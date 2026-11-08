package dev.aigw.core.provider.qoder

import java.io.InputStream

/**
 * 把 Qoder 的 SSE 信封流解包成标准 OpenAI chunk 行。
 *
 * ## 上游不是标准 SSE
 *
 * 上游每一帧长这样（外层是 Qoder 自己的信封，内层才是 OpenAI chunk 的 JSON 字符串）：
 *
 * ```
 * data:{"headers":{...},"body":"{\"choices\":[...]}","statusCodeValue":200}
 * data:{"body":"[DONE]"}
 * event:finish{"...":"计时元数据，忽略"}
 * ```
 *
 * 直接把上游流转发给客户端是不行的：客户端只认 OpenAI 格式，看到外层信封
 * 会当成未知字段忽略掉，表现就是「有响应但一个字都不显示」。
 *
 * ## 抽成独立类的原因
 *
 * 解包要在流式过程中逐行做，且必须能正确跨越 chunk 边界（一帧可能被 TCP
 * 切成好几块）。放在 Provider 里会让 openChat 难以测试；独立类可以对着
 * 内存流单测。
 */
internal class QoderSseUnwrapper(private val upstream: InputStream) {

    /** 上游在信封里报错（HTTP 200 但 statusCodeValue != 200）。 */
    var envelopeError: String = ""
        private set

    /** 上游 statusCodeValue 非 200 时记录的状态码。 */
    var envelopeStatus: Int = 0
        private set

    /**
     * 逐行产出标准 `data: {...}` 帧。
     *
     * 返回的每个元素都是可以直接写给客户端的完整 SSE 段（以 `\n\n` 结尾）。
     */
    fun frames(): Sequence<String> = sequence {
        val reader = upstream.bufferedReader(Charsets.UTF_8)
        var line: String?
        while (true) {
            line = try {
                reader.readLine()
            } catch (_: Exception) {
                null
            } ?: break

            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // event: 行是计时元数据，客户端不需要
            if (trimmed.startsWith("event:")) continue

            val data = when {
                trimmed.startsWith("data:") -> trimmed.removePrefix("data:").trim()
                // 少数实现不带 data: 前缀，直接给 JSON
                trimmed.startsWith("{") -> trimmed
                else -> continue
            }
            if (data.isEmpty()) continue

            val outer = runCatching {
                com.google.gson.JsonParser.parseString(data).asJsonObject
            }.getOrNull() ?: continue

            val status = outer.get("statusCodeValue")?.takeIf { !it.isJsonNull }?.asInt ?: 200
            val body = outer.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty()

            if (status != 200) {
                envelopeStatus = status
                envelopeError = body.ifEmpty { data.take(400) }
                return@sequence
            }
            if (body.isEmpty()) continue
            if (body == "[DONE]") {
                yield("data: [DONE]\n\n")
                return@sequence
            }

            // 内层是 OpenAI chunk 的 JSON 字符串；原样转发（客户端会自己解析）。
            // 只做一处规范化：确认它是合法 JSON，避免把上游半截帧当内容发出去。
            val ok = runCatching { com.google.gson.JsonParser.parseString(body) }.isSuccess
            if (!ok) continue
            yield("data: $body\n\n")
        }
    }
}
