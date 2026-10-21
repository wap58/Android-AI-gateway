package dev.aigw.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.aigw.app.MainActivity
import dev.aigw.app.R
import dev.aigw.app.AiGatewayApp
import dev.aigw.app.gatewayEngine

/**
 * 只负责「让进程活着」：网关本身由 UI 侧启动，成功后拉起本服务，
 * 这样锁屏或切后台后端口仍有人监听。
 */
class GatewayService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = applicationContext.gatewayEngine
        // 通知必须最先发：startForegroundService 拉起的服务不调 startForeground
        // 会被系统判定超时直接崩溃（ForegroundServiceDidNotStartInTime），
        // 而且「服务已启动」本身就是用户要的反馈，不应等引擎验证完才弹。
        // NanoHTTPD.start(timeout=0) 会同步等 bind 完成/抛出，engine.start() 返回
        // 即端口已就绪，所以这里不需要任何先导耗时操作。
        startForeground(NOTIFICATION_ID, buildNotification(engine.settings().port))
        val started = engine.isRunning() || runCatching { engine.start() }.isSuccess
        if (started) {
            // 服务能起来就说明网关此刻应当运行：补记期望状态（Root 守护/开机自启据此恢复）
            dev.aigw.app.data.GatewayIntent.markRunning(applicationContext, true)
            engine.requestLog.info("前台服务已拉起，通知已发布")
            acquireLocks()
            applyOverlay(engine)
            // 恢复上次状态：用户希望网关开着（标记存在）时用 START_STICKY，
            // 系统回收进程后由系统重建服务、网关自动恢复，无需用户再打开 App；
            // 用户已主动停止则 START_NOT_STICKY，绝不自己冒出来。
            return if (dev.aigw.app.data.GatewayIntent.isMarkedRunning(applicationContext)) {
                START_STICKY
            } else {
                START_NOT_STICKY
            }
        }
        // 引擎没起来：撤掉刚发的通知再退出，避免「通知说运行中、端口没人听」
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        FloatingKeepAlive.hide(this)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 锁屏后 CPU 与 Wi-Fi 会进省电，已有的 TCP 连接会被挂起，客户端只会看到「连接断开」；
     * 前台服务只保证进程不被杀，不保证网络与 CPU 常醒，所以这里显式持锁。
     */
    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, WIFI_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    /**
     * 可选的悬浮窗保活：用户开启时才显示（需要「显示在其他应用上层」权限），
     * 服务停止时一并移除。未授权时静默跳过，不影响网关运行。
     */
    private fun applyOverlay(engine: dev.aigw.core.gateway.GatewayEngine) {
        if (!dev.aigw.app.data.KeepAlivePrefs.overlayEnabled(engine.store())) return
        val shown = FloatingKeepAlive.show(this, "网关 ${engine.settings().port}")
        if (shown) {
            engine.requestLog.info("悬浮窗保活已开启")
        } else {
            engine.requestLog.warn("悬浮窗保活已开启但未获授权（显示在其他应用上层），本次未显示")
        }
    }

    private fun buildNotification(port: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AiGatewayApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("本地网关运行中 · 端口 $port")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "aigw:gateway"
        private const val WIFI_LOCK_TAG = "aigw:gateway-wifi"
    }
}
