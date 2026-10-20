package dev.aigw.core.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 上游 HTTP 的公共底座：只用到 Android 上同样存在的 [HttpURLConnection]，不引入任何 Android 依赖。
 *
 * 各家上游的差异（请求头、超时、编码方式）仍由调用方决定，这里只统一三件与协议无关的事：
 * 关闭自动重定向、显式要求不压缩、以及限长读取响应体。**不设任何默认请求头**——
 * User-Agent / Authorization / Content-Type 等都必须在调用处逐请求注入。
 */
object HttpSupport {

    /**
     * 响应体读取上限，防止上游异常时撑爆内存。
     *
     * 1MB 对模型目录偏紧：带内嵌图标、或供应商多时清单能到几百 KB，
     * 再叠加压缩前的原始长度就容易被截断（表现为模型列表莫名变少）。
     * 4MB 仍远小于内存风险阈值。
     */
    const val MAX_BODY_BYTES = 4 shl 20

    /**
     * 建立连接并按需写入 body。
     *
     * [body] 非空时开启 doOutput 并写入（Content-Type 需由调用方放进 [headers]）；
     * 不自动设置任何业务头，保证与各家原有请求逐字节等价。
     */
    fun open(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        allowCompression: Boolean = false,
    ): HttpURLConnection = configure(url, method, connectTimeoutMs, readTimeoutMs, allowCompression) { conn ->
        for ((key, value) in headers) conn.setRequestProperty(key, value)
    }.apply { writeBody(this, body) }

    /** 请求头需要按条件命令式设置时使用（如 Trae 的多套指纹头）。 */
    fun openWithHeaders(
        url: String,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        body: String?,
        applyHeaders: (HttpURLConnection) -> Unit,
    ): HttpURLConnection = configure(url, method, connectTimeoutMs, readTimeoutMs, applyHeaders = applyHeaders)
        .apply { writeBody(this, body) }

    /** 读取响应体（非 2xx 时自动落到 errorStream）。不关闭连接，由调用方决定生命周期。 */
    fun readBody(conn: HttpURLConnection, limit: Int = MAX_BODY_BYTES): String {
        val stream = try {
            conn.inputStream
        } catch (_: Exception) {
            conn.errorStream ?: return ""
        }
        return stream.use { readLimited(it, limit) }
    }

    /** 限长读取输入流。[input] 为 null 时返回空串。 */
    fun readLimited(input: InputStream?, limit: Int = MAX_BODY_BYTES): String {
        if (input == null) return ""
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) {
                out.write(buffer, 0, limit - out.size())
                break
            }
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    /**
     * 释放一次上游调用占用的连接。
     *
     * 之所以不再无条件用 `disconnect()`：Android 的 `HttpURLConnection`（OkHttp 后端）里，
     * `disconnect()` 会**直接把 socket 关掉并踢出连接池**，下一个请求必须重新做
     * TCP 握手 + TLS 握手（国内 1~2 RTT，海外 3~4 RTT），全部计入首字延迟。
     * 而 body 完整读完（EOF）后连接本来就已经回池可复用。
     *
     * 规则：body 读完 → 什么都不做，交给连接池；没读完（提前放弃/出错）→ 才 disconnect，
     * 避免半截流污染池子。
     */
    fun release(conn: HttpURLConnection, bodyFullyConsumed: Boolean) {
        if (bodyFullyConsumed) return
        runCatching { conn.disconnect() }
    }

    /**
     * 给流式响应体套一层「是否读到 EOF」的追踪，供 [streamingCloser] 判断能否复用连接。
     */
    fun tracked(stream: InputStream): EofTrackingStream = EofTrackingStream(stream)

    /**
     * 构造流式调用的收尾动作：[stream] 读到 EOF 时只关流（连接回池复用），
     * 否则 disconnect。
     */
    fun streamingCloser(conn: HttpURLConnection, stream: EofTrackingStream): () -> Unit = {
        runCatching { stream.close() }
        release(conn, stream.eofReached)
    }

    /** 读取响应体，并报告是否读到 EOF（完整消费 → 连接可回池复用）。非 2xx 自动落 errorStream。 */
    fun readResponseTracked(conn: HttpURLConnection, limit: Int = MAX_BODY_BYTES): Pair<String, Boolean> {
        val stream = try {
            conn.inputStream
        } catch (_: Exception) {
            conn.errorStream ?: return "" to false
        }
        return readLimitedTracked(stream, limit)
    }

    /** 限长读取，并报告 body 是否被完整消费（未因超限提前截断）。 */
    fun readLimitedTracked(input: InputStream?, limit: Int = MAX_BODY_BYTES): Pair<String, Boolean> {
        if (input == null) return "" to false
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toString("UTF-8") to true
            if (out.size() + read > limit) {
                out.write(buffer, 0, limit - out.size())
                return out.toString("UTF-8") to false
            }
            out.write(buffer, 0, read)
        }
    }

    private fun configure(
        url: String,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        allowCompression: Boolean = false,
        applyHeaders: (HttpURLConnection) -> Unit,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        // 上游若对 SSE/JSON 做重定向或 gzip，流式解析会错乱，显式关闭
        instanceFollowRedirects = false
        // 流式路径固定不压缩（逐字节解析 SSE）；JSON 接口允许压缩，交平台自己带上
        // gzip 并透明解压（Android 的 HttpURLConnection 会代劳），大目录响应能小几倍
        if (!allowCompression) setRequestProperty("Accept-Encoding", "identity")
        applyHeaders(this)
    }

    private fun writeBody(conn: HttpURLConnection, body: String?) {
        if (body == null) return
        conn.doOutput = true
        val bytes = body.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
    }
}

/**
 * 只追踪「是否读到过 EOF」的透传流。
 *
 * 流式响应结束后据此决定要不要 `disconnect()`：读到 EOF 说明上游已把 body 发完，
 * 连接可以回池复用（省掉下次的 TCP+TLS 握手）；没到 EOF 就结束说明是提前放弃，
 * 必须断开，否则半截 body 会污染连接池。
 */
class EofTrackingStream(private val delegate: InputStream) : InputStream() {

    @Volatile
    var eofReached: Boolean = false
        private set

    override fun read(): Int {
        val b = delegate.read()
        if (b < 0) eofReached = true
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n < 0) eofReached = true
        return n
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}
