package dev.aigw.core.usage

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.store.KeyValueStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条签到记录。
 *
 * 单独存，不混在请求日志里：请求日志是环形缓冲（300 条，几小时就刷掉了），
 * 而签到是「每天一次、需要追溯拿到多少点」的东西，必须能长期查到。
 */
data class CheckinRecord(
    val seq: Long,
    /** 发生时间（毫秒）。 */
    val atMillis: Long,
    val providerId: String,
    /** 账号显示名。 */
    val account: String,
    /** true=成功拿到；false=失败或今日已签。 */
    val ok: Boolean,
    /** 本次获得的数量（智点/积分）；0 表示没拿到或未知。 */
    val amount: Long,
    /** 连续天数（部分供应商有）。 */
    val streak: Int,
    /** 累计（部分供应商有）。 */
    val total: Long,
    /** 结果说明（上游原话或错误原因）。 */
    val message: String,
    /**
     * 结果类型：
     * - `claimed` 新签到成功
     * - `already` 今天已经签过
     * - `failed`  失败
     */
    val kind: String,
) {
    fun renderTime(): String = FULL.format(Date(atMillis))
    fun renderDay(): String = DAY.format(Date(atMillis))

    companion object {
        const val KIND_CLAIMED = "claimed"
        const val KIND_ALREADY = "already"
        const val KIND_FAILED = "failed"

        private val FULL = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }
}

/**
 * 签到记录存储。
 *
 * 容量给到 1000 条（一天几条的话够存一年多），按时间倒序保存，最新的在前。
 * 持久化键独立于请求日志，清空请求日志不会影响签到历史。
 */
class CheckinLogStore(
    private val store: KeyValueStore,
    capacity: Int = 1000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val capacity = capacity.coerceAtLeast(1)

    private val records = ArrayDeque<CheckinRecord>()
    private var nextSeq = 0L
    private var lastPersistAt = 0L

    init {
        load()
    }

    @Synchronized
    fun add(
        providerId: String,
        account: String,
        ok: Boolean,
        amount: Long,
        streak: Int,
        total: Long,
        message: String,
        kind: String,
    ) {
        val now = nowMillis()
        records.addFirst(
            CheckinRecord(
                seq = nextSeq++,
                atMillis = now,
                providerId = providerId,
                account = account,
                ok = ok,
                amount = amount,
                streak = streak,
                total = total,
                message = message,
                kind = kind,
            ),
        )
        while (records.size > capacity) records.removeLast()
        // 写盘节流：store.write 是「全量重加密 + fsync」，批量签到 10 个账号
        // 同步写 10 次会把收尾压成几百毫秒。与 RequestLog 保持同样的节流策略。
        if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
            persist()
            lastPersistAt = now
        }
    }

    /** 立即落盘（退出前或需要保证持久化时调用）。 */
    @Synchronized
    fun flush() {
        persist()
        lastPersistAt = nowMillis()
    }

    /** 最新在前。 */
    @Synchronized
    fun all(): List<CheckinRecord> = records.toList()

    /** 按供应商筛选。 */
    @Synchronized
    fun of(providerId: String): List<CheckinRecord> =
        records.filter { it.providerId == providerId }

    /** 某天某供应商是否已经成功签过（用于避免重复跑）。 */
    @Synchronized
    fun hasClaimedOn(providerId: String, uid: String, dayMillis: Long): Boolean {
        val day = dayOf(dayMillis)
        return records.any {
            it.providerId == providerId &&
                it.account == uid &&
                it.kind == CheckinRecord.KIND_CLAIMED &&
                dayOf(it.atMillis) == day
        }
    }

    /** 今日汇总：成功次数、总获得。 */
    @Synchronized
    fun todaySummary(): Pair<Int, Long> {
        val today = dayOf(nowMillis())
        var count = 0
        var sum = 0L
        records.forEach {
            if (dayOf(it.atMillis) == today && it.kind == CheckinRecord.KIND_CLAIMED) {
                count++
                sum += it.amount
            }
        }
        return count to sum
    }

    @Synchronized
    fun clear() {
        records.clear()
        persist()
        lastPersistAt = nowMillis()
    }

    private fun persist() {
        val array = JsonArray()
        for (r in records) {
            array.add(JsonObject().apply {
                addProperty("at", r.atMillis)
                addProperty("provider", r.providerId)
                addProperty("account", r.account)
                addProperty("ok", r.ok)
                addProperty("amount", r.amount)
                addProperty("streak", r.streak)
                addProperty("total", r.total)
                addProperty("message", r.message)
                addProperty("kind", r.kind)
            })
        }
        store.write(STORE_KEY, array.toString())
    }

    private fun load() {
        val raw = store.read(STORE_KEY) ?: return
        val array = runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull() ?: return
        for (element in array) {
            val o = runCatching { element.asJsonObject }.getOrNull() ?: continue
            records.addLast(
                CheckinRecord(
                    seq = nextSeq++,
                    atMillis = o.get("at")?.asLong ?: 0L,
                    providerId = o.get("provider")?.asString.orEmpty(),
                    account = o.get("account")?.asString.orEmpty(),
                    ok = o.get("ok")?.asBoolean ?: false,
                    amount = o.get("amount")?.asLong ?: 0L,
                    streak = o.get("streak")?.asInt ?: 0,
                    total = o.get("total")?.asLong ?: 0L,
                    message = o.get("message")?.asString.orEmpty(),
                    kind = o.get("kind")?.asString.orEmpty(),
                ),
            )
        }
        while (records.size > capacity) records.removeLast()
    }

    companion object {
        const val STORE_KEY = "logs/checkins.json"

        /** 两次写盘的最小间隔。 */
        private const val PERSIST_INTERVAL_MS = 2_000L

        /** 按本地时区把毫秒格式化成 yyyy-MM-dd，用于「同一天」判定。 */
        private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        @Synchronized
        private fun dayOf(millis: Long): String = DAY_FORMAT.format(Date(millis))
    }
}
