package dev.aigw.core.provider.opencode

import com.google.gson.JsonParser
import dev.aigw.core.util.HttpSupport

/**
 * OpenCode 模型目录。
 *
 * ## 两个端点
 *
 * | 端点 | 数量 | 说明 |
 * |---|---|---|
 * | `/zen/v1/models` | 约 85 | 主力通道 |
 * | `/zen/go/v1/models` | 约 37 | Go 订阅通道，与 zen 有重叠 |
 *
 * 合并时按 id 去重，`surface` 保留第一次出现的那条（zen 优先），
 * 界面据此判断模型来自哪条通道。
 *
 * ## 缓存
 *
 * 两个列表都是公开接口、无需鉴权，但每次都拉会拖慢模型页首屏，
 * 因此缓存 [CACHE_TTL_MS]。失败时回落到上次成功的结果并标记 `fromFallback`，
 * 与其它 provider 的做法一致——旧清单比空列表有用。
 */
internal class OpenCodeModelCatalog(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    data class Model(val id: String, val surface: String)

    @Volatile
    private var cached: List<Model> = emptyList()

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var lastErrorText: String = ""

    /** 上次成功的结果；失败时用它兜底。 */
    @Volatile
    private var lastGood: List<Model> = emptyList()

    val fromFallback: Boolean get() = cached.isNotEmpty() && lastErrorText.isNotEmpty()

    val lastError: String get() = lastErrorText

    fun invalidate() {
        cachedAt = 0L
    }

    fun models(): List<Model> {
        val now = nowMillis()
        if (cached.isNotEmpty() && now - cachedAt < CACHE_TTL_MS) return cached

        val fetched = ArrayList<Model>()
        val errors = ArrayList<String>()
        for ((url, surface) in ENDPOINTS) {
            val list = runCatching { fetch(url, surface) }
                .onFailure { errors.add("$surface: ${it.message ?: "拉取失败"}") }
                .getOrDefault(emptyList())
            fetched.addAll(list)
        }

        return if (fetched.isEmpty()) {
            lastErrorText = errors.joinToString("；").ifEmpty { "模型目录为空" }
            // 回落到上次成功的结果（可能为空列表，即真的没有缓存）
            lastGood
        } else {
            val merged = LinkedHashMap<String, Model>()
            for (m in fetched) merged.putIfAbsent(m.id, m)
            val result = merged.values.toList()
            lastErrorText = ""
            lastGood = result
            cached = result
            cachedAt = now
            result
        }
    }

    private fun fetch(url: String, surface: String): List<Model> {
        val conn = HttpSupport.open(
            url = url,
            method = "GET",
            headers = mapOf(
                "Authorization" to "Bearer public",
                "Accept" to "application/json",
            ),
            body = null,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 15_000,
            allowCompression = true,
        )
        val status = conn.responseCode
        val (body, fullyRead) = HttpSupport.readLimitedTracked(conn.inputStream)
        HttpSupport.release(conn, fullyRead)
        if (status !in 200..299) throw IllegalStateException("HTTP $status")

        val root = JsonParser.parseString(body).asJsonObject
        val data = root.getAsJsonArray("data") ?: return emptyList()
        val out = ArrayList<Model>(data.size())
        for (element in data) {
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: continue
            val modelId = obj.get("id")?.asString?.trim().orEmpty()
            if (modelId.isEmpty()) continue
            out.add(Model(modelId, surface))
        }
        return out
    }

    private companion object {
        /** 目录缓存时长：10 分钟。上游目录变动不频繁，没必要每次刷新都拉。 */
        const val CACHE_TTL_MS = 10 * 60 * 1000L

        val ENDPOINTS = listOf(
            "https://opencode.ai/zen/v1/models" to "zen",
            "https://opencode.ai/zen/go/v1/models" to "go",
        )
    }
}
