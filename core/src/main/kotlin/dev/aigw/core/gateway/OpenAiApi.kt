package dev.aigw.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.aigw.core.provider.RoutedModel

/** OpenAI 兼容层的报文拼装。 */
internal object OpenAiApi {

    fun errorBody(code: String, message: String): String = JsonObject().apply {
        add("error", JsonObject().apply {
            addProperty("message", message)
            addProperty("type", "api_error")
            addProperty("code", code)
        })
    }.toString()

    /**
     * 模型列表。对外 id 带 `provider/` 前缀（如 `trae/Seed-2.1-Code`），
     * 客户端据此在同一个端点下选择任意供应商的模型。
     */
    fun modelList(models: List<RoutedModel>): String {
        val data = JsonArray()
        for (routed in models) {
            data.add(JsonObject().apply {
                addProperty("id", routed.fullId)
                // 人类可读名（Qwen3.8-Max / DeepSeek-Flash 这类官方名）。
                // OpenAI 规范没规定这个字段，但各家客户端普遍读 name / display_name，
                // 不输出的话它们只能拿 id 自己拼成 "Qoder Cn Dfmodel" 这种鬼东西。
                addProperty("name", routed.model.name.ifEmpty { routed.fullId })
                addProperty("display_name", routed.model.name.ifEmpty { routed.fullId })
                addProperty("object", "model")
                addProperty("created", CREATED)
                addProperty("owned_by", routed.providerId)
                if (routed.model.contextWindow > 0) addProperty("context_length", routed.model.contextWindow)
            })
        }
        return JsonObject().apply {
            addProperty("object", "list")
            add("data", data)
        }.toString()
    }

    const val CREATED = 1_753_600_000L
}
