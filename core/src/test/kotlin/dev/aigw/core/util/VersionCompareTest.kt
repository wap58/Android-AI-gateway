package dev.aigw.core.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 版本比较规则（应用内更新用）：锁住「测试版 → 正式版能检测到更新」这条关键行为。 */
class VersionCompareTest {

    @Test
    fun `正式版之间按数字段比较`() {
        assertTrue(VersionCompare.isNewer("1.0.1", "1.0.0"))
        assertTrue(VersionCompare.isNewer("1.1.0", "1.0.9"))
        assertTrue(VersionCompare.isNewer("2.0.0", "1.99.99"))
        assertFalse(VersionCompare.isNewer("1.0.0", "1.0.1"))
        assertFalse(VersionCompare.isNewer("1.0.0", "1.0.0"))
    }

    @Test
    fun `测试版之间按构建号比较`() {
        assertTrue(VersionCompare.isNewer("1.0.0-ci.2", "1.0.0-ci.1"))
        assertTrue(VersionCompare.isNewer("1.0.0-ci.153", "1.0.0-ci.9"))
        assertFalse(VersionCompare.isNewer("1.0.0-ci.1", "1.0.0-ci.2"))
    }

    @Test
    fun `正式版比同版本测试版新`() {
        // 这是关键：用户从 CI 版切回公开版必须能检测到更新，
        // 否则会永远卡在测试包上。
        assertTrue(VersionCompare.isNewer("1.0.0", "1.0.0-ci.1"))
        assertTrue(VersionCompare.isNewer("1.0.0", "1.0.0-ci.153"))
    }

    @Test
    fun `测试版不算比同版本正式版新`() {
        assertFalse(VersionCompare.isNewer("1.0.0-ci.1", "1.0.0"))
        assertFalse(VersionCompare.isNewer("1.0.0-ci.999", "1.0.0"))
    }

    @Test
    fun `主版本高的测试版仍算新`() {
        assertTrue(VersionCompare.isNewer("1.1.0-ci.1", "1.0.0"))
        assertTrue(VersionCompare.isNewer("2.0.0-ci.1", "1.9.9"))
    }

    @Test
    fun `老版本号格式仍能比较`() {
        // 带上 release/agent 之类后缀的历史版本不应崩
        assertTrue(VersionCompare.isNewer("0.3.9.136", "0.3.9.135"))
        assertFalse(VersionCompare.isNewer("0.3.9.135", "0.3.9.136"))
    }
}
