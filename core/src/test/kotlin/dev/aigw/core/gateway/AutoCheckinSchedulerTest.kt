package dev.aigw.core.gateway

import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.usage.AutoCheckinPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 自动签到的日锁与失败退避。
 *
 * ## 为什么要有这组测试（2026-11-25 线上事故）
 *
 * 1.1.0 的日锁读写不是同一个 key：
 *   - 写：`markChecked(scope = providerId)` → `checkin/last_day/codebuddy`
 *   - 读：`isCheckedToday()`              → `checkin/last_day`
 *
 * 于是「今天签过了吗」永远 false，App 启动满 5 分钟后**每分钟全量重跑一轮签到**
 * （实测配置文件时间戳每分钟被重写）。七站请求量从每天 7 次涨到上千次，
 * 上游风控被打满，手机耗电与流量也白烧。
 *
 * 只修 key 还不够：只要有一个账号永远签不上（国际版/企业号/上游故障），
 * 「全部签完」永远不成立，同样会每分钟重跑。所以还有失败退避。
 */
class AutoCheckinSchedulerTest {

    /** 内存存储，够用且不碰文件。 */
    private class MemoryStore : KeyValueStore {
        private val map = HashMap<String, String>()
        override fun read(key: String): String? = map[key]
        override fun write(key: String, value: String) {
            map[key] = value
        }
        override fun delete(key: String) {
            map.remove(key)
        }
        override fun keys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
    }

    private val t0 = 1_764_000_000_000L // 2025-11-25 附近，只用于固定时间推进

    /** 可手动推进的时钟。 */
    private class Clock(var now: Long) {
        fun advanceMinutes(m: Long) {
            now += m * 60_000L
        }
    }

    private fun scheduler(
        prefs: AutoCheckinPrefs,
        clock: Clock,
        done: () -> Boolean,
        onRun: () -> Boolean,
    ): AutoCheckinScheduler = AutoCheckinScheduler(
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "test").apply { isDaemon = true }
        },
        prefs = prefs,
        runCheckin = onRun,
        isDoneToday = done,
        nowMillis = { clock.now },
    ).also { it.start() }

    @Test
    fun `日锁写入与读取必须使用同一个 scope`() {
        val store = MemoryStore()
        val prefs = AutoCheckinPrefs(store)

        // 模拟 GatewayEngine 的写法：按供应商记
        prefs.markChecked(t0, startHour = 0, scope = "codebuddy")

        // 按同一个 scope 读 → 必须读到
        assertTrue(
            prefs.isCheckedInCycle(t0, startHour = 0, scope = "codebuddy"),
            "同 scope 必须能读到刚写下的日锁",
        )
        // 全局 key 读不到是**预期的**（这正是事故成因，谁再拿全局 key 判断就是 bug）
        assertFalse(
            prefs.isCheckedToday(t0),
            "全局 key 与按供应商的 key 不同，读不到是正常的；调度器不能再用全局 key 判断",
        )
    }

    @Test
    fun `已签完时不再执行签到`() {
        val store = MemoryStore()
        val prefs = AutoCheckinPrefs(store)
        val clock = Clock(t0)
        var runs = 0
        val s = scheduler(prefs, clock, done = { true }, onRun = { runs++; true })

        clock.advanceMinutes(10)
        s.tick()
        assertEquals(0, runs, "全部签完之后绝不能再来一轮")
    }

    @Test
    fun `未到延迟时间不执行`() {
        val store = MemoryStore()
        val prefs = AutoCheckinPrefs(store)
        val clock = Clock(t0)
        var runs = 0
        val s = scheduler(prefs, clock, done = { false }, onRun = { runs++; true })

        clock.advanceMinutes(1)
        s.tick()
        assertEquals(0, runs, "启动未满 5 分钟不该跑")
    }

    @Test
    fun `成功一次后即记为完成`() {
        val store = MemoryStore()
        val prefs = AutoCheckinPrefs(store)
        val clock = Clock(t0)
        var done = false
        var runs = 0
        val s = scheduler(prefs, clock, done = { done }, onRun = {
            runs++
            done = true
            true
        })

        clock.advanceMinutes(6)
        s.tick()
        assertEquals(1, runs)

        // 之后每分钟都不该再跑
        repeat(30) {
            clock.advanceMinutes(1)
            s.tick()
        }
        assertEquals(1, runs, "成功后必须停手，这正是 1.1.0 的事故点")
    }

    @Test
    fun `连续失败按退避拉长间隔且当日有上限`() {
        val store = MemoryStore()
        val prefs = AutoCheckinPrefs(store)
        val clock = Clock(t0)
        var runs = 0
        val s = scheduler(prefs, clock, done = { false }, onRun = { runs++; false })

        // 满 5 分钟后第一次尝试
        clock.advanceMinutes(6)
        s.tick()
        assertEquals(1, runs, "首次失败应当执行一次")

        // 紧接着每分钟都不该重试（退避 5 分钟）
        repeat(4) {
            clock.advanceMinutes(1)
            s.tick()
        }
        assertEquals(1, runs, "失败后必须退避，不能每分钟重试")

        // 过了退避窗口才会再试
        clock.advanceMinutes(2)
        s.tick()
        assertEquals(2, runs, "退避期满后应再试一次")
    }

    @Test
    fun `跨天后退避计数重置`() {
        val store = MemoryStore()
        val prefs = AutoCheckinPrefs(store)
        val clock = Clock(t0)
        var runs = 0
        val s = scheduler(prefs, clock, done = { false }, onRun = { runs++; false })

        clock.advanceMinutes(6)
        s.tick()
        assertEquals(1, runs)

        // 跨日（推进 24 小时）
        clock.advanceMinutes(24 * 60)
        s.tick()
        assertEquals(2, runs, "新的一天要从头再来，不能被前一天的失败数卡死")
    }

    @Test
    fun `当日失败次数用尽后不再自动重试`() {
        val store = MemoryStore()
        val prefs = AutoCheckinPrefs(store)
        val clock = Clock(t0)
        var runs = 0
        val s = scheduler(prefs, clock, done = { false }, onRun = { runs++; false })

        clock.advanceMinutes(6)
        // 每次都给足退避时间，跑满所有机会
        repeat(10) {
            s.tick()
            clock.advanceMinutes(70)
        }
        // 退避表有 4 档，首次 + 4 次重试 ≈ 5 次以内
        assertTrue(runs <= 5, "当日重试次数必须有上限，当前 $runs 次")
        assertTrue(runs >= 1)
    }
}
