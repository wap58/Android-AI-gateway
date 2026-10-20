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

    /** 最后一次成功签到的自然日（yyyyMMdd），0 表示从未成功。 */
    fun lastCheckedDay(): Int = store.read(KEY_LAST_DAY)?.toIntOrNull() ?: 0

    /** 记一次成功签到（用给定时间戳换算自然日）。 */
    fun markChecked(atMillis: Long) {
        store.write(KEY_LAST_DAY, dayKeyOf(atMillis).toString())
        store.write(KEY_LAST_AT, atMillis.toString())
    }

    /** 今天是否已经成功签到过。 */
    fun isCheckedToday(nowMillis: Long): Boolean = lastCheckedDay() == dayKeyOf(nowMillis)

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
        fun dayKeyOf(millis: Long): Int {
            val cal = Calendar.getInstance().apply { timeInMillis = millis }
            return cal.get(Calendar.YEAR) * 10000 +
                (cal.get(Calendar.MONTH) + 1) * 100 +
                cal.get(Calendar.DAY_OF_MONTH)
        }
    }
}
