package dev.aigw.app.service

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.aigw.app.data.GatewayIntent
import dev.aigw.app.gatewayEngine

/**
 * 后台自动开启：应用退到后台并连续停留 1 分钟后，若网关没在跑就自动拉起来。
 *
 * ## 判据同样是「Activity 计数归零」
 *
 * `onPause` / `onUserLeaveHint` 在应用内跳页面时也会触发（见 [HideRecents] 的教训），
 * 拿它当「退到后台」会误判。只有 started 计数归零才是真的离开了前台。
 *
 * ## 唯一的例外：用户主动停止
 *
 * 用户点过「停止服务」（[GatewayIntent] 记为 false）就什么都不做 ——
 * 否则关掉网关、切个微信，一分钟后又自己跑起来，等于关不掉。
 * 全新安装尚未表态过（从未记录）时按「允许」处理：打开一次应用切后台即可自动开启。
 *
 * ## 为什么不在前台就开
 *
 * 前台时用户就在界面里，按开关是一秒的事；自动开启反而会打断「我只是想看看模型列表」的场景。
 */
object BackgroundAutoStart {

    /** 后台连续停留多久后自动开启。 */
    private const val DELAY_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())

    private var startedCount = 0
    private var installed = false
    private var pending: Runnable? = null

    fun install(app: Application) {
        if (installed) return
        installed = true
        // 直接用 Application 本身：applicationContext 的静态类型是 Context，
        // 传给需要 Application 的 schedule() 会编译失败
        val application: Application = app
        // 进程可能在后台被创建（服务/磁贴拉起），此时先排一次队：
        // 若前台从未出现过，等待期内用户打开应用就会被取消，不会误触发。
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedCount++
                cancel()
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount--
                if (startedCount > 0) return
                startedCount = 0
                if (activity.isChangingConfigurations) return
                schedule(application)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun schedule(app: Application) {
        cancel()
        val runnable = Runnable { fire(app) }
        pending = runnable
        handler.postDelayed(runnable, DELAY_MS)
    }

    private fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    private fun fire(app: Application) {
        pending = null
        // 等期内又回到前台：放弃这次
        if (startedCount > 0) return
        // ★ 所有判断都要读加密存储（解密一次几百毫秒），必须离开主线程做。
        //   这段回调是 Handler(Looper.getMainLooper()) 发的，直接在主线程序列化就是一次掉帧。
        Thread({
            val store = (app as? dev.aigw.app.AiGatewayApp)?.store
            val switchOn = store == null ||
                runCatching { dev.aigw.app.data.KeepAlivePrefs.bgAutoStartEnabled(store) }
                    .getOrDefault(true)
            if (!switchOn) return@Thread
            // 用户主动停止过：绝不自作主张
            if (GatewayIntent.isExplicitlyStopped(app)) return@Thread
            val engine = runCatching { app.gatewayEngine }.getOrNull() ?: return@Thread
            if (engine.isRunning()) return@Thread

            // ★ 顺序不能反。Android 12+ 禁止「后台启动前台服务」，
            // 在这里 startForegroundService 会抛 ForegroundServiceStartNotAllowedException。
            // 所以先起引擎（进程内起 HTTP server 不受限制），端口先活；
            // 前台服务再尽力尝试，成功就顺带拿到保活与常驻通知，失败也不影响网关可用。
            val startedOk = runCatching { engine.start() }.isSuccess
            if (!startedOk) return@Thread

            // ★ 无论前台服务是否成功，都要把「用户希望网关开着」落盘：
            //   这条路径存在的理由恰好就是「后台启动前台服务被系统拒绝」，
            //   若只在服务里补写，那种情况下状态永远是「关」——
            //   守护脚本会把已经跑着的网关当成用户主动停止，杀掉后再也不恢复。
            runCatching { GatewayIntent.markRunning(app, true) }

            val fgs = runCatching {
                ContextCompat.startForegroundService(app, Intent(app, GatewayService::class.java))
            }
            if (fgs.isSuccess) {
                engine.requestLog.info("应用在后台停留 1 分钟，网关已自动开启")
            } else {
                engine.requestLog.warn(
                    "应用在后台停留 1 分钟，网关已自动开启；前台服务被系统限制（" +
                        (fgs.exceptionOrNull()?.javaClass?.simpleName ?: "未知") +
                        "），打开应用后即恢复常驻",
                )
            }
        }, "bg-autostart").start()
    }
}
