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
 */
class AutoCheckinScheduler(
    private val scheduler: ScheduledExecutorService,
    private val prefs: AutoCheckinPrefs,
    /** 执行一次批量签到，返回 true 表示成功（只在不成功时才允许手动重试）。 */
    private val runCheckin: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private val running = AtomicBoolean(false)

    @Volatile
    private var started = false

    /** 本次进程内「启动时刻」：延迟从这里算起。 */
    @Volatile
    private var bootAt = 0L

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

    /** 每分钟的检查：过了延迟 + 今天还没成功 → 执行。 */
    internal fun tick() {
        if (!prefs.enabled()) return
        // 今天已经成功过，不用再跑
        if (prefs.isCheckedToday(nowMillis())) return
        // 启动后还没到延迟时间
        if (nowMillis() - bootAt < DELAY_MINUTES * 60_000L) return
        // 上一轮还没跑完
        if (!running.compareAndSet(false, true)) return
        try {
            // 循环内再判一次：签到要走网络，可能跨越周期边界
            if (prefs.isCheckedToday(nowMillis())) return
            val ok = runCatching { runCheckin() }.getOrDefault(false)
            if (ok) {
                // 只有成功才记「本周期已签」，失败保持可手动重试。
                // 各供应商的周期起点可能不同（Qoder 是 10:00），
                // 由 GatewayEngine 在跑完后按 provider 分别 markChecked。
                prefs.setLastResult(true, "自动签到完成")
            } else {
                prefs.setLastResult(false, "自动签到未全部成功，可手动重试")
            }
        } finally {
            running.set(false)
        }
    }

    private companion object {
        /** 启动后延迟多少分钟开始自动签到（用户指定 5 分钟）。 */
        const val DELAY_MINUTES = 5L

        /** 轮询间隔：每分钟检查一次，精度够用、开销可忽略。 */
        const val TICK_SECONDS = 60L
    }
}
