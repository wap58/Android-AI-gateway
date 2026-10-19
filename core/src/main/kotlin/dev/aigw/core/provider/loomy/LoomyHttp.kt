package dev.aigw.core.provider.loomy

import dev.aigw.core.util.EofTrackingStream
import dev.aigw.core.util.HttpSupport
import java.io.InputStream
import java.net.HttpURLConnection

/** 上游 HTTP 的小工具：只用到 Android 上同样存在的 `HttpURLConnection`。 */
internal object LoomyHttp {

    const val CONNECT_TIMEOUT_MS = 30_000
    const val READ_TIMEOUT_MS = 120_000

    /** 响应体读取上限，防止上游异常时撑爆内存。 */
    const val MAX_BODY_BYTES = HttpSupport.MAX_BODY_BYTES

    fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): HttpURLConnection =
        HttpSupport.open(url, method, headers, body, connectTimeoutMs, readTimeoutMs)

    /** 读取响应体（非 2xx 时自动落到 errorStream）。 */
    fun readBody(conn: HttpURLConnection, limit: Int = MAX_BODY_BYTES): String =
        HttpSupport.readBody(conn, limit)

    /** 读取响应体，并报告是否读到 EOF（完整消费 → 连接可回池复用）。 */
    fun readBodyTracked(conn: HttpURLConnection, limit: Int = MAX_BODY_BYTES): Pair<String, Boolean> =
        HttpSupport.readResponseTracked(conn, limit)
}

/**
 * 一次普通 JSON 请求的结果。
 *
 * 网络层失败（连不上/超时）也会被包成 [LoomyApiException]（kind = NETWORK），
 * 让上层只需要处理一种异常类型。
 *
 * body 读完后连接**不主动 disconnect**：交给连接池复用，省掉下次的 TCP+TLS 握手。
 */
internal fun httpJson(
    url: String,
    method: String,
    headers: Map<String, String>,
    body: String? = null,
    connectTimeoutMs: Int = LoomyHttp.CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = LoomyHttp.READ_TIMEOUT_MS,
): Pair<Int, String> {
    val conn = try {
        LoomyHttp.open(url, method, headers, body, connectTimeoutMs, readTimeoutMs)
    } catch (e: Exception) {
        throw LoomyApiException(0, LoomyErrorKind.NETWORK, e.message.orEmpty())
    }
    var fullyRead = false
    return try {
        val status = conn.responseCode
        val (text, complete) = LoomyHttp.readBodyTracked(conn)
        fullyRead = complete
        status to text
    } catch (e: Exception) {
        throw LoomyApiException(0, LoomyErrorKind.NETWORK, e.message.orEmpty())
    } finally {
        HttpSupport.release(conn, fullyRead)
    }
}

/**
 * 一次流式请求的连接。
 *
 * `status` 非 2xx 时 `stream` 为 null，错误体在 [errorBody] 里。
 * [contentType] 用来识别「HTTP 200 但其实是业务错误 JSON」的情况：
 * 上游对鉴权失败就是 200 + `{"code":"100002"}`，不是 4xx。
 */
class LoomyStreamCall internal constructor(
    val status: Int,
    val errorBody: String,
    val stream: InputStream?,
    val contentType: String,
    private val connection: HttpURLConnection,
) {
    /** 是否是真正的 SSE 流。 */
    val isEventStream: Boolean get() = contentType.contains("text/event-stream", ignoreCase = true)

    fun close() {
        val tracked = stream as? EofTrackingStream
        runCatching { stream?.close() }
        // 读到 EOF 才复用连接；提前放弃时断开，避免半截 body 污染池子
        HttpSupport.release(connection, tracked?.eofReached == true)
    }
}

internal fun openStreamCall(
    url: String,
    headers: Map<String, String>,
    body: String,
    connectTimeoutMs: Int = LoomyHttp.CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = LoomyHttp.READ_TIMEOUT_MS,
): LoomyStreamCall {
    val conn = try {
        LoomyHttp.open(url, "POST", headers, body, connectTimeoutMs, readTimeoutMs)
    } catch (e: Exception) {
        throw LoomyApiException(0, LoomyErrorKind.NETWORK, e.message.orEmpty())
    }
    val status = try {
        conn.responseCode
    } catch (e: Exception) {
        conn.disconnect()
        throw LoomyApiException(0, LoomyErrorKind.NETWORK, e.message.orEmpty())
    }
    val contentType = conn.contentType.orEmpty()
    if (status !in 200..299) {
        val (errorBody, fullyRead) = runCatching { LoomyHttp.readBodyTracked(conn) }
            .getOrDefault("" to false)
        HttpSupport.release(conn, fullyRead)
        return LoomyStreamCall(status, errorBody, null, contentType, conn)
    }
    return LoomyStreamCall(status, "", HttpSupport.tracked(conn.inputStream), contentType, conn)
}
