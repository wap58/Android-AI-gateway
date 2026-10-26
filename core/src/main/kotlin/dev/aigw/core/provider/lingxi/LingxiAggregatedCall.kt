package dev.aigw.core.provider.lingxi

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ChatCall
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * 非流式调用：把已经转成 OpenAI SSE 的字节流在本地聚合成一个完整的
 * `chat.completion` JSON。
 *
 * 注意顺序：转出来的流里 reasoning 块和 content 块是分开的事件，
 * 这里把它们分别拼接，最终响应同时给出 `content` 与 `reasoning_content`。
 */
internal class LingxiAggregatedCall(
    override val status: Int,
    private val streamSource: InputStream,
    private val onClose: () -> Unit,
) : ChatCall {

    override val errorBody: String get() = ""

    override val stream: InputStream? get() = null

    override val aggregated: String by lazy { aggregate() }

    private fun aggregate(): String {
        val content = StringBuilder()
        val reasoning = StringBuilder()

        try {
            BufferedReader(InputStreamReader(streamSource, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    val t = line.trim()
                    if (t.startsWith("data:")) {
                        val raw = t.removePrefix("data:").trim()
                        if (raw.isNotEmpty() && raw != "[DONE]") {
                            runCatching {
                                val o = JsonParser.parseString(raw).asJsonObject
                                val choices = o.getAsJsonArray("choices") ?: return@runCatching
                                val delta = choices.firstOrNull()
                                    ?.asJsonObject?.getAsJsonObject("delta")
                                    ?: return@runCatching
                                delta.get("content")?.takeIf { !it.isJsonNull }?.asString
                                    ?.let { content.append(it) }
                                delta.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
                                    ?.let { reasoning.append(it) }
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        } catch (_: Throwable) {
            // 上游中途断开：保留已收内容
        } finally {
            runCatching { streamSource.close() }
            onClose()
        }

        val message = JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", content.toString())
            if (reasoning.isNotEmpty()) {
                addProperty("reasoning_content", reasoning.toString())
            }
        }

        val root = JsonObject().apply {
            addProperty("id", "chatcmpl-lingxi")
            addProperty("object", "chat.completion")
            addProperty("created", System.currentTimeMillis() / 1000)
            addProperty("model", "")
            add(
                "choices",
                com.google.gson.JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("index", 0)
                            add("message", message)
                            addProperty("finish_reason", "stop")
                        },
                    )
                },
            )
            add(
                "usage",
                JsonObject().apply {
                    addProperty("prompt_tokens", 0)
                    addProperty("completion_tokens", 0)
                    addProperty("total_tokens", 0)
                },
            )
        }
        return root.toString()
    }

    override fun close() {
        runCatching { streamSource.close() }
        onClose()
    }
}
