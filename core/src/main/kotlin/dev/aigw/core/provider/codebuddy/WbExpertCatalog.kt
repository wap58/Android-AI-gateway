package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * WorkBuddy 专家市场配置（内嵌资源，无需联网）。
 *
 * 数据来源：上游公开 CDN 的 `expert_center.json`（429 个专家，其中 53 个是专家团）。
 * 这些 id 是**真实**的——`expert_5`、`Expert_team_use_3` 等任务上报时服务端会校验，
 * 自造 id 不计数。
 */
private fun JsonObject.str(k: String): String =
    get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

internal object WbExpertCatalog {

    data class Expert(
        val id: String,
        val name: String,
        val profession: String,
        val industryId: String,
        val isTeam: Boolean,
    )

    private val cached: List<Expert> by lazy { load() }

    /** 全部专家。 */
    fun all(): List<Expert> = cached

    /** 普通专家（`expert_5` 召唤用的就是这类）。 */
    fun agents(): List<Expert> = cached.filter { !it.isTeam }

    /** 专家团（`Expert_team_use_3` 用的）。 */
    fun teams(): List<Expert> = cached.filter { it.isTeam }

    /** 按 id 精确查找。 */
    fun byId(id: String): Expert? = cached.firstOrNull { it.id == id }

    private fun load(): List<Expert> = runCatching {
        val stream = WbExpertCatalog::class.java
            .getResourceAsStream("/wb_experts.json") ?: return emptyList()
        val text = stream.bufferedReader().use { it.readText() }
        val root = JsonParser.parseString(text)
        // 资源是扁平数组；兼容将来可能改成 {"experts":[...]} 的形态
        val arr = if (root.isJsonArray) root.asJsonArray else root.asJsonObject.getAsJsonArray("experts")
            ?: return emptyList()
        val out = ArrayList<Expert>(arr.size())
        arr.forEach { el ->
            val o = el.asJsonObject
            val id = o.str("id")
            if (id.isNotEmpty()) {
                out.add(
                    Expert(
                        id = id,
                        name = o.str("name"),
                        profession = o.str("profession"),
                        industryId = o.str("industryId"),
                        isTeam = o.get("team")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                    ),
                )
            }
        }
        out
    }.getOrDefault(emptyList())

}
