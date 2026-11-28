package dev.aigw.core.gateway

import dev.aigw.core.usage.AutoCheckinPrefs
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动签到：App 启动后延迟一段时间自动跑一轮，整天有效。
 *
 * ## 规则（用户指定）
 *
 * 1. App 启动后等 [DELAY_MINUTES] 分钟，自动执行一次批量签到
 * 2. 成功 → 记下「今天已签到」，手动按钮置灰，不再允许点
 * 3. 失败 → 不记「已签到」，按钮保持可点，让用户手动重试
 * 4. 一天只成功一次；跨天自动重置
 *
 * ## 为什么用延迟而不是固定时刻
 *
 * 固定时刻（如 9:00）在「用户 10 点才开机」时当天就漏了。
 * 延迟式则每次启动都保证「用过一会儿就签上了」，更符合日常使用。
 *
 * 仍然搭引擎的调度器轮询（每分钟看一眼「到时间了吗」），
 * 这样 App 中途被杀、重开后能继续计时而不必从头等 5 分钟。
 *
 * ## ★ 2026-11-25 修复：日锁 scope 不匹配导致的「每分钟重复签到」
 *
 * 原来这里判「今天签过了吗」用的是 `prefs.isCheckedToday()`——**全局 key**
 * （`checkin/last_day`）；而 [dev.aigw.core.gateway.GatewayEngine] 写入用的是
 * `markChecked(scope = providerId)`——**按供应商的 key**
 * （`checkin/last_day/codebuddy`）。
 *
 * 两个 key 不是同一个 → `isCheckedToday()` 永远返回 false → 启动满 5 分钟后
 * **每分钟全量重跑一轮签到**（实测配置文件时间戳每分钟被重写一次）。
 * 后果：七站一天的请求量从 ~7 次暴涨到 ~7700 次，直接把上游风控拉满，
 * 而且 App 夜间被系统杀掉后当天就再也没签上。
 *
 * 现在改成由宿主注入 [isDoneToday]：宿主知道有哪些供应商、各自的周期起点，
 * 逐个判「本周期是否都已签」，全部签完才让调度器停手。
 *
 * ## ★ 同版本修复：失败退避
 *
 * 只修 key 还不够——只要有一个账号**永远签不上**（国际版、企业号、上游挂掉），
 * `isDoneToday()` 就永远 false，同样会每分钟重跑。
 * 所以失败后按 [RETRY_BACKOFF_MINUTES] 退避，且有当日次数上限。
 * 进程重启会清零退避状态（重新计时 5 分钟再试一次），这是想要的：重启本身就是用户意图。
 */
