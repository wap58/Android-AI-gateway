package dev.aigw.core.provider.opencode

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/**
 * OpenCode 免费层的「准入伪装」。
 *
 * ## 核心事实（实测结论）
 *
 * 上游判定请求是否来自官方 CLI，**关键不是请求头，而是 body 里必须带
 * `shell` / `glob` / `grep` / `read` 这四个内置工具定义**。
 *
 * 实测对照（其余头完全一致）：
 *
 * | body 里的 tools | 结果 |
 * |---|---|
 * | 不带 | HTTP 403 FreeTierError |
 * | 只有 1 个（shell） | HTTP 403 |
 * | 空数组 `[]` | HTTP 403 |
 * | 四个齐全 | **HTTP 200，正常流式返回** |
 *
 * 这也解释了早先把它误判成「TLS 指纹」的原因：那时一直在调请求头，
 * 而检查点其实在 body。
 *
 * ## 为什么还要加一句提示语
 *
 * 我们注入的四个工具上游根本不会调用（免费层只做对话），
 * 但万一模型真去调用它们，请求会失败。所以在 system 消息末尾补一句
 * 「这些是占位工具，别调用」，既不改用户语义，也避免误触发。
 */
internal object OpenCodeGate {

    /** 上游内置工具名（顺序即官方 schema 顺序）。 */
    private val TOOL_NAMES = listOf("shell", "glob", "grep", "read")

    private const val PLACEHOLDER_NOTE =
        "The tools named shell, glob, grep and read in this request are placeholders. " +
            "Do not call them; use the other tools you have been given."

    /**
     * 把请求体改造成「官方 CLI 形态」。
     *
     * 返回改造后的 JSON 字符串。解析失败时原样返回——宁可让上游报错，
     * 也不要在网关里吞掉请求体。
     */
    fun ensure(body: String): String {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return body

        // 官方 CLI 一律流式；非流式请求也强制打开，网关侧再聚合回非流式
        obj.addProperty("stream", true)

        val toolChoiceNone = obj.get("tool_choice")?.asString == "none"
        if (toolChoiceNone) {
            // tool_choice=none 与「必须带工具」矛盾，直接删掉让上游用默认值
            obj.remove("tool_choice")
        }

        val kept = JsonArray()
        val present = HashSet<String>()

        // 客户端自带的下游工具：非保留名原样保留；保留名且参数非空也算「已带」
        obj.arrayOrNull("tools")?.forEach { element ->
            val tool = element.asObjectOrNull() ?: return@forEach
            val name = toolName(tool)
            if (name !in TOOL_NAMES) {
                if (!toolChoiceNone) kept.add(tool)
            } else if (paramsNonEmpty(tool)) {
                present.add(name)
                kept.add(tool)
            }
        }

        // 缺失的保留工具补回官方 schema
        var injected = false
        for (name in TOOL_NAMES) {
            if (present.add(name)) {
                kept.add(toolDefinition(name))
                injected = true
            }
        }
        obj.add("tools", kept)

        if (injected) appendPlaceholderNote(obj)
        return obj.toString()
    }

    /** 取工具名（兼容 `{function:{name}}` 与 `{name}` 两种写法）。 */
    private fun toolName(tool: JsonObject): String =
        tool.objOrNull("function")?.stringOrNull("name")
            ?: tool.stringOrNull("name")
            ?: ""

    /** 参数 schema 是否有实际内容（空对象视为「没写」）。 */
    private fun paramsNonEmpty(tool: JsonObject): Boolean {
        val params = tool.objOrNull("function")?.objOrNull("parameters")
            ?: tool.objOrNull("parameters")
            ?: return false
        val props = params.objOrNull("properties") ?: return false
        return props.entrySet().isNotEmpty()
    }

    /** 往所有 system / developer 消息末尾追加占位说明。 */
    private fun appendPlaceholderNote(obj: JsonObject) {
        val messages = obj.arrayOrNull("messages") ?: return
        for (element in messages) {
            val msg = element.asObjectOrNull() ?: continue
            val role = msg.stringOrNull("role").orEmpty()
            if (role != "system" && role != "developer") continue
            val content = msg.get("content") ?: continue
            if (content.isJsonPrimitive && content.asJsonPrimitive.isString) {
                msg.addProperty("content", content.asString + "\n" + PLACEHOLDER_NOTE)
            }
        }
    }

    /** 官方四个工具的 JSON Schema。 */
    private fun toolDefinition(name: String): JsonObject {
        val (description, paramName, paramDesc) = when (name) {
            "shell" -> Triple(
                "Execute a shell command and return its output.",
                "command",
                "The command to execute.",
            )
            "glob" -> Triple(
                "Search file paths using a glob pattern (examples: `**/*.ts`, `src/**/*.tsx`).",
                "pattern",
                "The glob pattern to match file paths against.",
            )
            "grep" -> Triple(
                "Search file contents using ripgrep syntax.",
                "pattern",
                "The regex pattern to search for in file contents.",
            )
            else -> Triple(
                "Read the contents of a file or directory.",
                "path",
                "The path to the file or directory to read.",
            )
        }
        return JsonObject().apply {
            addProperty("type", "function")
            add(
                "function",
                JsonObject().apply {
                    addProperty("name", name)
                    addProperty("description", description)
                    add(
                        "parameters",
                        JsonObject().apply {
                            addProperty("type", "object")
                            add(
                                "properties",
                                JsonObject().apply {
                                    add(
                                        paramName,
                                        JsonObject().apply {
                                            addProperty("type", "string")
                                            addProperty("description", paramDesc)
                                        },
                                    )
                                },
                            )
                            add(
                                "required",
                                com.google.gson.JsonArray().apply { add(paramName) },
                            )
                            addProperty("additionalProperties", false)
                        },
                    )
                    addProperty("strict", false)
                },
            )
        }
    }
}
