package dev.aigw.core.util

/**
 * 版本号比较（应用内更新用）。
 *
 * 规则（按优先级）：
 * 1. **主版本逐段比**：正式版比全部数字段，测试版比前 3 段（`1.0.0-ci.1` 的主版本是 `1.0.0`）。
 *    这一段保证「主版本高就是新版本」。
 * 2. **主版本相同时**：正式版 > 测试版。因为正式版是稳定线，用户从 CI 版切回公开版
 *    必须能检测到更新，否则会永远停在测试包上。
 * 3. **同为测试版**：比完整数字序列，`1.0.0-ci.2` > `1.0.0-ci.1`。
 *
 * 为什么测试版只比前 3 段：CI 版本名形如 `1.0.0-ci.153`，如果拿全序列跟 `1.0.0` 比，
 * `[1,0,0,153]` 会比 `[1,0,0]` 大，导致正式版永远判不成更新。
 */
object VersionCompare {

    /** 提取所有数字段。`0.3.5_ci_42` → [0,3,5,42]。 */
    private fun nums(v: String): List<Int> =
        Regex("""\d+""").findAll(v).mapNotNull { it.value.toIntOrNull() }.toList()

    /** 带字母的是测试版（`1.0.0-ci.1`），纯数字是正式版（`1.0.0`）。 */
    private fun isPrerelease(v: String): Boolean = Regex("""[A-Za-z]""").containsMatchIn(v)

    /** 远端版本比本地新返回 true。 */
    fun isNewer(remote: String, local: String): Boolean {
        val r = nums(remote)
        val l = nums(local)
        val rPre = isPrerelease(remote)
        val lPre = isPrerelease(local)

        // 1) 主版本比较：测试版只取前 3 段，正式版取全部
        val rCore = if (rPre) r.take(3) else r
        val lCore = if (lPre) l.take(3) else l
        for (i in 0 until maxOf(rCore.size, lCore.size)) {
            val a = rCore.getOrElse(i) { 0 }
            val b = lCore.getOrElse(i) { 0 }
            if (a != b) return a > b
        }

        // 2) 主版本相同：正式版更新
        if (rPre != lPre) return !rPre
        if (!rPre) return false

        // 3) 同为测试版：比完整序列（含 ci 后的构建号）
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
