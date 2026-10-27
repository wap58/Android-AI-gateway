package dev.aigw.core.provider.lingxi

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.HttpSupport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WPS 灵犀（Lingxi / 灵犀 Claw）。
 *
 * ## 鉴权
 *
 * 全部登录态就是一个 `wps_sid` cookie，用户在 lingxi.kdocs.cn 的 F12 里复制值粘贴即可。
 * 没有设备指纹、没有请求签名、没有设备授权流程 —— 比 Qoder 简单得多。
 *
 * ## 对话
 *
 * 两段式：先 `POST /api/aigc/v3/assistant/sessions`（body 为 `{}`）拿 `session_id`，
 * 再 `POST .../sessions/{id}/completions` 发问题。会话 id 失效（404/400）时自动重建一次。
 *
 * ## 额度
 *
 * 单位是「智点」，`GET /api/public/v1/credits/balance`。
 */
class LingxiProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider {

    override val id: String = ID
    override val displayName: String = "WPS 灵犀"
    override val authKind: AuthKind = AuthKind.NONE

    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.CREDIT_REFRESH,
        ProviderCapability.CHECKIN,
    )

    override fun hosts(): List<String> = listOf("lingxi.kdocs.cn", "kdocs.cn")

    // ------------------------------------------------------------------ 凭证

    private fun credentialOf(account: ProviderAccount): LingxiCredential? =
        LingxiCredential.parse(account.secret)

    override fun importCredentials(raw: String): ProviderAccount? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // 允许直接粘整段 JSON（含 wps_sid 字段），也允许裸 cookie
        val cookieInJson = runCatching {
            JsonParser.parseString(trimmed).asJsonObject.get("wps_sid")?.asString
        }.getOrNull()

        val source = cookieInJson?.let { "wps_sid=$it" } ?: trimmed
        val cred = LingxiCredential.parse(source) ?: return null

        // ★ uid 用 WPS 账号的 uid cookie（稳定），不能用 sid 前缀 ——
        //   sid 每次登录都变，那样重复登录会不断新增账号。取不到才退回昵称。
        return ProviderAccount(
            providerId = ID,
            uid = cred.accountId.ifEmpty { cred.nickname },
            nickname = cred.nickname,
            secret = cred.cookie,
        )
    }

    // ------------------------------------------------------------------ 模型

    /**
     * 模型目录缓存。
     *
     * 按 **账号 uid** 分开缓存：不同 WPS 账号的可选模型可能不同（套餐/灰度差异），
     * 全局一份会让 A 账号的清单冒充 B 账号的。
     */
    private class CatalogCache(
        val models: List<ProviderModel>,
        val at: Long,
    )

    private val catalogs = java.util.concurrent.ConcurrentHashMap<String, CatalogCache>()

    override fun invalidateModelCache() {
        catalogs.clear()
    }

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        if (account == null) {
            return ProviderModelCatalogView(
                models = emptyList(),
                fromFallback = false,
                error = "还没有添加 WPS 灵犀账号",
            )
        }

        val cred = credentialOf(account) ?: return ProviderModelCatalogView(
            models = emptyList(),
            fromFallback = false,
            error = "凭证无法解析，请重新登录",
        )

        val key = account.uid
        val cached = catalogs[key]
        if (cached != null && nowMillis() - cached.at < MODEL_TTL_MS) {
            return ProviderModelCatalogView(cached.models, fromFallback = false, error = "")
        }

        // ★ 不做静态兜底：拉不到就如实报错。
        //   上游模型会随套餐和灰度变化，写死的清单一到变更就变成"能选中但用不了"的假象。
        return try {
            val models = fetchModels(cred)
            if (models.isEmpty()) {
                // 保留上一次成功的结果，但明确告知这次没刷新成功
                val stale = cached?.models
                ProviderModelCatalogView(
                    models = stale ?: emptyList(),
                    fromFallback = stale != null,
                    error = if (stale != null) "刷新失败，显示的是上次结果" else "上游没有返回任何模型",
                )
            } else {
                catalogs[key] = CatalogCache(models, nowMillis())
                ProviderModelCatalogView(models, fromFallback = false, error = "")
            }
        } catch (e: Exception) {
            val stale = cached?.models
            ProviderModelCatalogView(
                models = stale ?: emptyList(),
                fromFallback = stale != null,
                error = e.message?.take(120) ?: "拉取模型失败",
            )
        }
    }

    /**
     * 拉取模型与模式。
     *
     * 上游把「模式」和「模型」分开返回，但对调用方来说两者都是可选的 model 值：
     * - `plans`：plan_pro / plan_max —— **场景模式，不进列表**（传上去无法正常对话）
     * - `models`：deepseek-* / qwen3.8_* / GLM-* 等，这些才是可选模型
     */
    private fun fetchModels(cred: LingxiCredential): List<ProviderModel> {
        var lastError: Exception? = null
        cred.cookieVariants().forEach { cookie ->
            try {
                return fetchModelsWith(cookie)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("没有可用的凭证")
    }

    private fun fetchModelsWith(cookie: String): List<ProviderModel> {
        val conn = HttpSupport.open(
            url = LingxiConstants.PLANS_URL,
            method = "GET",
            headers = baseHeaders(cookie),
            body = null,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )

        val status = conn.responseCode
        if (status !in 200..299) {
            val (body, fullyRead) = runCatching { HttpSupport.readLimitedTracked(conn.errorStream) }
                .getOrDefault("" to false)
            HttpSupport.release(conn, fullyRead)
            val hint = if (status == 401 || status == 403) {
                "登录态已失效，请重新登录"
            } else {
                "上游 HTTP $status"
            }
            throw IllegalStateException("$hint ${body.take(80)}")
        }

        val (text, fullyRead) = HttpSupport.readLimitedTracked(conn.inputStream, 4 * 1024 * 1024)
        HttpSupport.release(conn, fullyRead)

        val root = JsonParser.parseString(text).asJsonObject
        if (root.get("result")?.asString == "UserUnauthorized") {
            throw IllegalStateException("登录态已失效，请重新登录")
        }
        val data = root.getAsJsonObject("data") ?: return emptyList()

        val out = mutableListOf<ProviderModel>()
        val seen = mutableSetOf<String>()

        // ★ plans（plan_pro / plan_max）是**场景模式**，不是可选模型：
        //   它们是网页端的"任务类型"开关，直接当 model 传上去无法正常对话，
        //   所以不进模型列表，只暴露 data.models 里的真实模型。

        data.getAsJsonArray("models")?.forEach { el ->
            val o = runCatching { el.asJsonObject }.getOrNull() ?: return@forEach
            val key = o.str("key")
            if (key.isEmpty() || !seen.add(key)) return@forEach
            out += ProviderModel(
                id = key,
                name = o.str("name").ifEmpty { key },
                contextWindow = runCatching { o.get("max_context_tokens")?.asLong ?: 0L }.getOrDefault(0L),
                extra = mapOf(
                    "desc" to o.str("desc"),
                    "multiplier" to o.str("multiplier"),
                    "tag" to o.str("tag"),
                    "reasoning" to runCatching { o.get("support_reasoning")?.asBoolean ?: false }
                        .getOrDefault(false).toString(),
                ),
            )
        }

        return out
    }

    override fun resolveModel(requested: String): String {
        val stripped = requested.substringAfterLast('/').removePrefix("lingxi-").trim()
        return stripped.ifEmpty { LingxiConstants.DEFAULT_MODEL }
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val cred = credentialOf(account) ?: return FailedChatCall(401, "凭证无法解析")
        val streaming = LingxiPrompt.isStreaming(openAiBody)
        val model = LingxiPrompt.modelOf(openAiBody)

        val question = runCatching {
            val msgs = JsonParser.parseString(openAiBody).asJsonObject.getAsJsonArray("messages")
            LingxiPrompt.build(msgs)
        }.getOrDefault("你好")

        val payload = LingxiPrompt.payload(question, model)

        // 首次尝试：新建会话 + 发问
        var result = trySend(cred, sessionId = null, payload = payload, streaming = streaming, model = model)

        // 会话失效（404/400）：重建一次
        if (result is Retry) {
            result = trySend(cred, sessionId = null, payload = payload, streaming = streaming, model = model, forceNew = true)
        }

        return when (result) {
            is Ok -> result.call
            is Retry -> result.failure
            is Fatal -> result.failure
        }
    }

    private sealed interface Attempt
    private class Ok(val call: ChatCall) : Attempt
    private class Retry(val failure: ChatCall) : Attempt
    private class Fatal(val failure: ChatCall) : Attempt

    private fun trySend(
        cred: LingxiCredential,
        sessionId: String?,
        payload: String,
        streaming: Boolean,
        model: String,
        forceNew: Boolean = false,
    ): Attempt {
        val session = if (sessionId.isNullOrEmpty() || forceNew) {
            createSession(cred) ?: return Fatal(FailedChatCall(502, "创建会话失败"))
        } else {
            sessionId
        }

        val conn = try {
            HttpSupport.open(
                url = LingxiConstants.completionsUrl(session),
                method = "POST",
                headers = completionsHeaders(cred.chatCookie),
                body = payload,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                allowCompression = false,
            )
        } catch (e: Exception) {
            return Fatal(FailedChatCall(0, e.message ?: "连接失败"))
        }

        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return Fatal(FailedChatCall(0, e.message ?: "连接失败"))
        }

        // 会话可能已过期：交给上层重建一次
        if (status == 404 || status == 400) {
            runCatching { HttpSupport.readLimitedTracked(conn.errorStream) }
            HttpSupport.release(conn, false)
            return Retry(FailedChatCall(status, "会话失效"))
        }

        if (status !in 200..299) {
            val (body, fullyRead) = runCatching { HttpSupport.readLimitedTracked(conn.errorStream) }
                .getOrDefault("" to false)
            HttpSupport.release(conn, fullyRead)
            return Fatal(FailedChatCall(status, body))
        }

        val input = conn.inputStream
        // ★ onClose 必须走 transformer.close()：它同时关掉写端，
        //   否则无人读取时泵线程会永久阻塞（泄漏一个线程 + 一个连接）。
        val transformer = LingxiSseTransformer(input, model) { conn.disconnect() }
        val transformed = transformer.start()

        return if (streaming) {
            Ok(StreamingChatCall(status, transformed) { transformer.close() })
        } else {
            Ok(LingxiAggregatedCall(status, transformed) { conn.disconnect() })
        }
    }

    private fun createSession(cred: LingxiCredential): String? {
        val conn = runCatching {
            HttpSupport.open(
                url = LingxiConstants.SESSIONS_URL,
                method = "POST",
                headers = completionJsonHeaders(cred.chatCookie),
                body = "{}",
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            )
        }.getOrNull() ?: return null

        return try {
            if (conn.responseCode !in 200..299) {
                HttpSupport.release(conn, false)
                return null
            }
            val (text, fullyRead) = HttpSupport.readLimitedTracked(conn.inputStream, 512 * 1024)
            HttpSupport.release(conn, fullyRead)
            JsonParser.parseString(text).asJsonObject
                .getAsJsonObject("data")
                ?.get("session_id")?.asString
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            null
        }
    }

    // ------------------------------------------------------------------ 额度

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val cred = credentialOf(account) ?: return null
        var result: CreditInfo? = null
        cred.cookieVariants().forEach { cookie ->
            if (result != null) return result
            result = creditInfoWith(cookie)
        }
        return result
    }

    private fun creditInfoWith(cookie: String): CreditInfo? {
        val conn = runCatching {
            HttpSupport.open(
                url = LingxiConstants.CREDITS_URL,
                method = "GET",
                headers = baseHeaders(cookie),
                body = null,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            )
        }.getOrNull() ?: return null

        return try {
            if (conn.responseCode !in 200..299) {
                HttpSupport.release(conn, false)
                return null
            }
            val (text, fullyRead) = HttpSupport.readLimitedTracked(conn.inputStream, 512 * 1024)
            HttpSupport.release(conn, fullyRead)

            val data = JsonParser.parseString(text).asJsonObject.getAsJsonObject("data") ?: return null
            val total = runCatching {
                data.get("total_balance")?.asLong ?: data.get("balance")?.asLong ?: 0L
            }.getOrDefault(0L)

            CreditInfo(
                balance = total,
                known = true,
                detail = "智点",
            )
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            null
        }
    }

    override fun creditPacks(account: ProviderAccount): List<QuotaPack> {
        val cred = credentialOf(account) ?: return emptyList()

        val conn = runCatching {
            HttpSupport.open(
                url = LingxiConstants.CREDITS_URL,
                method = "GET",
                headers = baseHeaders(cred.bizCookie),
                body = null,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            )
        }.getOrNull() ?: return emptyList()

        return try {
            if (conn.responseCode !in 200..299) {
                HttpSupport.release(conn, false)
                return emptyList()
            }
            val (text, fullyRead) = HttpSupport.readLimitedTracked(conn.inputStream, 512 * 1024)
            HttpSupport.release(conn, fullyRead)

            val data = JsonParser.parseString(text).asJsonObject.getAsJsonObject("data")
                ?: return emptyList()

            val out = mutableListOf<QuotaPack>()

            // 赠送智点（bonus_credits 或 bonus_summary.items），带过期时间
            val bonuses = data.getAsJsonArray("bonus_credits")
                ?: data.getAsJsonObject("bonus_summary")?.getAsJsonArray("items")

            bonuses?.forEach { el ->
                val o = runCatching { el.asJsonObject }.getOrNull() ?: return@forEach
                val balance = runCatching { o.get("balance")?.asLong ?: 0L }.getOrDefault(0L)
                if (balance <= 0) return@forEach
                val name = o.str("name").ifEmpty { o.str("title").ifEmpty { "赠送智点" } }
                out += QuotaPack(
                    name = name,
                    group = "赠送",
                    limit = balance,
                    remain = balance,
                    expireAt = parseExpire(o.str("expire_time")),
                )
            }

            // 订阅/套餐额度
            val total = runCatching {
                data.get("total_balance")?.asLong ?: data.get("balance")?.asLong ?: 0L
            }.getOrDefault(0L)
            if (out.isEmpty() && total > 0) {
                out += QuotaPack(name = "智点余额", group = "账户", limit = total, remain = total)
            }

            out
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            emptyList()
        }
    }

    private fun parseExpire(raw: String): Long {
        if (raw.isEmpty()) return 0
        // 形如 2026-11-01T00:00:00+08:00 或 2026-11-01 00:00:00
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")
        patterns.forEach { p ->
            runCatching {
                val fmt = SimpleDateFormat(p, Locale.US)
                val d: Date = fmt.parse(raw) ?: return@runCatching null
                return d.time / 1000
            }
        }
        return 0
    }

    // ------------------------------------------------------------------ 签到

    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult {
        if (action != CHECKIN_ACTION) return ProviderActionResult.unsupported(action)
        val cred = credentialOf(account) ?: return ProviderActionResult.failure("凭证无法解析")

        return try {
            // 先读状态：已签过就不重复请求，避免无谓的写操作
            val state = fetchCheckinState(cred)
            if (state?.claimed == true) {
                return ProviderActionResult(
                    ok = true,
                    message = "今日已签到" +
                        if (state.consecutiveDays > 0) "（连续 ${state.consecutiveDays} 天）" else "",
                    amount = 0,
                    streak = state.consecutiveDays,
                    total = state.totalClaimed.toLong(),
                    kind = "already",
                )
            }

            val claimed = claim(cred)
                ?: return ProviderActionResult(
                    ok = false,
                    message = "签到失败，可能登录态已失效",
                    kind = "failed",
                )

            // 再读一次，回填连续天数与累计
            val after = runCatching { fetchCheckinState(cred) }.getOrNull()
            val streak = after?.consecutiveDays ?: 0
            val total = after?.totalClaimed?.toLong() ?: 0L
            ProviderActionResult(
                ok = true,
                message = claimed.message + if (streak > 0) "，连续 $streak 天" else "",
                amount = claimed.amount,
                streak = streak,
                total = total,
                kind = "claimed",
            )
        } catch (e: Exception) {
            ProviderActionResult(ok = false, message = e.message ?: "签到异常", kind = "failed")
        }
    }

    /**
     * 读取签到任务状态。
     *
     * 上游返回结构（实测）：
     * ```
     * {"data":{"tasks":[
     *   {"task_key":"daily_check_in","reward_amount":100,"status":"claimed",
     *    "extra":{"consecutive_days":2,"today_day":2,"today_reward":100,
     *             "check_in_days":[{"day":2,"date":"2026-10-26","status":"claimed"}]}}
     * ]}}
     * ```
     * `status` 为 `claimed` 表示今天已领；`claimable`/`available` 表示可领。
     */
    private class CheckinState(
        val claimed: Boolean,
        val consecutiveDays: Int,
        val todayReward: Int,
        val totalClaimed: Int,
    )

    private fun fetchCheckinState(cred: LingxiCredential): CheckinState? {
        val conn = runCatching {
            HttpSupport.open(
                url = LingxiConstants.TASKS_URL,
                method = "GET",
                headers = baseHeaders(cred.bizCookie),
                body = null,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            )
        }.getOrNull() ?: return null

        return try {
            if (conn.responseCode !in 200..299) {
                HttpSupport.release(conn, false)
                return null
            }
            val (text, fullyRead) = HttpSupport.readLimitedTracked(conn.inputStream, 1024 * 1024)
            HttpSupport.release(conn, fullyRead)

            val data = JsonParser.parseString(text).asJsonObject.getAsJsonObject("data") ?: return null
            val tasks = data.getAsJsonArray("tasks") ?: return null

            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis()))

            var target: JsonObject? = null
            tasks.forEach { el ->
                val o = runCatching { el.asJsonObject }.getOrNull()
                if (o != null && o.str("task_key") == "daily_check_in") target = o
            }
            val task = target ?: return null

            val status = task.str("status").lowercase()
            val extra = task.getAsJsonObject("extra")

            // 今天这一档的状态才是准的：extra.check_in_days 里按 date 匹配 today
            var todayStatus = status
            var consecutive = 0
            var todayReward = 0
            if (extra != null) {
                consecutive = runCatching { extra.get("consecutive_days")?.asInt ?: 0 }.getOrDefault(0)
                todayReward = runCatching { extra.get("today_reward")?.asInt ?: 0 }.getOrDefault(0)
                extra.getAsJsonArray("check_in_days")?.forEach { el ->
                    val d = runCatching { el.asJsonObject }.getOrNull() ?: return@forEach
                    if (d.str("date") == today) {
                        todayStatus = d.str("status").lowercase()
                    }
                }
            }

            val claimed = todayStatus == "claimed" || status == "claimed"
            val total = runCatching { data.get("total_claimed_credits")?.asInt ?: 0 }.getOrDefault(0)

            CheckinState(
                claimed = claimed,
                consecutiveDays = consecutive,
                todayReward = todayReward,
                totalClaimed = total,
            )
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            null
        }
    }

    /** 签到领取的结果。 */
    private class ClaimResult(val amount: Long, val message: String)

    /** 执行签到领取。返回 null 表示请求失败。 */
    private fun claim(cred: LingxiCredential): ClaimResult? {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis()))
        val conn = runCatching {
            HttpSupport.open(
                url = LingxiConstants.claimUrl(today),
                method = "POST",
                // 签到属于业务接口，用 wps.cn 域的会话
                headers = completionJsonHeaders(cred.bizCookie),
                body = "{}",
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            )
        }.getOrNull() ?: return null

        return try {
            val ok = conn.responseCode in 200..299
            val (text, fullyRead) = HttpSupport.readLimitedTracked(
                if (ok) conn.inputStream else conn.errorStream,
                256 * 1024,
            )
            HttpSupport.release(conn, fullyRead)
            if (!ok) return null

            val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            val reward = runCatching {
                root?.getAsJsonObject("data")?.get("reward_amount")?.asLong ?: 0L
            }.getOrDefault(0L)
            ClaimResult(
                amount = reward,
                message = if (reward > 0) "签到成功，获得 $reward 智点" else "签到成功",
            )
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            null
        }
    }

    // ------------------------------------------------------------------ 错误

    override fun classify(status: Int, body: String): UpstreamError {
        val message = runCatching {
            val o = JsonParser.parseString(body).asJsonObject
            o.getAsJsonObject("error")?.get("message")?.asString
                ?: o.get("message")?.asString
                ?: o.get("msg")?.asString
        }.getOrNull().orEmpty()

        return when {
            status == 401 || status == 403 ->
                UpstreamError(ErrorKind.SESSION_DEAD, message.ifEmpty { "登录态失效，请重新粘贴 wps_sid" })
            status == 429 || body.contains("rate_limit") || body.contains("quota") ->
                UpstreamError(ErrorKind.SOFT_RATE, message.ifEmpty { "智点不足或触发限流" })
            status in 500..599 ->
                UpstreamError(ErrorKind.SERVER, message.ifEmpty { "上游 $status" })
            else -> UpstreamError(ErrorKind.CLIENT, message.ifEmpty { "HTTP $status" })
        }
    }

    // ------------------------------------------------------------------ 头部

    private fun baseHeaders(cred: LingxiCredential): Map<String, String> = baseHeaders(cred.cookie)

    private fun baseHeaders(cookie: String): Map<String, String> = mapOf(
        "Cookie" to cookie,
        "User-Agent" to LingxiConstants.USER_AGENT,
        "Referer" to LingxiConstants.REFERER,
        "Accept" to "application/json, text/plain, */*",
    )

    private fun completionJsonHeaders(cookie: String): Map<String, String> =
        baseHeaders(cookie) + mapOf("Content-Type" to "application/json")

    private fun completionsHeaders(cookie: String): Map<String, String> =
        completionJsonHeaders(cookie) + mapOf(
            "X-Cc-Version" to LingxiConstants.X_CC_VERSION,
            "X-Client-Type" to LingxiConstants.X_CLIENT_TYPE,
            "X-Cc-Entrance" to LingxiConstants.X_CC_ENTRANCE,
            "Accept" to "text/event-stream",
        )

    companion object {
        const val ID = "lingxi"

        const val CHECKIN_ACTION = "checkin"

        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 180_000
        private const val MODEL_TTL_MS = 30 * 60 * 1000L
    }
}

/** Gson 取字符串：缺键、null、非字符串都返回空串。 */
private fun JsonObject.str(key: String): String = runCatching {
    get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
}.getOrDefault("")