class AutoCheckinScheduler(
    private val scheduler: ScheduledExecutorService,
    private val prefs: AutoCheckinPrefs,
    /** 执行一次批量签到，返回 true 表示成功（只在不成功时才允许手动重试）。 */
    private val runCheckin: () -> Boolean,
    /**
     * 本轮签到的全部目标是否都已在**各自周期内**完成。
     *
     * 由宿主注入（见 [dev.aigw.core.gateway.GatewayEngine]）：它知道有哪些供应商、
     * 每个供应商的 `checkinCycleStartHour`，以及哪些账号真的需要签到。
     * 不能在这里用全局 key 判断——那正是本次 bug 的成因。
     */
    private val isDoneToday: () -> Boolean = { prefs.isCheckedToday(System.currentTimeMillis()) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val running = AtomicBoolean(false)

    @Volatile
    private var started = false

    /** 本次进程内「启动时刻」：延迟从这里算起。 */
    @Volatile
    private var bootAt = 0L

    /** 最近一次失败尝试的时刻（0 = 本进程还没失败过）。 */
    @Volatile
    private var lastFailAt = 0L

    /** 本进程内连续失败次数（成功或跨天清零）。 */
    @Volatile
    private var failCount = 0

    /** 失败退避所对应的日期键，跨天时用来重置计数。 */
    @Volatile
    private var failDay = 0

    fun start() {
        if (started) return
        started = true
        bootAt = nowMillis()
        scheduler.scheduleWithFixedDelay(
            { tick() },
            TICK_SECONDS,
            TICK_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    /** 每分钟的检查：过了延迟 + 到了执行窗口 + 今天没签完 + 不在退避窗口 → 执行。 */
    internal fun tick() {
        if (!prefs.enabled()) return
        // 今天已经全部签完，不用再跑
        if (runCatching { isDoneToday() }.getOrDefault(false)) return
        // 启动后还没到延迟时间
        if (nowMillis() - bootAt < DELAY_MINUTES * 60_000L) return
        // ★ 还没到当天的执行窗口（见 NOT_BEFORE_HOUR 的说明）
        if (!inDailyWindow()) return
        // 失败退避：连续失败时拉长间隔，避免每分钟重试轰炸上游
        if (inBackoff()) return
        // 上一轮还没跑完
        if (!running.compareAndSet(false, true)) return
        try {
            // 循环内再判一次：签到要走网络，可能跨越周期边界
            if (runCatching { isDoneToday() }.getOrDefault(false)) return
            val ok = runCatching { runCheckin() }.getOrDefault(false)
            noteResult(ok)
            if (ok) {
                // 只有成功才记「本周期已签」，失败保持可手动重试。
                // 各供应商的周期起点可能不同（Qoder 是 10:00），
                // 由 GatewayEngine 在跑完后按 provider 分别 markChecked。
                prefs.setLastResult(true, "自动签到完成")
            } else {
                prefs.setLastResult(false, "自动签到未全部成功，${nextBackoffMinutes()} 分钟后自动重试")
            }
        } finally {
            running.set(false)
        }
    }

    /**
     * 当前是否已进入当天的签到窗口。
     *
     * ★ 为什么要卡一个「不早于 NOT_BEFORE_HOUR」的下限
     *
     * 各站服务端的「今日」状态重置时刻并不都在 0 点整。实测 WorkBuddy 在
     * 00:01 去签到会被回「今日已签到」——但那句话指的是**昨天**（日期刚翻，
     * 服务端还没重置）。而 [dev.aigw.core.provider.codebuddy.CodeBuddyProvider]
     * 的 `checkin()` 把「已签到」一律当成功，于是这条假信号被
     * `performActionAll` 写进了**今天**的日锁，全天判定为已完成、再也不重试。
     *
     * 表现就是用户每天醒来都要手动补一次，而活跃格看着又是亮的
     * （手动那次点亮了）。2026-11-28 定位。
     *
     * 06:00 是个保守值：此时所有站的重置都已完成，签到一定是「真·今天」。
     * 代价是凌晨不签到——但凌晨签到本来就没有意义（用户都睡了，早签晚签
     * 奖励一致），换来的是「醒来即已签好」这个确定行为。
     */
    private fun inDailyWindow(): Boolean {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis() }
        return cal.get(java.util.Calendar.HOUR_OF_DAY) >= NOT_BEFORE_HOUR
    }

    /**
     * 当前是否处于失败退避窗口，或当日机会已用尽。
     *
     * 跨天（自然日变化）自动清零：新的一天要从头再来。
     *
     * 语义说明：[RETRY_BACKOFF_MINUTES] 的每一项是「**第 N 次失败后**要等多久」。
     * 用尽全部机会后（failCount > 表长）当日不再自动重试——但仍可手动点，
     * 且跨天自然恢复。这不是「永久锁死」，只是当天不再骚扰上游。
     */
    private fun inBackoff(): Boolean {
        val day = AutoCheckinPrefs.dayKeyOf(nowMillis())
        if (day != failDay) {
            failDay = day
            failCount = 0
            lastFailAt = 0L
            return false
        }
        if (failCount <= 0 || lastFailAt <= 0L) return false
        // 当日机会用尽：不再自动重试（手动仍可，跨天恢复）
        if (failCount > RETRY_BACKOFF_MINUTES.size) return true
        val waitMs = RETRY_BACKOFF_MINUTES[failCount - 1] * 60_000L
        return nowMillis() - lastFailAt < waitMs
    }

    /** 记录一次尝试结果。成功清零；失败累加并记时刻。 */
    private fun noteResult(ok: Boolean) {
        val day = AutoCheckinPrefs.dayKeyOf(nowMillis())
        if (day != failDay) {
            failDay = day
            failCount = 0
        }
        if (ok) {
            failCount = 0
            lastFailAt = 0L
        } else {
            failCount++
            lastFailAt = nowMillis()
        }
    }

    /** 给界面看的下一次重试间隔（分钟）。 */
    internal fun nextBackoffMinutes(): Long {
        if (failCount <= 0) return RETRY_BACKOFF_MINUTES.first()
        if (failCount > RETRY_BACKOFF_MINUTES.size) return 0L
        return RETRY_BACKOFF_MINUTES[failCount - 1]
    }

    /** 供界面/日志查看本进程内的失败次数（0 = 没失败过）。 */
    internal fun currentFailCount(): Int = failCount

    private companion object {
        /** 启动后延迟多少分钟开始自动签到（用户指定 5 分钟）。 */
        const val DELAY_MINUTES = 5L

        /** 轮询间隔：每分钟检查一次，精度够用、开销可忽略。 */
        const val TICK_SECONDS = 60L

        /**
         * 当天最早几点开始自动签到。
         *
         * 见 [inDailyWindow] 的说明：早于这个时刻，上游的「今日」状态可能还没重置，
         * 「今日已签到」会指向昨天，被误记成今天完成、全天不再重试。
         * 06:00 是所有站都已完成重置的保守时刻。
         */
        const val NOT_BEFORE_HOUR = 6

        /**
         * 失败后的重试间隔（分钟），按连续失败次数取。
         *
         * 5 → 15 → 30 → 60，共 4 次机会；之后当日不再自动重试（仍可手动点）。
         * 为什么需要退避：只要有一个账号永远签不上（国际版/企业号/上游故障），
         * 「全部签完」永远不成立，不节流就会每分钟重跑一轮、一天上千次请求。
         */
        val RETRY_BACKOFF_MINUTES = longArrayOf(5L, 15L, 30L, 60L)
    }
}
