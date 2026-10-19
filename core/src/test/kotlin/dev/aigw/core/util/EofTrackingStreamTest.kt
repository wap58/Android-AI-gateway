package dev.aigw.core.util

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EofTrackingStream] 的 EOF 语义测试。
 *
 * 这个标志决定「流结束后连接能不能回池复用」：
 * 读到 EOF → 上游已把 body 发完，连接交回连接池（省掉下次 TCP+TLS 握手）；
 * 没到 EOF 就结束 → 提前放弃，必须 disconnect，否则半截 body 会污染连接池。
 *
 * 注意：不要试图用「两次请求只建立一条 TCP 连接」来验证复用 ——
 * 在 JVM 的 HttpURLConnection 上，即使调 `disconnect()` 连接也照样被复用，
 * 这种断言无法区分新旧实现，属于假证据。真实差异只在 Android（OkHttp 后端）上出现。
 */
class EofTrackingStreamTest {

    @Test
    fun `读满置位 eof`() {
        val stream = EofTrackingStream(ByteArrayInputStream("abc".toByteArray()))
        val buf = ByteArray(16)
        var total = 0
        while (true) {
            val n = stream.read(buf, 0, buf.size)
            if (n < 0) break
            total += n
        }
        assertEquals(3, total)
        assertTrue(stream.eofReached, "读到 -1 之后必须标记 EOF")
    }

    @Test
    fun `单字节读也置位 eof`() {
        val stream = EofTrackingStream(ByteArrayInputStream(byteArrayOf(1, 2)))
        assertEquals(1, stream.read())
        assertEquals(2, stream.read())
        assertEquals(-1, stream.read())
        assertTrue(stream.eofReached)
    }

    @Test
    fun `没读到 eof 时不置位`() {
        val stream = EofTrackingStream(ByteArrayInputStream("abcdef".toByteArray()))
        val buf = ByteArray(3)
        assertEquals(3, stream.read(buf, 0, 3))
        assertFalse(stream.eofReached, "只读了部分内容时不能标记 EOF（否则会对半截连接回池复用）")
    }
}
