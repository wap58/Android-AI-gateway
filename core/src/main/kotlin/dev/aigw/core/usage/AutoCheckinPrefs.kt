package dev.aigw.core.usage

import dev.aigw.core.store.KeyValueStore
import java.util.Calendar

/**
 * 自动签到的状态记录。
 *
 * 只记一件事：**最后一次成功签到的自然日**。
 * 界面据此把「签到」按钮置灰；跨天自动恢复可点。
 *
 * 失败不写入——这样按钮保持可点，用户可以手动重试。
 */
class AutoCheckinPrefs(private val store: KeyValueStore) {

    fun enabled(): Boolean = store.read(KEY_ENABLED)?.let { it == "1" } ?: true

    fun setEnabled(value: Boolean) {
        store.write(KEY_ENABLED, if (value) "1" else "0")
    }

    /** 最后一次成功签到的日期键（默认全局，0 表示从未成功）。 */
    fun lastCheckedDay(scope: String = ""): Int =
        store.read(dayKeyFor(scope))?.toIntOrNull() ?: 0

    private fun dayKeyFor(scope: String): String =
        if (scope.isEmpty()) KEY_LAST_DAY else "$KEY_LAST_DAY/$scope"

    /** 记一次成功签到（用给定时间戳换算自然日）。 */
    fun markChecked(atMillis: Long, startHour: Int = 0, scope: String = "") {
        store.write(dayKeyFor(scope), dayKeyOf(atMillis, startHour).toString())
        store.write(KEY_LAST_AT, atMillis.toString())
    }

    /** 今天是否已经成功签到过（自然日口径）。 */
    fun isCheckedToday(nowMillis: Long): Boolean = isCheckedInCycle(nowMillis, startHour = 0)

    /**
     * 当前签到周期内是否已成功签到。
     *
     * @param startHour 周期起点小时。Qoder 传 10（10:00→次日 10:00 一个周期）。
     *                  记录时用同一个 startHour，保证同一周期内只成功一次。
     */
    fun isCheckedInCycle(nowMillis: Long, startHour: Int, scope: String = ""): Boolean =
        lastCheckedDay(scope) == dayKeyOf(nowMillis, startHour)

    /** 上次签到的时间戳（毫秒），0 表示没有。 */
    fun lastCheckedAt(): Long = store.read(KEY_LAST_AT)?.toLongOrNull() ?: 0L

    /** 上次自动签到的结果文案，界面直接展示。 */
    fun lastResult(): String = store.read(KEY_LAST_RESULT).orEmpty()

    /**
     * 上一次自动签到的结果。
     *
     * @param ok true=成功（按钮置灰），false=失败（按钮可点）
     */
    fun setLastResult(ok: Boolean, text: String) {
        store.write(KEY_LAST_RESULT, (if (ok) "ok|" else "fail|") + text)
    }

    /** 解析上次结果，返回 (是否成功, 文案)。 */
    fun lastResultParsed(): Pair<Boolean, String> {
        val raw = lastResult()
        if (raw.isEmpty()) return false to ""
        return when {
            raw.startsWith("ok|") -> true to raw.removePrefix("ok|")
            raw.startsWith("fail|") -> false to raw.removePrefix("fail|")
            else -> false to raw
        }
    }

    /** 手动清掉「今天已签」的标记，用于「我要重新签一次」。 */
    fun resetToday() {
        store.delete(KEY_LAST_DAY)
        store.delete(KEY_LAST_AT)
    }

    companion object {
        private const val KEY_ENABLED = "checkin/auto_enabled"
        private const val KEY_LAST_DAY = "checkin/last_day"
        private const val KEY_LAST_AT = "checkin/last_at"
        private const val KEY_LAST_RESULT = "checkin/last_result"

        /** 把毫秒时间戳换算成 yyyyMMdd 形式的自然日键。 */
        /**
         * 「签到周期」的键。
         *
         * ★ 不能按自然日（0 点）切：官方每日 100 Credits 的领取窗口是
         *   **每天 10:00 到次日 10:00**，一个周期只能领一次。
         *   按 0 点切会在 00:00–09:59 这段判成「新的一天」，于是重复去领
         *   （上游返回 409 AlreadyExists），或者反过来把还能领的周期判成已领。
         *
         * 做法：把时间往前推 [CYCLE_START_HOUR] 小时再取日期 —— 10:00 之前的时刻
         * 会落到前一天，正好和官方的周期边界对齐。
         */
        fun dayKeyOf(millis: Long): Int = dayKeyOf(millis, startHour = 0)

        /**
         * 以任意小时为日界线的日期键。
         *
         * 多数供应商按自然日（startHour=0）算，但 **Qoder 的每日 100 Credits
         * 是每天 10:00 到次日 10:00 一个周期**，必须用 startHour=10，
         * 否则 00:00–09:59 这段会被判成新的一天而重复去领（上游返回 409）。
         */
        fun dayKeyOf(millis: Long, startHour: Int): Int {
            val cal = Calendar.getInstance().apply {
                timeInMillis = millis - startHour * 3600_000L
            }
            return cal.get(Calendar.YEAR) * 10000 +
                (cal.get(Calendar.MONTH) + 1) * 100 +
                cal.get(Calendar.DAY_OF_MONTH)
        }
    }
}
