package dev.aigw.core.provider.autoclaw

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ACTION_CHECKIN_DAILY
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.UpstreamError
import java.net.HttpURLConnection
import java.net.URL

/**
 * AutoClaw（智谱 AutoGLM 客户端）供应商。
 *
 * ## 和别家最大的不同
 * 推理不是走标准 OpenAI 路径，而是 `autoclaw-proxy/proxy/autoclaw/v1/chat/completions`，
 * 且模型名必须换成 **route id**（`zai_glm-5-turbo`、`tdpsk_deepseek-v4-pro-202606` 之类），
 * 通过 `X-Request-Model` 头传递。响应本身是标准 OpenAI 格式，不用解包。
 *
 * ## 登录
 * OAuth（zai 智谱 / google）。本类只做「拿授权码换 token」和「刷新」，拉起浏览器
 * 交授权页由 WebLoginSupport 负责——但 AutoClaw 的 OAuth 页带着验证码，
 * 实际更常用的是「粘贴凭证导入」。
 */
internal class AutoClawProvider(
    private val hooks: ProviderHooks = ProviderHooks(),
) : Provider, dev.aigw.core.provider.SmsLoginSupport {

    private val smsLogin = AutoClawSmsLogin { hooks.onLog(it) }

    override val id: String = AutoClawConstants.ID
    override val displayName: String = AutoClawConstants.DISPLAY_NAME
    override val authKind: AuthKind = AuthKind.SMS_CODE
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.CHECKIN)

    override fun hosts(): List<String> = listOf("autoglm.ai")

    // ------------------------------------------------------------------ 模型目录

    /**
     * 参考实现里**实测能推理**的模型集合（2026-09 验证）。
     *
     * 上游没有 `/models` 接口可直接拉，所以这里是静态目录；模型一旦新增，
     * 用户仍可用「route id 直填」的方式绕过（[resolveModel] 会原样放行含下划线的名字）。
     */
    /**
     * 内置模型，来自官方文档（`docs/advanced/models/`，2026-11 核实）：
     * 「Autoclaw 平台内置智谱旗舰大模型 GLM-5.3，GLM-5-Turbo、GLM-5V-Turbo 和 Auto 模式四种模型」。
     *
     * 之前按第三方反代猜的 glm-5.3-flash / deepseek-v4-* 并不在官方清单里；
     * 用户要接入 Coding Plan 或其他第三方模型时，用 `model: 自定义名` 直填即可，
     * [resolveModel] 会原样放行。
     */
    /**
     * 官方客户端实际配置的 5 个模型（取自澳龙 `openclaw.json` 的
     * `models.providers.zai.models`，2026-11 核实）。
     *
     * 之前按官网文档写的 4 个（auto/glm-5-turbo/glm-5v-turbo/glm-5.3）是产品宣传口径；
     * 真正可用的 route id 是下面这些，其中 `zai_glm-5.3-flash` 和
     * `zai_glm-5.3-flashx-legal` 只在客户端配置里出现。
     */
    private val catalog = listOf(
        // 只留真实模型。官方 App 列的 5 个里：
        //   auto / auto-fast → **场景位**（自动选模型），不是可指定的模型本体
        //   glm-5.3-flashx-legal → 法律垂直模型，不适合通用对话
        // 实测可用且通用的只有下面两个（2026-11-10 探活 200）。
        ProviderModel(id = "glm-5.3", name = "GLM-5.3", contextWindow = 1_048_576),
        ProviderModel(id = "glm-5.3-flash", name = "GLM-5.3-Flash", contextWindow = 1_048_576),
    )

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView =
        ProviderModelCatalogView(models = catalog, fromFallback = false, error = "")

    override fun resolveModel(requested: String): String {
        val key = requested.substringAfterLast('/').trim()
        if (key.isEmpty()) return "glm-5.3-flash"
        // 含下划线的已经是 route id，原样放行供高级用户直填
        if (key.contains('_')) return key
        // 目录里没有也放行：上游可能已上线新模型，交给它自己判定
        return key
    }

    // ------------------------------------------------------------------ 对话

    private val sandbox = AutoClawSandbox { hooks.onLog(it) }

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = AutoClawCredential.parse(account.secret)
            ?: return FailedChatCall(401, "凭证损坏，请重新登录")

        val body = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull()
            ?: return FailedChatCall(400, "请求体不是合法 JSON")
        val stream = body.get("stream")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        val modelKey = resolveModel(body.str("model"))
        val routeId = AutoClawConstants.routeId(modelKey)

        // body 里的 model 给裸名；推理不认 route id 前缀，只认裸名 + x-openclaw-model
        val outbound = body.deepCopy().apply {
            // ★ 沙箱网关的路由规则（来自 autoclaw2api 的 prepareModelBody）：
            // body.model 固定写 "openclaw"（agent 目标名），真正的后端模型走
            // x-openclaw-model 头。直接传裸模型名会让网关串号（请求 glm 却返回 DeepSeek）。
            addProperty("model", "openclaw")
            // 官方声明 requiresReasoningContentOnAssistantMessages：
            // assistant 消息缺 reasoning_content 会 400，补空串
            body.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
                val m = runCatching { el.asJsonObject }.getOrNull() ?: return@forEach
                if (m.str("role") == "assistant" && m.get("reasoning_content") == null) {
                    m.addProperty("reasoning_content", "")
                }
            }
        }.toString()

        // 1) 准备沙箱（缓存 → list → apply），没有沙箱无法对话
        val box = sandbox.ensure(credential)
        if (box == null) {
            hooks.onLog("AutoClaw 拿不到沙箱，无法对话")
            return FailedChatCall(503, "拿不到 AutoClaw 沙箱，请稍后重试")
        }
        // 2) 唤醒（不唤醒会超时/503）
        sandbox.wake(credential, box)

        hooks.onLog("AutoClaw 对话 model=$modelKey route=$routeId stream=$stream sandbox=${box.id} bodyLen=${outbound.length}")

        // 3) 打沙箱网关
        val (status, input, err) = sandbox.chat(
            credential, box, outbound, stream,
            backendModel = AutoClawConstants.bodyModel(routeId),
        )
        if (status !in 200..299 || input == null) {
            hooks.onLog("AutoClaw 对话失败 HTTP $status：${err.take(300)}")
            return FailedChatCall(if (status == 0) 0 else status, mapError(status, err))
        }

        // 4) 沙箱端点可能轮换，把最新的沙箱信息写回账号
        if (box.id != credential.sandboxId || box.endpoint != credential.sandboxEndpoint) {
            runCatching {
                hooks.onAccountUpdated(
                    account.copy(
                        secret = credential.copy(
                            sandboxId = box.id,
                            sandboxEndpoint = box.endpoint,
                            sandboxEnd = box.endTimestamp,
                        ).toSecret(),
                    ),
                )
            }
        }

        if (!stream) {
            val text = runCatching { input.readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
            return AggregatedChatCall(status, text)
        }
        return StreamingChatCall(status, input) { runCatching { input.close() } }
    }

    // ------------------------------------------------------------------ 刷新

    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = AutoClawCredential.parse(account.secret) ?: return null
        if (credential.refreshToken.isEmpty()) {
            hooks.onLog("AutoClaw 刷新 → 该账号没有 refresh_token，无法刷新")
            return null
        }

        val body = JsonObject().apply {
            addProperty("refresh_token", credential.refreshToken)
            addProperty("device_id", deviceId())
            addProperty("source_id", AutoClawConstants.SOURCE_ID)
        }.toString()

        val (status, text) = httpPost("${AutoClawConstants.USER_API}${AutoClawConstants.PATH_REFRESH}", body)
        if (status !in 200..299) {
            hooks.onLog("AutoClaw 刷新 → HTTP $status ${text.take(200)}")
            return null
        }

        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val code = root.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        if (code != 0) {
            hooks.onLog("AutoClaw 刷新失败 code=$code ${root.str("msg").take(120)}")
            return null
        }
        val data = root.obj("data") ?: root
        // 字段名以实测为准；这里同时容忍驼峰/下划线两种写法，取不到就不动原值
        val newToken = data.str("access_token").ifEmpty { data.str("accessToken") }
        if (newToken.isEmpty()) {
            hooks.onLog("AutoClaw 刷新拿不到 access_token，响应字段=${data.keySet().joinToString(",")}")
            return null
        }
        val newRefresh = data.str("refresh_token").ifEmpty { data.str("refreshToken") }
            .ifEmpty { credential.refreshToken }

        hooks.onLog("AutoClaw 刷新成功 token=${newToken.take(12)}…")
        val updated = credential.copy(
            accessToken = newToken,
            refreshToken = newRefresh,
            userName = credential.userName,
        )
        return account.copy(secret = updated.toSecret())
    }

    // ------------------------------------------------------------------ 短信登录（国内站）

    override fun sendSmsCode(phone: String, ccode: String): String = smsLogin.sendSmsCode(phone, ccode)

    override fun loginBySmsCode(
        phone: String,
        code: String,
        msgid: String,
        ccode: String,
    ): ProviderAccount = smsLogin.loginBySmsCode(phone, code, msgid, ccode)

    override fun importCredentials(raw: String): ProviderAccount? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // 支持三种形态：完整 JSON / 直接粘贴 access token / "账号<TAB>access<TAB>refresh"
        val credential = if (trimmed.startsWith("{")) {
            runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull()?.let { o ->
                val token = o.str("access_token").ifEmpty { o.str("accessToken") }
                if (token.isEmpty()) null
                else AutoClawCredential(
                    accessToken = token,
                    refreshToken = o.str("refresh_token").ifEmpty { o.str("refreshToken") },
                    userId = o.str("user_id").ifEmpty { o.str("userId") },
                    userName = o.str("user_name").ifEmpty { o.str("userName") },
                )
            }
        } else {
            val parts = trimmed.split('\t', '|').map { it.trim() }.filter { it.isNotEmpty() }
            when (parts.size) {
                1 -> AutoClawCredential(accessToken = parts[0])
                2 -> AutoClawCredential(accessToken = parts[0], refreshToken = parts[1])
                else -> AutoClawCredential(
                    accessToken = parts[0],
                    refreshToken = parts[1],
                    userName = parts[2],
                )
            }
        } ?: return null

        if (credential.accessToken.isEmpty()) return null
        val uid = credential.userId.ifEmpty {
            // 没有 user_id 时用 token 的哈希做稳定标识，保证重复导入不会产生新账号
            val digest = java.security.MessageDigest.getInstance("MD5")
                .digest(credential.accessToken.toByteArray())
            digest.joinToString("") { "%02x".format(it) }.take(16)
        }
        return ProviderAccount(
            providerId = id,
            uid = uid,
            nickname = credential.userName.ifEmpty { displayName },
            secret = credential.toSecret(),
        )
    }

    // ------------------------------------------------------------------ 任务

    /**
     * 任务清单（硬编码，2026-11-10 实机流水验证）。
     *
     * 为什么不读 task-list：那份列表只有 4 条 daily，**不含两个 lobster 新手任务**，
     * 但 `task-complete` 提交 lobster id 确实能领到 500+500（14:55 流水实证）。
     * 所以新手任务只能硬编码提交，不能以 task-list 为准。
     *
     * 另一类「每周限时激励 +5000」「随时随地使用 AutoClaw +500」是登录型奖励，
     * 上游只在官方 App 登录时下发，API 领不了，这里不做也不假装能做。
     */
    private val dailyTasks = listOf("daily_signin" to 200)
    private val newbieTasks = listOf(
        "newbie_cloud_lobster" to 500,
        "newbie_local_lobster" to 500,
    )

    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult {
        val credential = AutoClawCredential.parse(account.secret)
            ?: return ProviderActionResult.failure("凭证损坏")
        return when (action) {
            // 每日签到 = 签到 + 灵感中心 + 登录活跃（活动福利，见 claimDaily）
            ACTION_CHECKIN_DAILY -> claimDaily(credential, account.uid)

            // 新手任务：只领旧版两个 lobster，不触发活跃钩子
            ACTION_TASKS -> claimTasks(credential, newbieTasks, "新手任务")

            // 批量签到：全领一遍，含活跃触发
            ACTION_CHECKIN -> claimDaily(credential, account.uid, includeNewbie = true)

            else -> ProviderActionResult.unsupported(action)
        }
    }

    /**
     * 每日签到（含活动福利触发）。
     *
     * 照搬 ProvidersPlugin/Agent2Providers 的分层：
     *   1. 先领任务，成败就是这条结果行的成败
     *   2. 看本次有没有涨分
     *   3. 没涨 → 打一次 user-info（客户端启动同款「登录」动作）让服务端发 campaign 奖
     *      涨了就不打（用户当天开过客户端时零浪费）
     *
     * 这样「每日签到」的文案只反映签到本身，不再和 5000 混在一起。
     */
    private fun claimDaily(
        credential: AutoClawCredential,
        uid: String = "",
        includeNewbie: Boolean = false,
    ): ProviderActionResult {
        val tasks = if (includeNewbie) dailyTasks + newbieTasks else dailyTasks
        val base = claimTasks(credential, tasks, "签到", withInspiration = true)

        // 本次已涨分就不再打钩子
        if (base.kind != "already") return base

        // 没涨分（今天已领过或上游幂等）：补一次登录活跃。
        // 活动奖（周激励 5000 / 新手 7 天礼）和签到是两套发放，签到「已领过」不代表活动奖也到了。
        val before = sandbox.points(credential)
        if (uid.isNotEmpty()) {
            if (activeTriggeredOn[uid] == todayKey()) return base
            activeTriggeredOn[uid] = todayKey()
        }
        if (!triggerLoginActive(credential)) return base
        Thread.sleep(PROMOTION_SETTLE_MS)
        val after = sandbox.points(credential)
        val gained = if (before != null && after != null) (after - before).toInt() else 0
        return if (gained > 0) {
            ProviderActionResult.success("签到 +$gained 积分（当前 ${after?.toLong() ?: "-"}）")
        } else {
            base
        }
    }

    /**
     * 领取一组任务。
     *
     * **判定只信积分增量**：上游 `task-complete` 的响应里没有 `success` 字段，
     * 之前按 `success == true` 判定，缺字段就当成「已领完」，
     * 于是明明领到了（流水 +500 已入账）界面却报「已领完」。
     *
     * 现在：只要 HTTP 2xx 就算提交成功；涨分才算真到账；
     * 提交了但没涨分，如实说「已提交」而不是谎报「已领完」。
     */
    private fun claimTasks(
        credential: AutoClawCredential,
        tasks: List<Pair<String, Int>>,
        label: String,
        withInspiration: Boolean = false,
    ): ProviderActionResult {
        val before = sandbox.points(credential)
        var submitted = 0
        var already = 0
        val failures = mutableListOf<String>()

        for ((taskId, _) in tasks) {
            val body = JsonObject().apply { addProperty("task_id", taskId) }.toString()
            val (status, text) = signedPost(
                "${AutoClawConstants.userApiHost(credential.realm)}/autoclaw-proxy/proxy/autoclaw-task-complete",
                credential,
                body,
            )
            if (status !in 200..299) {
                failures.add("$taskId(HTTP $status)")
                continue
            }
            submitted++
            // 上游对已领过的任务回 already_completed=true；这个字段可信，如实计数
            val done = runCatching { JsonParser.parseString(text).asJsonObject }
                .getOrNull()?.obj("data")
                ?.get("already_completed")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            if (done) already++
            hooks.onLog("AutoClaw $label $taskId → ${text.take(200)}")
        }
        if (withInspiration) claimInspiration(credential)?.let { submitted++ }

        val after = sandbox.points(credential)
        val gained = if (before != null && after != null) (after - before).toInt() else 0
        val allAlready = submitted > 0 && already == submitted
        return when {
            gained > 0 -> ProviderActionResult.success("$label +$gained 积分（当前 ${after?.toLong() ?: "-"}）")
            failures.isNotEmpty() -> ProviderActionResult.failure("$label 失败：${failures.joinToString(",")}")
            allAlready -> ProviderActionResult.success("${label}今日已领取").copy(kind = "already")
            // 提交成功但没涨分：上游幂等，通常是今天已经领过（task-list 状态滞后）
            submitted > 0 -> ProviderActionResult.success("${label}今日已领取").copy(kind = "already")
            else -> ProviderActionResult.failure("没有可领取的任务")
        }
    }

    /**
     * 领灵感中心奖励。返回「已领过/无条目」的计数用于文案。
     *
     * 链路：`inspiration-center` 取 `inspiration_id` → `inspiration-task-complete` 领奖。
     * 之前直接调 `daily_inspiration_center` 任务 id 是错的，那个 task 不存在。
     * 幂等：560118 = 今日已完成。
     */
    private fun claimInspiration(credential: AutoClawCredential): Int? {
        val host = AutoClawConstants.userApiHost(credential.realm)
        val listBody = JsonObject().apply {
            addProperty("source_id", AutoClawConstants.SOURCE_ID)
            addProperty("device_id", deviceId())
            addProperty("biz_app_id", "autoclaw")
        }.toString()
        val (ls, lt) = signedPost("$host/agentdr/v1/assistant/inspiration-center", credential, listBody)
        if (ls !in 200..299) return null
        val root = runCatching { JsonParser.parseString(lt).asJsonObject }.getOrNull() ?: return null
        val arr = (root.obj("data")?.get("list") ?: root.get("list"))
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        var already = 0
        for (el in arr) {
            val o = runCatching { el.asJsonObject }.getOrNull() ?: continue
            val id = o.str("inspiration_id").ifEmpty { o.str("id") }
            if (id.isEmpty()) continue
            val body = JsonObject().apply {
                addProperty("source_id", AutoClawConstants.SOURCE_ID)
                addProperty("device_id", deviceId())
                addProperty("inspiration_id", id)
            }.toString()
            val (cs, ct) = signedPost(
                "$host/agentdr/v1/assistant/inspiration-task-complete",
                credential,
                body,
            )
            if (cs !in 200..299) continue
            val code = runCatching { JsonParser.parseString(ct).asJsonObject }
                .getOrNull()?.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            if (code != 0) already++
        }
        return already
    }

    /**
     * 打「登录活跃」钩子，触发服务端 `event_type=autoclaw_user_active` 的活动奖。
     *
     * ★ 关键发现（来源：ProvidersPlugin/Agent2Providers 插件源码 checkin.rs 注释，
     *   ​2026-10-15 实测）：服务端把「登录」的判据挂在 `POST /userapi/v1/user-info` 上，
     *   这是客户端每次启动都会打的接口。签到本身、`refresh` 强制刷新、home-fetch
     *   **都不触发**发放；只有 user-info 打下去，「每周限时激励 5000」「随时随地使用
     *   AutoClaw 500」这类无 grant_id 的 campaign 奖才立刻入账。
     *
     * 特性：只带 access token（不轮换 token，无副作用）、幂等（重复调用不重复入账）、
     * 版本无关。body 与客户端 `withWebInfo` 一致：source_id + device_id。
     */
    private fun triggerLoginActive(credential: AutoClawCredential): Boolean {
        val body = JsonObject().apply {
            addProperty("source_id", AutoClawConstants.SOURCE_ID)
            addProperty("device_id", deviceId())
        }.toString()
        val (status, text) = signedPost(
            "${AutoClawConstants.userApiHost(credential.realm)}/userapi/v1/user-info",
            credential,
            body,
        )
        val ok = status in 200..299
        hooks.onLog("AutoClaw 登录活跃钩子 HTTP $status${if (ok) "" else "：" + text.take(150)}")
        return ok
    }

    /** 触发活跃钩子后的等待：服务端发放有延迟，参考实现用 1500ms 复核。 */
    private val PROMOTION_SETTLE_MS = 1500L

    /** 带签名的 POST（userapi 走小写 authorization）。 */
    private fun signedPost(url: String, credential: AutoClawCredential, body: String): Pair<Int, String> {
        val ts = System.currentTimeMillis() / 1000
        val token = if (credential.accessToken.startsWith("Bearer ")) {
            credential.accessToken
        } else {
            "Bearer ${credential.accessToken}"
        }
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Version", AutoClawConstants.CLIENT_VERSION)
                setRequestProperty("X-Tm", "linux")
                setRequestProperty("X-Product", "autoclaw")
                setRequestProperty("X-Client-Type", "pc")
                setRequestProperty("X-Channel", "official")
                setRequestProperty("X-Auth-Appid", AutoClawConstants.APP_ID)
                setRequestProperty("X-Auth-TimeStamp", ts.toString())
                setRequestProperty("X-Auth-Sign", AutoClawConstants.sign(ts))
                setRequestProperty("X-Trace-Id", java.util.UUID.randomUUID().toString())
                setRequestProperty("X-Lang", "zh-CN")
                // ★ 小写 authorization：大写会被拒（参考实现实测）
                setRequestProperty("authorization", token)
                val bytes = body.toByteArray(Charsets.UTF_8)
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val text = runCatching {
                (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            }.getOrDefault("")
            runCatching { conn.disconnect() }
            status to text
        } catch (e: Exception) {
            hooks.onLog("AutoClaw 签到异常 ${e.javaClass.simpleName}：${e.message}")
            0 to ""
        }
    }

    /** 带签名的 GET（userapi 走小写 authorization），用于读任务列表。 */
    private fun signedGet(url: String, credential: AutoClawCredential): Pair<Int, String> {
        val ts = System.currentTimeMillis() / 1000
        val token = if (credential.accessToken.startsWith("Bearer ")) {
            credential.accessToken
        } else {
            "Bearer ${credential.accessToken}"
        }
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Version", AutoClawConstants.CLIENT_VERSION)
                setRequestProperty("X-Tm", "linux")
                setRequestProperty("X-Product", "autoclaw")
                setRequestProperty("X-Client-Type", "pc")
                setRequestProperty("X-Channel", "official")
                setRequestProperty("X-Auth-Appid", AutoClawConstants.APP_ID)
                setRequestProperty("X-Auth-TimeStamp", ts.toString())
                setRequestProperty("X-Auth-Sign", AutoClawConstants.sign(ts))
                setRequestProperty("X-Trace-Id", java.util.UUID.randomUUID().toString())
                setRequestProperty("X-Lang", "zh-CN")
                setRequestProperty("authorization", token)
            }
            val status = conn.responseCode
            val text = runCatching {
                (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            }.getOrDefault("")
            runCatching { conn.disconnect() }
            status to text
        } catch (e: Exception) {
            hooks.onLog("AutoClaw GET 异常 ${e.javaClass.simpleName}：${e.message}")
            0 to ""
        }
    }

    /**
     * 积分。接口来自开源项目 `ZFXing-lite/autoclaw2api`：
     * `GET /agent-assetmgr/api/v2/wallets?biz_app_id=autoclaw` → `total_balance`。
     *
     * 之前猜的 `/autoclaw-proxy/proxy/autoclaw-user-info` 不存在，所以额度永远刷不出来。
     */
    /** 活跃钩子的日锁：key = uid，value = 已触发的北京日期。 */
    private val activeTriggeredOn = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 北京日期键（与上游 grant_date 同口径）。 */
    private fun todayKey(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        return fmt.format(java.util.Date())
    }

    /**
     * 每天自动打一次「登录活跃」，让服务端发放 campaign 奖。
     *
     * 覆盖三类「活跃即发」的活动（判据来自 ProvidersPlugin/Agent2Providers 的实测记录）：
     * - `autoclaw_user_active_reward`：周/日福利（如每周限时激励 5000）
     * - `autoclaw_user_active_bonus`：一次性大礼包（如 20000）
     * - `autoclaw_user_signup_day_N`：新手 7 天登录礼（新号每天都有）
     *
     * 接口幂等，重复调用不重复入账；用日锁避免同一账号一天打多次。
     * 从额度刷新（creditInfo）和签到两条链路都会调用，谁先跑谁触发。
     */
    private fun maybeTriggerLoginActive(credential: AutoClawCredential, uid: String) {
        val today = todayKey()
        if (activeTriggeredOn[uid] == today) return
        // 先占位再触发：并发调用时只打一次
        activeTriggeredOn[uid] = today
        triggerLoginActive(credential)
    }

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val credential = AutoClawCredential.parse(account.secret) ?: return null
        // 每次刷新额度先补一次活跃钩子：这是最稳的每日触发点（App 启动/手动刷新都会走）
        maybeTriggerLoginActive(credential, account.uid)
        val points = sandbox.points(credential) ?: return null
        return CreditInfo(balance = points.toLong(), known = true, detail = "AutoClaw 积分")
    }

    // ------------------------------------------------------------------ 错误分类

    override fun classify(status: Int, body: String): UpstreamError {
        val kind = when {
            status == 401 -> ErrorKind.SESSION_DEAD
            status == 429 -> ErrorKind.SOFT_RATE
            status == 404 -> ErrorKind.NOT_FOUND
            status == 0 -> ErrorKind.NETWORK
            status in 400..499 -> ErrorKind.CLIENT
            else -> ErrorKind.SERVER
        }
        return UpstreamError(kind = kind, message = extractMessage(body).ifEmpty { "上游 HTTP $status" })
    }

    private fun mapError(status: Int, raw: String): String {
        val msg = extractMessage(raw)
        return when {
            msg.isNotEmpty() -> msg
            status == 401 -> "登录已过期，请重新登录"
            status == 429 -> "上游限流，请稍后重试"
            else -> "上游 HTTP $status"
        }
    }

    /** 上游错误体形如 `{"code":x,"msg":"..."}`，优先取 msg。 */
    private fun extractMessage(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        runCatching {
            val o = JsonParser.parseString(trimmed).asJsonObject
            val m = o.str("msg").ifEmpty { o.str("message") }
            if (m.isNotEmpty()) return m
        }
        return trimmed.take(200)
    }

    // ------------------------------------------------------------------ HTTP 工具

    private fun httpPost(
        url: String,
        body: String,
        headers: Map<String, String> = AutoClawConstants.userApiHeaders(),
    ): Pair<Int, String> = httpRequest(url, "POST", body, headers)

    private fun httpGet(url: String, headers: Map<String, String>): Pair<Int, String> =
        httpRequest(url, "GET", null, headers)

    private fun httpRequest(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): Pair<Int, String> = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { it.write(bytes) }
            }
        }
        val status = conn.responseCode
        val text = runCatching {
            (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        }.getOrDefault("")
        runCatching { conn.disconnect() }
        status to text
    } catch (e: Exception) {
        hooks.onLog("AutoClaw 请求异常 ${e.javaClass.simpleName}：${e.message}")
        0 to ""
    }

    /** 设备标识：固定派生即可，服务端只用于风控关联，不做强校验。 */
    private fun deviceId(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest("autoclaw-device".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    // ------------------------------------------------------------------ Json 扩展

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.arr(key: String): List<com.google.gson.JsonElement>? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList()

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.num(key: String): Double? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let {
            runCatching { it.asDouble }.getOrNull()
        }
}
