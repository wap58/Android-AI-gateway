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

    /**
     * 测试用的时间锚点：**本地时区**的某天 00:10。
     *
     * ★ 不能写成固定毫秒值：`AutoCheckinPrefs.dayKeyOf` 用默认时区切自然日，
     *   而 CI 跑 UTC、本地跑 CST(+8)，同一个绝对时刻在两边属于不同的「天」。
     *   用 Calendar 按本地时区构造，保证「锚点 + 若干小时」始终落在同一个自然日里，
     *   测试结果才不会随运行环境变化（第一版就踩了这个坑，本地过、CI 红）。
     */
    private fun localAnchor(hour: Int = 0, minute: Int = 10): Long =
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.YEAR, 2026)
            set(java.util.Calendar.MONTH, java.util.Calendar.NOVEMBER)
            set(java.util.Calendar.DAY_OF_MONTH, 15)
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val t0: Long = localAnchor()

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
        // 每次都给足最长的退避时间（70 分钟 > 60 分钟），把当日机会跑完
        repeat(10) {
            s.tick()
            clock.advanceMinutes(70)
        }

        // 退避表 5/15/30/60 共 4 档：首次执行 + 4 次重试 = 5 次，之后当日停手。
        // 这条断言是**精确**的，不留浮动区间——浮动区间会掩盖退避表被改动。
        assertEquals(5, runs, "当日应恰好尝试 5 次（1 次首跑 + 4 次退避重试）后停手")
    }

    @Test
    fun `天然时区锚点保证测试不随运行环境变化`() {
        // 这条锁住「用 Calendar 按本地时区构造时刻」这个前提：
        // 若有人改回硬编码毫秒值，UTC 与 CST 的跨日点不同，上面几条会随机变红。
        val a = localAnchor(hour = 0, minute = 10)
        val b = localAnchor(hour = 23, minute = 50)
        assertEquals(
            AutoCheckinPrefs.dayKeyOf(a),
            AutoCheckinPrefs.dayKeyOf(b),
            "同一本地自然日内的两个时刻必须属于同一天键",
        )
    }
}
