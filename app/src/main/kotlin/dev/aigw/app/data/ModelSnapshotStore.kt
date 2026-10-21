package dev.aigw.app.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.store.KeyValueStore

/**
 * 模型目录的本地快照。
 *
 * 为什么需要它：模型目录是「进程内状态」——App 进程一重启就全空，
 * 而重新拉一遍上游要几秒到二十几秒（各家读超时不同、无账号的供应商还会失败）。
 * 用户看到的是「模型页一片空白，得手动点刷新」，网关在重建目录期间
 * `/v1/models` 也返回空。
 *
 * 于是把上一轮成功的结果落盘：
 * - 冷启动先读出快照，模型页立刻有内容（标注「缓存」）
 * - 后台静默重拉，到货后逐组替换
 * - 全量重拉成功后覆盖快照
 *
 * 只存展示需要的最小字段，不存 `extra` 之外的敏感信息（模型目录本身不含凭证）。
 */
object ModelSnapshotStore {

    private const val KEY = "models/snapshot"

    /** 快照超过这个时长就认为太旧，仅用于「先显示出来」，仍会立即触发重拉。 */
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    /** 落盘用的精简结构：避免把 RoutedModel 的嵌套类型写死在加密存储里。 */
    private data class Item(
        val p: String,
        val pn: String,
        val id: String,
        val name: String,
        val cw: Long,
        val prefix: String,
        val region: String?,
    )

    private data class Envelope(val at: Long, val items: List<Item>)

    private val gson = Gson()

    /** 上次真正落盘的时间与条数，用于节流（chunk 到货很密集）。 */
    private var lastSaveAt = 0L
    private var lastSaveCount = -1

    /**
     * 保存快照。
     *
     * [force] 为 false 时做节流：3 秒内且条数没明显变化就跳过。
     * 加密存储的 write 是「全量重加密 + fsync」，流式加载时每个供应商都写一次会很重。
     */
    fun save(store: KeyValueStore, models: List<RoutedModel>, force: Boolean = false) {
        if (models.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveAt < 3_000 && models.size == lastSaveCount) return
        lastSaveAt = now
        lastSaveCount = models.size
        runCatching {
            val env = Envelope(
                at = System.currentTimeMillis(),
                items = models.map {
                    Item(
                        p = it.providerId,
                        pn = it.providerName,
                        id = it.model.id,
                        name = it.model.name,
                        cw = it.model.contextWindow,
                        prefix = it.routePrefix,
                        region = it.region,
                    )
                },
            )
            store.write(KEY, gson.toJson(env))
        }
    }

    /** 读出快照；为空/过期/损坏时返回空列表。 */
    fun load(store: KeyValueStore): List<RoutedModel> {
        val raw = runCatching { store.read(KEY) }.getOrNull() ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<Envelope>() {}.type
            val env: Envelope = gson.fromJson(raw, type) ?: return emptyList()
            if (System.currentTimeMillis() - env.at > MAX_AGE_MS) return emptyList()
            env.items.map { item ->
                RoutedModel(
                    providerId = item.p,
                    providerName = item.pn,
                    model = ProviderModel(id = item.id, name = item.name, contextWindow = item.cw),
                    routePrefix = item.prefix,
                    region = item.region,
                    cached = true,
                )
            }
        }.getOrDefault(emptyList())
    }
}
