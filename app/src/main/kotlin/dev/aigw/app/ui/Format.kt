package dev.aigw.app.ui

import java.util.Locale

/** 千分位计数（如 1,234,567）。 */
fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

/** 人类友好缩写：3.82M / 276.6K。 */
fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> String.format(Locale.US, "%.2fM", tokens / 1_000_000.0)
    tokens >= 1_000L -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
    else -> tokens.toString()
}

/** 耗时缩写：850ms / 22.3s / 5m12s / 1h03m。记录页胶囊用它代替动辄五位的毫秒值。 */
fun formatDuration(millis: Long): String {
    if (millis < 1_000L) return "${millis}ms"
    if (millis < 60_000L) return String.format(Locale.US, "%.1fs", millis / 1000.0)
    val seconds = millis / 1000L
    if (seconds < 3_600L) return "${seconds / 60}m${(seconds % 60).toString().padStart(2, '0')}s"
    val hours = seconds / 3_600L
    return "${hours}h${((seconds % 3_600L) / 60L).toString().padStart(2, '0')}m"
}
