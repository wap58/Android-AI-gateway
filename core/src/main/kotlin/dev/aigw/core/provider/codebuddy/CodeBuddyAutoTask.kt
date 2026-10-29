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
            val after = runCatching { reload() }.getOrNull()
            val cur = after?.firstOrNull { it.code == a.code }
            val reached = cur != null && (cur.claimed || (cur.target > 0 && cur.current >= cur.target))
            if (!reached) {
                out.add(Outcome(a.code, t.title, false,
                    "$detail（进度未变化" + (cur?.let { ": ${it.current}/${it.target}" } ?: "") + "）"))
                continue
            }
            val claimMsg = claim(a.code)
            out.add(Outcome(a.code, t.title, claimMsg == null,
                if (claimMsg != null) "$detail；$claimMsg" else "$detail；已领奖"))
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
        val (status, text) = post("/activity/growth/tasks/$clean/claim", "", "web")
        if (status !in 200..299) return "领奖失败 HTTP $status ${text.take(60)}"
        val obj = runCatching {
            com.google.gson.JsonParser.parseString(text).asJsonObject
        }.getOrNull()
        val data = obj?.getAsJsonObject("data") ?: obj
        val already = data?.get("already_claimed")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val credit = data?.get("credit")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        return when {
            already -> "已领取过"
            credit > 0 -> "领到 +$credit 积分"
            else -> null
        }
    }

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
    )

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
        for (i in 0 until need) {
            val base = "tyd-${System.currentTimeMillis()}-$i"
            val cid = "$base-conv"
            val rid = "$base-req"
            val mid = "$base-msg"
            reportDesktop(*desktopChatSequence(cid, rid, mid))
            if (i < need - 1) Thread.sleep(2000)
        }
        return "已补报 $need 轮桌面对话"
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
        val tpl = listOf("深度研究" to "1", "周报生成" to "2", "竞品分析" to "3",
            "活动策划" to "4", "代码评审" to "5")
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
            chain.add(mapOf("eventCode" to "template_used", "template_id" to id, "task_mode" to "working"))
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
