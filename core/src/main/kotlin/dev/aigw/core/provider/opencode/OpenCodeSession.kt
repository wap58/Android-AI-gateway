package dev.aigw.core.provider.opencode

import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * OpenCode 免费通道的会话标识与请求头。
 *
 * ## 为什么需要 sessionId
 *
 * 上游把「同一个会话」的请求归到一起做前缀缓存（响应里的 `cached_tokens`），
 * 同时在缺少会话标识时按「非官方客户端」处理。格式必须与官方 CLI 一致：
 *
 * ```
 * ses_ + hex(48bit 时间戳+计数器, 取反) + 14 位 base62 随机
 * ```
 *
 * ## 版本号
 *
 * `User-Agent: opencode/prod/<version>/cli` 里的 `<version>` 会被上游校验格式。
 * 这里固定用一个合法版本号，不做 npm 探测——探测只为「看起来最新」，
 * 而任何 2.x 版本都能通过校验，多一次网络请求不值得。
 */
internal object OpenCodeSession {

    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /** 官方 CLI 的版本号（UA 里用到）。 */
    const val CLI_VERSION = "2.0.29"

    private val random = SecureRandom()

    /** 同毫秒内的自增计数：官方实现据此保证同一毫秒生成的 id 也不重复。 */
    private val counter = AtomicLong(0)

    @Volatile
    private var lastMillis = 0L

    /**
     * 生成一个 sessionId。
     *
     * 算法来自官方客户端：`x = (nowMs << 12) + counter`，取反后取低 48 位，
     * 再拼 14 位 base62 随机串。取反是为了让高位（时间）在字符串里变化更明显。
     */
    fun newSessionId(nowMillis: Long = System.currentTimeMillis()): String {
        val c = synchronized(this) {
            if (nowMillis == lastMillis) {
                counter.incrementAndGet()
            } else {
                lastMillis = nowMillis
                counter.set(1)
                1L
            }
        }
        val x = ((nowMillis shl 12) + c).inv()
        val prefix = String.format(Locale.US, "%012x", x and 0xFFFFFFFFFFFFL)
        val sb = StringBuilder(18)
        repeat(14) { sb.append(BASE62[random.nextInt(BASE62.length)]) }
        return "ses_$prefix$sb"
    }

    /**
     * 构造请求头。
     *
     * 这些头不是「可选装饰」：上游按它们判定请求来自官方 CLI。
     * 但**真正起决定作用的是 body 里的 tools**（见 [OpenCodeGate]），
     * 头只做辅证。
     */
    fun headers(sessionId: String, streaming: Boolean): Map<String, String> = buildMap {
        put("Authorization", "Bearer public")
        put("User-Agent", "opencode/prod/$CLI_VERSION/cli")
        put("x-opencode-client", "cli")
        put("x-opencode-project", "global")
        put("x-opencode-session", sessionId)
        put("x-opencode-session-id", sessionId)
        put("x-session-affinity", sessionId)
        put("x-session-id", sessionId)
        put("Accept", if (streaming) "text/event-stream" else "application/json")
        // 禁 gzip：否则流式帧会被缓冲，客户端迟迟拿不到首字
        put("Accept-Encoding", "identity")
    }
}
