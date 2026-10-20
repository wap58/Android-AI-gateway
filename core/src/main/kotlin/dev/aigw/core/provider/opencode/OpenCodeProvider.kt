package dev.aigw.core.provider.opencode

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.NoAccountSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.HttpSupport

/**
 * OpenCode 免费通道。
 *
 * ## 不需要账号
 *
 * 上游的凭据是一个公开哨兵值 `Bearer public`，不需要注册、不需要 token。
 * 为了让现有账号池与调度逻辑无需特判，注册时会给它自动建一个占位账号
 * （见 [PLACEHOLDER_UID]），界面上显示为「公共免费通道」。
 *
 * ## 免费判定
 *
 * 模型 id 含 `-free` 后缀，或等于 `big-pickle` / `union-alpha`。
 * 这里**不过滤**非免费模型——用户可能想看全量清单，
 * 选到付费模型时上游会返回明确的鉴权错误，比直接隐藏更好排查。
 */
class OpenCodeProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider, NoAccountSupport {

    override val id: String = ID
    override val displayName: String = "OpenCode 免费"
    override val authKind: AuthKind = AuthKind.NONE

    /** 网关侧给它的上游域名（代理开关按后缀匹配）。 */
    override fun hosts(): List<String> = listOf("opencode.ai")

    /**
     * 合成账号：不落账号池，由 GatewayEngine.pickAccount 现场提供。
     *
     * 用固定 uid，这样并发闸门、熔断、指标统计都能稳定按这个 key 聚合，
     * 不会每次请求都变成新账号。
     */
    override fun syntheticAccount(): ProviderAccount = ProviderAccount(
        providerId = ID,
        uid = PLACEHOLDER_UID,
        nickname = "公共免费通道",
        secret = "{}",
    )

    private val catalog = OpenCodeModelCatalog(nowMillis = nowMillis)

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val models = catalog.models()
        return ProviderModelCatalogView(
            models = models.map { m ->
                ProviderModel(
                    id = m.id,
                    name = m.id,
                    extra = buildMap {
                        if (isFreeModel(m.id)) put("free", "1")
                        put("surface", m.surface)
                    },
                )
            },
            fromFallback = catalog.fromFallback,
            error = catalog.lastError,
        )
    }

    override fun invalidateModelCache() = catalog.invalidate()

    /** 模型名保持原样：上游的 id 就是完整名（如 `deepseek-v4.1-flash-free`）。 */
    override fun resolveModel(requested: String): String = requested

    /** 免费层只有对话用途，没有内部条目。 */
    override fun isInternalModel(id: String): Boolean = false

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val streaming = isStreamingBody(openAiBody)
        val requested = requestedModelOf(openAiBody)

        // 关键一步：注入四个内置工具，否则上游一律 403
        val prepared = OpenCodeGate.ensure(openAiBody)
        // 强制 stream=true 之后，网关侧仍需知道调用方要不要流式
        val sessionId = OpenCodeSession.newSessionId(nowMillis())

        val conn = try {
            HttpSupport.open(
                url = CHAT_URL,
                method = "POST",
                headers = OpenCodeSession.headers(sessionId, streaming = true),
                body = prepared,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                allowCompression = false,
            )
        } catch (e: Exception) {
            return FailedChatCall(0, e.message ?: "连接失败")
        }

        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, e.message ?: "连接失败")
        }

        if (status !in 200..299) {
            val (errorBody, fullyRead) = runCatching { HttpSupport.readLimitedTracked(conn.errorStream) }
                .getOrDefault("" to false)
            HttpSupport.release(conn, fullyRead)
            return FailedChatCall(status, errorBody)
        }

        val input = conn.inputStream
        // 上游恒为 SSE：非流式请求也要把它聚合回一个完整 JSON
        return if (streaming) {
            StreamingChatCall(status, input) { conn.disconnect() }
        } else {
            OpenCodeAggregatingCall(status, input, requested) { conn.disconnect() }
        }
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = runCatching {
            JsonParser.parseString(body).asJsonObject
                .getAsJsonObject("error")
                ?.get("message")?.asString
        }.getOrNull().orEmpty()

        return when {
            // 免费层用尽：429 或上游自造的 rate_limit 错误体
            status == 429 || body.contains("rate_limit_exceeded") ->
                UpstreamError(ErrorKind.SOFT_RATE, message.ifEmpty { "免费额度已用尽" })
            status == 401 || status == 403 ->
                UpstreamError(ErrorKind.SESSION_DEAD, message.ifEmpty { "免费通道被拒绝" })
            status in 500..599 ->
                UpstreamError(ErrorKind.SERVER, message.ifEmpty { "上游 $status" })
            else -> UpstreamError(ErrorKind.CLIENT, message.ifEmpty { "HTTP $status" })
        }
    }

    companion object {
        const val ID = "opencode"

        /** 占位账号 uid：不需要真实凭据，只为了让账号池有一个可调度对象。 */
        const val PLACEHOLDER_UID = "public"

        private const val CHAT_URL = "https://opencode.ai/inference/v1/chat/completions"
        private const val CONNECT_TIMEOUT_MS = 20_000

        /** 思考型模型首字可能很慢，读超时给足；但也不能无限等。 */
        private const val READ_TIMEOUT_MS = 300_000

        /**
         * 是否免费模型。
         *
         * 规则来自上游客户端：id 含 `-free`，或等于两个特判名。
         * 这两个特例在目录里没有 `-free` 后缀但确实是免费的。
         */
        fun isFreeModel(id: String): Boolean {
            val lower = id.lowercase()
            if (lower.contains("-free")) return true
            return lower == "big-pickle" || lower == "union-alpha"
        }
    }
}

/** 把上游的 SSE 聚合成一个普通 completion（非流式请求用）。 */
private class OpenCodeAggregatingCall(
    override val status: Int,
    private val input: java.io.InputStream,
    private val model: String,
    private val onClose: () -> Unit,
) : ChatCall {

    private var closed = false

    override val stream: java.io.InputStream? get() = null

    override val errorBody: String get() = ""

    /** 延迟到首次读取时聚合，避免调用方不读就泄漏。 */
    override val aggregated: String by lazy {
        buildString {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var usage: JsonObject? = null
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val chunk = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
                        ?: continue
                    chunk.getAsJsonObject("usage")?.let { usage = it }
                    chunk.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                        ?.getAsJsonObject("delta")?.let { delta ->
                            delta.get("content")?.takeIf { !it.isJsonNull }?.asString
                                ?.let { content.append(it) }
                            delta.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
                                ?.let { reasoning.append(it) }
                        }
                }
            }
            append("{\"id\":\"opencode-agg\",\"object\":\"chat.completion\",\"model\":\"")
            append(model.replace("\"", ""))
            append("\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"")
            append(escapeJson(content.toString()))
            if (reasoning.isNotEmpty()) {
                append("\",\"reasoning_content\":\"")
                append(escapeJson(reasoning.toString()))
            }
            append("\"},\"finish_reason\":\"stop\"}]")
            if (usage != null) {
                append(",\"usage\":").append(usage.toString())
            }
            append("}")
        }
    }

    private fun escapeJson(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { input.close() }
        onClose()
    }
}
