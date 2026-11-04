package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonObject
import dev.aigw.core.provider.ProviderTask
import java.security.MessageDigest

/**
 * WorkBuddy 成长中心「完成类」任务的事件上报。
 *
 * 依据 `linguo2625469/workbuddy2api-panel` 的 `internal/upstream/desktop.go`
 * （原作者抓包 + 多账号实测）。关键结论：
 *
 *  1. 判据不是独立端点，而是 `POST {chatBase}/v2/report` 上**不同的客户端指纹**。
 *  2. 事件字段是 **camelCase**（`eventCode`），不是 snake_case。
 *  3. 桌面类任务必须带整套桌面指纹（ideName/ideType/extName/machineId/os/arch…），
 *     否则上游照样回 200 但不计入。
 *  4. 事件体必须是 **JSON 数组**。
 */
internal class CodeBuddyAutoTask(
    private val chatBaseUrl: String,
    private val webBaseUrl: String,
    private val accessToken: String,
    private val uid: String,
    private val nickname: String,
    private val post: (path: String, body: String, mode: String) -> Pair<Int, String>,
    private val reload: () -> List<ProviderTask>,
    /** 真跑一次网页对话，返回 (conversationId, 回复正文)；失败返回 ("", "")。 */
    private val chatOnce: (prompt: String) -> Pair<String, String>,
) {

    data class Outcome(val code: String, val title: String, val ok: Boolean, val detail: String)

    private class Action(val code: String, val desc: String, val attempt: Boolean = false,
                         val run: (ProviderTask) -> String)

    /** 由 uid 稳定派生 36 位 hex 设备标识（同一账号每次相同，模拟固定设备）。 */
    private fun deriveId(salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest("$salt:$uid".toByteArray())
        return d.take(18).joinToString("") { "%02x".format(it) }
    }

    /** 桌面指纹公共字段（注入每个桌面事件）。 */
    private fun desktopFingerprint(): Map<String, Any> {
        val now = System.currentTimeMillis()
        return mapOf(
            "timezone" to "Asia/Shanghai",
            "reportDelay" to 2000,
            "userId" to uid,
            "username" to nickname,
            "userNickname" to nickname,
            "product" to "SaaS",
            "ideName" to "WorkBuddy",
            "ideType" to "WorkBuddy",
            "ideVersion" to "5.5.6",
            "machineId" to deriveId("machine"),
            "sessionId" to deriveId("session"),
            "extName" to "workbuddy-desktop",
            "extVersion" to "5.5.6",
            "os" to "win32",
            "arch" to "x64",
            "osVersion" to "10.0.26220",
            "cpuCores" to 20,
            "memorySize" to 24,
            "timestamp" to now,
            "presentAt" to now,
        )
    }

    /** 把业务字段 + 桌面指纹合成事件数组并上报（桌面指纹走 copilot 域）。 */
    private fun reportDesktop(vararg events: Map<String, Any>) {
        val fp = desktopFingerprint()
        val arr = events.joinToString(",", "[", "]") { ev ->
            val merged = LinkedHashMap<String, Any>(fp)
            merged.putAll(ev)   // 业务字段优先
            toJson(merged)
        }
        val (status, text) = post("/v2/report", arr, "desktop")
        if (status !in 200..299) throw IllegalStateException("桌面上报失败 HTTP $status: ${text.take(80)}")
    }

    /** web 域页面事件（浏览器形状指纹，用于资料库这类页面行为任务）。 */
    private fun reportWeb(eventCode: String, pageUrl: String, elementId: String, elementName: String) {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
        val ev = mapOf(
            "eventCode" to eventCode,
            "timestamp" to System.currentTimeMillis(),
            "reportDelay" to 0,
            "pageURL" to pageUrl,
            "elementId" to elementId,
            "elementName" to elementName,
            "os" to "Win32", "arch" to "", "osVersion" to "10.0", "userAgent" to ua,
            "machineId" to deriveId("webmachine"),
            "userId" to uid, "userNickname" to nickname,
        )
        val (status, text) = post("/v2/report", "[" + toJson(ev) + "]", "cli")
        if (status !in 200..299) throw IllegalStateException("web 上报失败 HTTP $status: ${text.take(80)}")
    }

    private fun toJson(m: Map<String, Any>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in m) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(k).append("\":")
            when (v) {
                is String -> sb.append(JsonObject().apply { addProperty("_", v) }
                    .get("_").toString())
                is Number, is Boolean -> sb.append(v.toString())
                else -> sb.append("\"").append(v.toString().replace("\"", "\\\"")).append('"')
            }
        }
        return sb.append('}').toString()
    }

    fun runAll(tasks: List<ProviderTask>): List<Outcome> {
        val out = ArrayList<Outcome>()
        for (a in ACTIONS) {
            val t = tasks.firstOrNull { it.code == a.code } ?: continue
            if (t.claimed) { out.add(Outcome(a.code, t.title, true, "已领取，跳过")); continue }
            if (t.target > 0 && t.current >= t.target) {
                val msg = claim(t.code)
                out.add(Outcome(a.code, t.title, msg == null, msg ?: "进度已达标，领奖成功"))
                continue
            }
            val detail = runCatching { a.run(t) }
                .getOrElse { e -> "失败：" + (e.message ?: e.javaClass.simpleName) }
            if (detail.startsWith("失败")) {
                out.add(Outcome(a.code, t.title, false, detail))
                continue
            }
            // 上报成功不等于任务点亮：必须**重查进度**确认达标，否则 claim 必然失败
            // （进度还是 0/N 时上游会拒，表现为「完成失败」，容易误判成上报出错）。
            // 服务端记账有延迟：立刻重查常拿到旧值（进度的更新可能落后于事件上报）。
            // 退避重试 3 次（0.5s / 1.2s / 2.5s），仍读不到才判失败。
            var after: List<ProviderTask>? = null
            var cur: ProviderTask? = null
            for (wait in longArrayOf(500, 1200, 2500)) {
                Thread.sleep(wait)
                after = runCatching { reload() }.getOrNull()
                cur = after?.firstOrNull { it.code == a.code }
                if (cur != null && (cur.claimed || (cur.target > 0 && cur.current >= cur.target))) break
            }
            val reached = cur != null && (cur.claimed || (cur.target > 0 && cur.current >= cur.target))
            if (!reached) {
                out.add(Outcome(a.code, t.title, false,
                    "$detail（进度未变化" + (cur?.let { ": ${it.current}/${it.target}" } ?: "") + "）"))
                continue
            }
            val claimMsg = claim(a.code).orEmpty()
            val claimOk = claimMsg.startsWith("OK|")
            val claimText = claimMsg.substringAfter("|", claimMsg)
            out.add(Outcome(a.code, t.title, claimOk, "$detail；$claimText"))
        }
        return out
    }

    /**
     * 领奖。
     *
     * ★ 端点必须是 **Web 域** `POST www.workbuddy.cn/activity/growth/tasks/{code}/claim`
     *   （任务码在**路径**里、无 body、带 `x-client-platform: web`）。
     *   CLI 域的 `/v2/.../reward/claim` 路径不存在，会一直 400 "task not completed"
     *   ——原项目注释里专门标注过这是「此前领奖失败的真实原因」。
     */
    private fun claim(code: String): String? {
        val clean = code.replace("\"", "")
        val path = "/activity/growth/tasks/$clean/claim"
        // 先走普通域（CLI 头），400 再降级 web 头——反过来会一直被拒
        var (status, text) = post(path, "{}", "chat")
        if (status == 400) {
            val fallback = post(path, "{}", "web")
            status = fallback.first
            text = fallback.second
        }
        val obj = runCatching {
            com.google.gson.JsonParser.parseString(text).asJsonObject
        }.getOrNull()
        val data = obj?.getAsJsonObject("data") ?: obj
        val msg = obj?.get("msg")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val already = (data?.get("already_claimed")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false) ||
            msg.contains("已领取") || msg.contains("已领过") ||
            msg.contains("already", ignoreCase = true)
        val credit = data?.get("credit")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        val energy = data?.get("energy")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        return when {
            already -> "OK|已领取过"
            credit > 0 -> "OK|领到 +$credit 积分" + (if (energy > 0) " +$energy 能量" else "")
            status in 200..299 -> "OK|已处理"
            else -> "FAIL|领奖失败 HTTP $status ${text.take(60)}"
        }
    }

    /** 最近一次真实对话的 conversationId（画布/专家类事件要挂在真实会话上）。 */
    private var lastConversationId: String? = null

    private val ACTIONS: List<Action> = listOf(
        Action("chat_5", "补报对话事件") { chat5(it) },
        Action("first_buddy", "上报解锁 → 同意协议 → 领养") { firstBuddy(it) },
        Action("Library_read", "上报资料库阅读") { libraryRead(it) },
        Action("automation_1", "上报定时任务创建") { automationCreate(it) },
        Action("Buddy_App", "上报 Buddy 应用进入链") { buddyApp(it) },
        Action("Buddy_App_QQ", "上报企鹅教师助手进入链") { buddyApp(it) },
        Action("RichMeow_Chat", "桌面端成功对话事件链", attempt = true) { richMeow(it) },
        Action("Model_chat_GLM5.2", "glm-5.2 模型对话上报", attempt = true) { modelChat(it) },
        Action("template_5", "使用 5 个模板") { templateUse(it) },
        Action("playbook_prompt", "灵感案例做同款") { playbookPrompt(it) },
        Action("expert_5", "召唤 5 次专家") { expertSummon(it) },
        Action("Expert_team_use_3", "召唤 3 次专家团") { teamSummon(it) },
        Action("black_cat", "夜猫子对话", attempt = true) { blackCat(it) },
        Action("create_canvas", "设计创意画布", attempt = true) { createCanvas(it) },
    )

    /**
     * create_canvas：设计创意模式。
     *
     * 主路径 = 真实对话 + 桌面事件链 + 两个画布事件（挂在**真实**的
     * conversationId / requestId 上）。失败再回落 web 域的裸事件——后者上游多半不认，
     * 只是聊胜于无。
     */
    private fun createCanvas(t: ProviderTask): String {
        val (convId, reply) = chatOnce("帮我在设计创意画布里做一张活动海报")
        if (reply.isNotEmpty()) {
            val cid = convId.ifEmpty { lastConversationId.orEmpty() }
            val rid = "cmb-" + java.util.UUID.randomUUID().toString().replace("-", "")
            if (!cid.isNullOrEmpty()) {
                val chain = desktopChatSequence(cid, rid, "msg-canvas").toMutableList()
                chain.add(mapOf(
                    "eventCode" to "wbx_design_canvas_task_create",
                    "conversationId" to cid, "requestId" to rid,
                    "source" to "summon_keyword", "isCustomModel" to false, "name" to "",
                    "inputLength" to 12,
                    "id" to "wbx-canvas-" + System.currentTimeMillis(), "cost" to 0,
                    "isSuccessful" to true,
                ))
                chain.add(mapOf(
                    "eventCode" to "wbx_design_canvas_open",
                    "conversationId" to cid, "requestId" to rid,
                    "id" to "ardot-file-" + rid.takeLast(8),
                    "source" to "summon_keyword", "type" to "page", "cost" to 13000,
                    "isSuccessful" to true,
                ))
                reportDesktop(*chain.toTypedArray())
                return "画布事件已上报（真实会话）"
            }
        }
        // 回落：web 域裸事件
        reportDesktop(
            mapOf("eventCode" to "agent_task_created", "source" to "CLOUD", "name" to "",
                "mode" to "craft", "requestModelId" to "default", "task_mode" to "design"),
            mapOf("eventCode" to "wbx_design_canvas_task_create"),
        )
        return "画布事件已上报（裸事件回退）"
    }

    /**
     * expert_5 / Expert_team_use_3：召唤 N 次专家。
     *
     * 事件用**真实专家 id**（来自内嵌的 expert_center.json），自造 id 不计数。
     * 每个专家发一对 `expert_summoned` + `expert_actual_use`，都挂同一个 requestId。
     */
    private fun expertSummon(t: ProviderTask): String {
        val target = if (t.target > 0) t.target else 5L
        val need = (target - t.current).coerceAtLeast(0)
        if (need <= 0) return "进度已达标"
        val experts = WbExpertCatalog.agents().ifEmpty { WbExpertCatalog.all() }
        if (experts.isEmpty()) return "专家目录为空"
        var sent = 0
        for (i in 0 until need.toInt()) {
            val e = experts[i % experts.size]
            val rid = newId("cmb")
            val cid = newId("cmb")
            reportDesktop(
                mapOf(
                    "eventCode" to "expert_summoned", "id" to e.id, "name" to e.name,
                    "type" to "agent", "expertTitle" to e.profession, "expertType" to "agent",
                ),
                mapOf(
                    "eventCode" to "expert_actual_use", "id" to e.id, "name" to e.name,
                    "type" to e.industryId, "expertType" to "agent", "source" to "builtin",
                    "version" to "1.0", "cost" to 0, "characterCount" to 12,
                    "conversationId" to cid, "requestId" to rid,
                    "messageId" to "msg-" + rid.takeLast(8),
                    "requestModelId" to "deepseek-v4-flash",
                    "requestModelName" to "DeepSeek V4 Flash",
                ),
            )
            sent++
            if (i < need - 1) Thread.sleep(2000)
        }
        return "召唤 $sent 位专家"
    }

    /** 专家团：必须用 team 类型的条目，普通专家计数不到这个任务。 */
    private fun teamSummon(t: ProviderTask): String {
        val need = (t.target - t.current).coerceAtLeast(1)
        val teams = WbExpertCatalog.teams()
        if (teams.isEmpty()) return "失败：本地专家团目录为空"
        var sent = 0
        for (i in 0 until need.toInt()) {
            val e = teams[i % teams.size]
            val rid = newId("cmb")
            val cid = newId("cmb")
            reportDesktop(
                mapOf(
                    "eventCode" to "expert_summoned", "id" to e.id, "name" to e.name,
                    "type" to "team", "expertTitle" to e.profession, "expertType" to "team",
                ),
                mapOf(
                    "eventCode" to "expert_actual_use", "id" to e.id, "name" to e.name,
                    "type" to e.industryId, "expertType" to "team", "source" to "builtin",
                    "version" to "1.0", "cost" to 0, "characterCount" to 12,
                    "conversationId" to cid, "requestId" to rid,
                    "messageId" to "msg-" + rid.takeLast(8),
                    "requestModelId" to "deepseek-v4-flash",
                    "requestModelName" to "DeepSeek V4 Flash",
                ),
            )
            sent++
            if (i < need - 1) Thread.sleep(2000)
        }
        return "召唤 $sent 个专家团"
    }

    /**
     * black_cat 夜猫子：**仅 23:00–08:00（CST）计数**，官方规则「每日 1 次 × 累计 3 天」。
     *
     * 所以这里只发一次真实对话——一晚聊三次不算三天，多发无益。
     * 不在时间窗内直接返回，避免白掉积分。
     */
    private fun blackCat(t: ProviderTask): String {
        val hour = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("Asia/Shanghai"),
        ).get(java.util.Calendar.HOUR_OF_DAY)
        if (hour < 23 && hour > 8) {
            return "跳过：夜猫子仅 23:00-08:00 计数（当前 ${hour} 点）"
        }
        val prompts = listOf("今天天气怎么样？", "1+1等于几？", "讲个笑话")
        for (attempt in 0 until 3) {
            val (convId, reply) = chatOnce(prompts[attempt % prompts.size])
            if (reply.isNotEmpty()) {
                lastConversationId = convId
                return "夜猫子对话完成（回复 ${reply.length} 字）"
            }
            Thread.sleep(5000)
        }
        return "夜猫子对话无响应（已重试 3 次）"
    }

    /** 生成上游形态的 id：`前缀-32位hex`。 */
    private fun newId(prefix: String): String =
        prefix + "-" + java.util.UUID.randomUUID().toString().replace("-", "")

    /** 拉服务端模板场景表 `[(id, name)]`；失败返回空表，由调用方回落内置表。 */
    private fun fetchScenes(): List<Pair<String, String>> = runCatching {
        val (code, text) = post("/console/as/support/scenes?locale=zh-CN", "", "web")
        if (code !in 200..299) return@runCatching emptyList()
        val root = com.google.gson.JsonParser.parseString(text).asJsonObject
        val data = root.getAsJsonObject("data") ?: return@runCatching emptyList()
        val scenes = data.getAsJsonArray("scenes") ?: return@runCatching emptyList()
        val out = ArrayList<Pair<String, String>>()
        scenes.forEach { el ->
            val o = el.asJsonObject
            val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (id.isNotEmpty()) {
                val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                out.add(id to name)
            }
        }
        out
    }.getOrDefault(emptyList())

    /**
     * chat_5：补足「和 AI 聊天 N 次」。
     *
     * 必须是**桌面端完整对话事件链**（6 个事件），不是 CLI 形状的单条
     * `chat_request_send`——后者实测被上游 400 拒（code 10001）。
     */
    private fun chat5(t: ProviderTask): String {
        val target = if (t.target > 0) t.target else 5L
        val need = target - t.current
        if (need <= 0) return "进度已达标"
        var real = 0
        val prompts = listOf("你好", "今天天气怎么样？", "1+1等于几？", "Python 是什么？", "推荐一本好书")
        for (i in 0 until need.toInt()) {
            // 先真跑一遍对话：上游会校验 conversationId 是否存在，纯事件上报不计进度
            val (convId, reply) = chatOnce(prompts[i % prompts.size])
            if (reply.isNotEmpty()) {
                lastConversationId = convId
                real++
                Thread.sleep(3000)
            } else {
                // 真实对话失败（额度/网络）再回退到事件链，至少争取一次机会
                val base = "tyd-${System.currentTimeMillis()}-$i"
                reportDesktop(*desktopChatSequence("$base-conv", "$base-req", "$base-msg"))
                Thread.sleep(1500)
            }
        }
        return if (real > 0) "真实对话 $real 轮" else "已补报 $need 轮事件"
    }

    /** 桌面端一次成功对话的完整事件链（照抄客户端形状，缺事件不计数）。 */
    private fun desktopChatSequence(cid: String, rid: String, mid: String): Array<Map<String, Any>> {
        val now = System.currentTimeMillis()
        val modelId = "deepseek-v4-flash"
        val modelName = "DeepSeek V4 Flash"
        val asst = "$mid-assistant"
        fun mk(code: String, extra: Map<String, Any> = emptyMap()): Map<String, Any> {
            val m = LinkedHashMap<String, Any>()
            m["eventCode"] = code
            m.putAll(extra)
            return m
        }
        return arrayOf(
            mk("agent_task_created", mapOf(
                "source" to "LOCAL", "name" to "working", "task_target" to "local", "mode" to "craft",
                "requestModelId" to modelId, "requestModelName" to modelName,
                "has_repo" to false, "repo_type" to "none", "workspace_type" to "empty",
                "has_connector" to false, "has_mention" to false, "has_template" to false,
                "has_expert" to false, "has_skill" to false,
                "conversationId" to cid, "messageId" to mid, "buddyId" to "", "buddyName" to "",
            )),
            mk("chat_message_send", mapOf(
                "messageId" to asst, "historyCount" to 0, "isContextTruncated" to false,
                "currentStepCount" to 1, "traceId" to rid, "rootRequestId" to rid,
                "parentConversationId" to cid, "agentName" to "cli", "agentType" to "main",
            )),
            mk("chat_request_send", mapOf(
                "inputLength" to 24, "isPlan" to false, "isAutoExecuteTerminal" to false,
                "isAutoModify" to false, "codebaseEnable" to false, "maxToken" to 0,
                "maxSteps" to 500, "temperature" to 0, "maxRetries" to 0,
                "mentionContextCount" to 0, "command" to "", "recommendId" to "",
                "skillId" to "", "skillCount" to 0, "totalCount" to 0,
                "traceId" to rid, "rootRequestId" to rid, "parentConversationId" to cid,
                "agentName" to "cli", "agentType" to "main",
                "codebuddy.session_id" to cid, "codebuddy.conversation_request_id" to rid,
            )),
            mk("chat_message_response", mapOf(
                "messageId" to asst, "responseModelId" to modelId,
                "inputToken" to 120, "outputToken" to 80, "totalToken" to 200,
                "cachedTokens" to 0, "cachedWriteTokens" to 0, "cachedMissTokens" to 0,
                "isSuccessful" to true, "messageErrorCode" to "", "finishReason" to "stop",
                "firstTokenAt" to now, "traceId" to rid, "conversationId" to cid,
                "rootRequestId" to rid, "parentConversationId" to cid,
                "agentName" to "cli", "agentType" to "main",
                "codebuddy.session_id" to cid, "codebuddy.conversation_request_id" to rid,
            )),
            mk("chat_message_status", mapOf(
                "messageId" to asst, "messageErrorCode" to "0", "traceId" to rid,
                "rootRequestId" to rid, "parentConversationId" to cid,
                "agentName" to "cli", "agentType" to "main",
            )),
            mk("chat_request_response", mapOf(
                "mode" to "craft", "toolCallCount" to 0,
                "inputToken" to 120, "outputToken" to 80, "totalToken" to 200,
                "cachedTokens" to 0, "cachedWriteTokens" to 0, "cachedMissTokens" to 0,
                "isSuccessful" to true, "messageErrorCode" to "", "finishReason" to "stop",
                "rootRequestId" to rid, "parentConversationId" to cid,
            )),
        )
    }

    /** first_buddy：上报解锁 → 同意协议 → 领养。 */
    private fun firstBuddy(t: ProviderTask): String {
        chat5(ProviderTask(code = "chat_5", title = "chat_5", current = 0, target = 3))
        Thread.sleep(1500)
        post("/v2/activity/growth/buddy/agreement", "{}", "cli")
        Thread.sleep(800)
        val (status, text) = post("/v2/activity/growth/buddy/first", "{}", "cli")
        if (status !in 200..299) return "失败：领养 HTTP $status ${text.take(60)}"
        return "已解锁并领养 Buddy"
    }

    /** Library_read：web 域页面点击事件。 */
    private fun libraryRead(t: ProviderTask): String {
        reportWeb("web_element_click",
            "https://www.workbuddy.cn/space/d/o0KWYeynteVv06UnAZqIFm",
            "library_doc_intro_click", "WorkBuddy资料库介绍")
        return "已上报资料库阅读"
    }

    /** automation_1：定时任务创建成功事件（桌面形状）。 */
    private fun automationCreate(t: ProviderTask): String {
        reportDesktop(mapOf(
            "eventCode" to "automated_task_create_suc",
            "name" to "tyd 自动化", "source" to "manually",
            "modelId" to "fast-model", "modelIsThinking" to true,
            "connectorCount" to 0, "skills" to "", "skillCount" to 0,
            "scheduleType" to "once", "mode" to "LOCAL",
        ))
        return "已上报定时任务创建"
    }

    /** Buddy_App / Buddy_App_QQ：五连事件。 */
    private fun buddyApp(t: ProviderTask): String {
        val id = "cb_y5Dy46tPQGGWtueMxXbe"
        val name = "企鹅教师助手"
        fun mk(code: String, extra: Map<String, Any> = emptyMap()): Map<String, Any> {
            val m = LinkedHashMap<String, Any>()
            m["eventCode"] = code
            m["mode"] = "LOCAL"
            m["buddyId"] = id
            m["buddyName"] = name
            m.putAll(extra)
            return m
        }
        reportDesktop(
            mk("buddyapp_discover_click"),
            mk("buddyapp_show", mapOf("elementId" to id, "elementName" to name, "position" to 2)),
            mk("buddyapp_enter_click", mapOf("elementId" to id, "elementName" to name,
                "position" to 2, "isFirstPage" to "1")),
            mk("buddyapp_auth_confirm_click", mapOf("elementId" to id, "elementName" to name)),
            mk("buddyapp_bindaccount_skip_click", mapOf("elementId" to id, "elementName" to name)),
        )
        return "已上报应用进入五连事件"
    }

    /** RichMeow_Chat：桌面端完整成功对话链。 */
    private fun richMeow(t: ProviderTask): String {
        val cid = "tyd-rm-${System.currentTimeMillis()}"
        val mid = "$cid-msg"
        fun mk(code: String, extra: Map<String, Any> = emptyMap()): Map<String, Any> {
            val m = LinkedHashMap<String, Any>()
            m["eventCode"] = code
            m["conversationId"] = cid
            m["requestId"] = cid
            m["messageId"] = mid
            m["modelId"] = "deepseek-v4-flash"
            m["modelName"] = "DeepSeek V4 Flash"
            m.putAll(extra)
            return m
        }
        reportDesktop(
            mk("agent_task_created"),
            mk("chat_message_send"),
            mk("chat_request_send"),
            mk("chat_message_response", mapOf("isSuccessful" to true)),
            mk("chat_message_status", mapOf("status" to "success")),
            mk("chat_request_response", mapOf("isSuccessful" to true)),
        )
        return "已上报桌面对话成功链"
    }

    /** Model_chat_GLM5.2：带 glm-5.2 模型的对话上报。 */
    private fun modelChat(t: ProviderTask): String {
        val cid = "tyd-glm-${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        val ev = mapOf(
            "event_code" to "chat_request_send", "timestamp" to now, "report_delay" to 0,
            "mode" to "craft", "conversation_id" to cid, "request_id" to cid,
            "input_length" to 12, "request_model_id" to "glm-5.2",
            "request_model_name" to "GLM-5.2", "is_plan" to false,
            "agent_name" to "default", "agent_type" to "conversation",
            "present_at" to now, "root_request_id" to cid,
            "parent_conversation_id" to cid, "user_id" to uid,
        )
        val (st, tx) = post("/v2/report", "[" + toJson(ev) + "]", "cli")
        if (st !in 200..299) return "失败：上报 HTTP $st ${tx.take(60)}"
        return "已按 glm-5.2 上报对话"
    }

    /**
     * template_5：使用 5 个模板。
     *
     * 每组 = 完整对话链 + `agent_task_created_with_template` + `template_used`。
     * 只报 `template_used` 单条不计数（实测：必须挂在一轮合法对话上）。
     */
    private fun templateUse(t: ProviderTask): String {
        val target = if (t.target > 0) t.target else 5L
        val need = target - t.current
        if (need <= 0) return "进度已达标"
        // 真实场景 id：上游 /console/as/support/scenes；拉不到就用内置兜底表（实测线上 id）
        val tpl = fetchScenes().ifEmpty {
            listOf("幻灯片" to "0", "深度研究" to "4", "数据分析" to "8",
                "设计" to "16", "日常开发" to "20")
        }
        for (i in 0 until need) {
            val (name, id) = tpl[(i % tpl.size).toInt()]
            val base = "tyd-tpl-${System.currentTimeMillis()}-$i"
            val cid = "$base-conv"
            val rid = "$base-req"
            val chain = desktopChatSequence(cid, rid, "msg-$id").toMutableList()
            chain.add(mapOf(
                "eventCode" to "agent_task_created_with_template", "mode" to "working",
                "isCustomModel" to false, "id" to id, "name" to name, "requestId" to rid,
            ))
            chain.add(mapOf("eventCode" to "template_used", "template_id" to id, "templateId" to id,
                "templateName" to name, "task_mode" to "working", "id" to id, "name" to name,
                "source" to "growth-center"))
            // ext1 是幂等键，缺了这条不计数（上游实测）
            chain.add(mapOf("eventCode" to "playbook_prompt_send", "ext1" to java.util.UUID.randomUUID().toString(),
                "requestId" to rid, "id" to id, "name" to name, "type" to "other",
                "promptLength" to 30, "isOfficial" to 1, "source" to "growth-center"))
            reportDesktop(*chain.toTypedArray())
            if (i < need - 1) Thread.sleep(1200)
        }
        return "已上报 $need 次模板使用"
    }

    /**
     * playbook_prompt：灵感案例「做同款」。
     *
     * 判据是 `playbook_prompt_send`（Dialog 发送 Prompt），不是卡片曝光/点击。
     */
    private fun playbookPrompt(t: ProviderTask): String {
        val base = "tyd-pb-${System.currentTimeMillis()}"
        val cid = "$base-conv"
        val rid = "$base-req"
        val caseId = "pm-gtm-launch-plan"
        val caseName = "新产品上市 GTM 发布计划一页纸"
        val chain = desktopChatSequence(cid, rid, "msg-pb").toMutableList()
        val payload = mapOf(
            "id" to caseId, "name" to caseName, "type" to "document",
            "categoryId" to "", "categoryName" to "",
        )
        chain.add(mapOf(
            "eventCode" to "web_element_click", "pageName" to "playbook_detail",
            "elementId" to "playbook_ctaClick", "elementName" to caseName, "source" to "discover",
        ))
        chain.add(LinkedHashMap<String, Any>().apply {
            put("eventCode", "playbook_cta_click"); put("source", "discover"); put("position", 0)
            putAll(payload)
        })
        chain.add(LinkedHashMap<String, Any>().apply {
            put("eventCode", "playbook_prompt_send"); put("conversationId", cid); put("requestId", rid)
            putAll(payload)
        })
        reportDesktop(*chain.toTypedArray())
        return "已上报灵感做同款事件组"
    }
}
