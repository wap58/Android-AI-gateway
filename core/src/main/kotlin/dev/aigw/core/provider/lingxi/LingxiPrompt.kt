package dev.aigw.core.provider.lingxi

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * OpenAI 请求体 → 灵犀 `completions` 请求体。
 *
 * 上游只吃一个扁平的 `question` 字段，**没有 messages 数组**，
 * 所以多轮上下文只能自己拼成一段带角色的文本。这一点和 Qoder 完全不同
 * （Qoder 保留结构化 messages，灵犀纯文本）。
 */
internal object LingxiPrompt {

    private const val MAX_HISTORY_ITEMS = 200

    /**
     * 把 OpenAI 的 messages 拼成一段对话文本。
     *
     * 规则与参考实现一致：
     * - 只有一条消息：直接取它
     * - 多条：历史逐条 `User: ...` / `Assistant: ...` / `[System: ...]`，
     *   最后一条 user 作为当前提问单独放在末尾
     */
    fun build(messages: JsonArray?): String {
        if (messages == null || messages.isEmpty) return "你好"

        val items = messages.toList()
            .takeLast(MAX_HISTORY_ITEMS)
            .mapNotNull { el ->
                val m = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val role = m.str("role").ifEmpty { "user" }
                val text = contentOf(m)
                if (text.isEmpty()) null else role to text
            }

        if (items.isEmpty()) return "你好"

        // 单轮：直接用
        if (items.size == 1) return items[0].second

        // 多轮：最后一条 user 是当前提问，其余全部作为历史
        val lastUserIndex = items.indexOfLast { it.first == "user" }
        if (lastUserIndex < 0) return items.joinToString("\n\n") { label(it.first, it.second) }

        val current = items[lastUserIndex].second
        val history = items.take(lastUserIndex)
        if (history.isEmpty()) return current

        val sb = StringBuilder()
        history.forEach { (role, text) ->
            sb.append(label(role, text)).append("\n\n")
        }
        sb.append("User: ").append(current)
        return sb.toString()
    }

    private fun label(role: String, text: String): String = when (role) {
        "system" -> "[System: $text]"
        "assistant" -> "Assistant: $text"
        else -> "User: $text"
    }

    /** content 可能是字符串，也可能是 `[{type:text,text:...}]` 数组。 */
    private fun contentOf(m: JsonObject): String {
        val c = m.get("content") ?: return ""
        if (c.isJsonPrimitive) {
            return runCatching { c.asString }.getOrDefault("")
        }
        if (c.isJsonArray) {
            val sb = StringBuilder()
            c.asJsonArray.forEach { part ->
                val p = runCatching { part.asJsonObject }.getOrNull() ?: return@forEach
                if (p.str("type") == "text") sb.append(p.str("text"))
            }
            return sb.toString()
        }
        return ""
    }

    /** 构造上游请求体。 */
    fun payload(question: String, model: String): String {
        val trimmed = if (question.length > LingxiConstants.MAX_PROMPT_CHARS) {
            question.take(LingxiConstants.MAX_PROMPT_CHARS)
        } else {
            question
        }
        val body = JsonObject().apply {
            addProperty("question", trimmed)
            addProperty("model", model)
            add("user_input", JsonObject().apply { addProperty("text", trimmed) })
        }
        return body.toString()
    }

    /** 从 OpenAI 请求体里取模型名并剥掉网关前缀（`wps-lingxi/`、`lingxi-`）。 */
    fun modelOf(openAiBody: String): String {
        val raw = runCatching {
            JsonParser.parseString(openAiBody).asJsonObject.get("model")?.asString.orEmpty()
        }.getOrDefault("")

        val stripped = raw
            .substringAfterLast('/')
            .removePrefix("lingxi-")
            .trim()

        return if (stripped.isEmpty() || stripped == "default") {
            LingxiConstants.DEFAULT_MODEL
        } else {
            stripped
        }
    }

    /** 调用方要不要流式。 */
    fun isStreaming(openAiBody: String): Boolean = runCatching {
        val o = JsonParser.parseString(openAiBody).asJsonObject
        val s = o.get("stream")
        s != null && !s.isJsonNull && s.asBoolean
    }.getOrDefault(false)
}

/** Gson 取字符串的小工具（缺键或 null 都返回空串）。 */
private fun JsonObject.str(key: String): String = runCatching {
    get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
}.getOrDefault("")
