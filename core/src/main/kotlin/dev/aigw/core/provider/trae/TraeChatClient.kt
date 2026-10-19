package dev.aigw.core.provider.trae

import dev.aigw.core.util.EofTrackingStream
import dev.aigw.core.util.HttpSupport
import java.io.InputStream

/**
 * 一次对话调用的上游响应：2xx 时 [stream] 非空，否则 [errorBody] 非空。
 *
 * 关闭语义分两种：流读到 EOF 说明上游已发完 body，连接交回连接池复用（省掉下轮的
 * TCP+TLS 握手）；没读到 EOF（提前放弃/解析出错）才 disconnect，避免半截 body 污染池子。
 */
class TraeChatCall(
    val status: Int,
    val stream: InputStream?,
    val errorBody: String,
    /** 持有的连接；[stream] 为 null（错误体/非 SSE 200）时关闭一并断开。 */
    private val conn: java.net.HttpURLConnection? = null,
) : AutoCloseable {
    override fun close() {
        val tracked = stream as? EofTrackingStream
        runCatching { stream?.close() }
        if (conn == null) return
        HttpSupport.release(conn, tracked?.eofReached == true)
    }
}

/** SOLO 对话通道客户端。请求体改写交给 [TraePayload]。 */
class TraeChatClient(
    private val version: TraeVersion,
    private val host: String,
    private val idleTimeoutMs: Int = TraeHttp.STREAM_IDLE_TIMEOUT_MS,
) {

    /**
     * 发起 `llm_utils_chat`。返回的对象里 [TraeChatCall.stream] 是 SOLO 的 SSE 原始字节流，
     * 调用方负责解析与关闭。
     *
     * 上游 200 但没回 event-stream（签名失效时常直接回 JSON 错误体）时，
     * 不能把非 SSE 字节流交给 SSE 解析器——那会被静默忽略成「成功但零输出」。
     */
    fun openStream(account: TraeAccount, openAiBody: String): TraeChatCall {
        val payload = TraePayload.prepare(openAiBody)
        val conn = TraeHttp.post(
            url = host + TraeConstants.EP_CHAT,
            body = payload,
            connectTimeoutMs = TraeHttp.CONNECT_TIMEOUT_MS,
            readTimeoutMs = idleTimeoutMs,
        ) { TraeHeaders.solo(it, account, version, stream = true) }

        val status = conn.responseCode
        if (status >= 400) {
            val raw = TraeHttp.readBody(conn)
            return TraeChatCall(status, null, raw, conn)
        }
        val contentType = conn.contentType.orEmpty()
        if (!contentType.contains("event-stream", ignoreCase = true)) {
            val raw = TraeHttp.readBody(conn)
            return TraeChatCall(
                status,
                null,
                "上游未返回 SSE 流（Content-Type: ${contentType.ifEmpty { "—" }}）：${raw.trim().take(200).ifEmpty { "<空响应>" }}",
                conn,
            )
        }
        return TraeChatCall(status, HttpSupport.tracked(conn.inputStream), "")
    }

    /** 拉取模型目录（`get_detail_param`）。 */
    fun fetchModels(account: TraeAccount): List<TraeModel> {
        val body = TraePayloadModels.requestBody()
        val conn = TraeHttp.post(
            url = host + TraeConstants.EP_MODELS,
            body = body,
            connectTimeoutMs = TraeHttp.CONNECT_TIMEOUT_MS,
            readTimeoutMs = TraeHttp.READ_TIMEOUT_MS,
        ) { TraeHeaders.solo(it, account, version, stream = false) }

        val status = conn.responseCode
        var fullyRead = false
        val raw = try {
            val (text, complete) = HttpSupport.readLimitedTracked(conn.errorStreamOrInput(status))
            fullyRead = complete
            text
        } finally {
            // 目录是非流式请求：读完就交回连接池，异常路径也不能漏
            HttpSupport.release(conn, fullyRead)
        }
        if (status >= 400) {
            throw TraeHttpException(status, TraeErrors.fromStatus(status, raw), raw, TraeErrors.extractCode(raw))
        }
        return TraePayloadModels.parse(raw)
    }

    /** 2xx 读 inputStream，非 2xx 读 errorStream。 */
    private fun java.net.HttpURLConnection.errorStreamOrInput(status: Int): InputStream? =
        if (status in 200..299) runCatching { inputStream }.getOrNull() else runCatching { errorStream }.getOrNull()
}
