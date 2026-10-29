package dev.aigw.core.provider.lingxi

import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * 把灵犀的 SSE 事件流转换成 OpenAI `chat.completion.chunk` 字节流。
 *
 * 上游事件形态（每行 `data: {json}`）：
 * ```
 * {"type":"reasoning","data":{"text":"..."}}   深度思考增量
 * {"type":"text","data":{"text":"..."}}        正文增量
 * {"type":"text_end"} / {"type":"end"}         结束
 * ```
 * 与 OpenAI 的差异只有「事件形状」，语义是 1:1 的，所以这里做纯转发式改写。
 */
internal class LingxiSseTransformer(
    private val source: InputStream,
    private val model: String,
    private val onClose: () -> Unit,
) {

    private val pipeOut = PipedOutputStream()
    private val pipeIn = PipedInputStream(pipeOut, 64 * 1024)

    /**
     * 是否已关闭。
     *
     * PipedOutputStream 的写端在无人读取时会**永久阻塞**（缓冲 64KB 写满之后）：
     * 网关那边如果不再读（客户端断开、超时），pump 线程就会卡死并泄漏。
     * 所以关闭时必须显式把写端关掉，让泵线程抛 IOException 退出。
     */
    @Volatile
    private var closed = false

    /** 由调用方在 close 时调用：立刻停止泵线程并释放上游连接。 */
    fun close() {
        closed = true
        runCatching { pipeOut.close() }
        runCatching { source.close() }
        onClose()
    }

    fun start(): InputStream {
        val worker = Thread({ pump() }, "lingxi-sse")
        worker.isDaemon = true
        worker.start()
        return pipeIn
    }

    private fun pump() {
        try {
            BufferedReader(InputStreamReader(source, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    handleLine(line)
                    line = reader.readLine()
                }
            }
            emit("[DONE]")
        } catch (_: Throwable) {
            // 上游断开：尽力补一个结束标记，让客户端不会一直挂着
            runCatching { emit("[DONE]") }
        } finally {
            runCatching { pipeOut.close() }
            runCatching { source.close() }
            onClose()
        }
    }

    private fun handleLine(line: String) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return
        val raw = trimmed.removePrefix("data:").trim()
        if (raw.isEmpty()) return
        if (raw == "[DONE]") {
            emit("[DONE]")
            return
        }

        val obj = runCatching {
            com.google.gson.JsonParser.parseString(raw).asJsonObject
        }.getOrNull() ?: return

        val type = runCatching { obj.get("type")?.asString.orEmpty() }.getOrDefault("")
        val data = obj.get("data")

        // 排障：把上游的未知事件全部留档。模型身份（如是否真的 deepseek-v4.1-flash）
        // 很可能就藏在被忽略的元信息事件里。确认后删除。
        if (type !in setOf("reasoning", "text", "text_end", "end")) {
        }

        when (type) {
            "reasoning" -> emitChunk("reasoning_content", textOf(data))
            "text" -> emitChunk("content", textOf(data))
            "text_end", "end" -> emitFinish()
            // 其他事件（心跳、元信息）忽略即可
        }
    }

    /** data 可能是对象 `{text:...}`，也可能是裸字符串。 */
    private fun textOf(data: com.google.gson.JsonElement?): String {
        if (data == null || data.isJsonNull) return ""
        if (data.isJsonPrimitive) return runCatching { data.asString }.getOrDefault("")
        return runCatching {
            val o = data.asJsonObject
            o.get("text")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        }.getOrDefault("")
    }

    private fun emitChunk(field: String, text: String) {
        if (text.isEmpty()) return
        val delta = com.google.gson.JsonObject().apply { addProperty(field, text) }
        emitDelta(delta, finish = null)
    }

    private fun emitFinish() = emitDelta(com.google.gson.JsonObject(), finish = "stop")

    private fun emitDelta(delta: com.google.gson.JsonObject, finish: String?) {
        val chunk = com.google.gson.JsonObject().apply {
            addProperty("id", "chatcmpl-lingxi")
            addProperty("object", "chat.completion.chunk")
            addProperty("created", System.currentTimeMillis() / 1000)
            addProperty("model", model)
            add(
                "choices",
                com.google.gson.JsonArray().apply {
                    add(
                        com.google.gson.JsonObject().apply {
                            addProperty("index", 0)
                            add("delta", delta)
                            if (finish == null) {
                                add("finish_reason", com.google.gson.JsonNull.INSTANCE)
                            } else {
                                addProperty("finish_reason", finish)
                            }
                        },
                    )
                },
            )
        }
        emit(chunk.toString())
    }

    private fun emit(payload: String) {
        write("data: $payload\n\n")
    }

    private fun write(s: String) {
        if (closed) return
        val out: OutputStream = pipeOut
        out.write(s.toByteArray(Charsets.UTF_8))
        out.flush()
    }
}
