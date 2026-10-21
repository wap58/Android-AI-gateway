package dev.aigw.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import dev.aigw.app.AiGatewayApp
import dev.aigw.app.data.GatewayIntent
import dev.aigw.app.data.KeepAlivePrefs

/**
 * 开机自启：系统启动完成后把网关服务拉起来。
 *
 * 关键前提：`AiGatewayApp.onCreate()` 里已经构造好 GatewayEngine，
 * 所以这里只要把 GatewayService 起来，网关就活了，不依赖任何 UI 交互。
 *
 * Android 8+ 后台不能直接 startService，必须 startForegroundService；
 * 而 BOOT_COMPLETED 在「刚从开机广播启动」时是允许前台服务的豁免场景之一。
 *
 * 只监听 `BOOT_COMPLETED`：`LOCKED_BOOT_COMPLETED` 阶段用户凭据还未解锁，
 * 加密存储（EncryptedSharedPreferences）读不出来，开关状态不可信。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        // 开关存在加密存储里：读失败（未解锁/被卸载重装等）就当作未开启。
        val app = context.applicationContext as? AiGatewayApp ?: return
        val enabled = runCatching { KeepAlivePrefs.autoStartEnabled(app.store) }.getOrDefault(false)
        if (!enabled) return

        // 恢复上次状态：上次（关机 / 崩溃 / 被清理前）网关是停止的，开机就不该把它拉起来。
        // 只有「上次是运行中」才恢复，避免用户主动停掉后重启又自己跑起来。
        // 加密存储（权威）与文件标记（兜底）任一存在即视为「上次是开着的」：
        // 开机早期加密存储可能读不出（用户凭据未就绪），标记文件没有这个限制。
        val desired = runCatching { KeepAlivePrefs.desiredRunning(app.store) }.getOrDefault(false) ||
            GatewayIntent.isMarkedRunning(app)
        if (!desired) return

        val svc = Intent(context, GatewayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, svc)
            } else {
                context.startService(svc)
            }
        } catch (_: Throwable) {
            // 开机早期被系统拦截（权限未就绪 / 自启动白名单未放行）属常见情况，
            // 静默放弃，由系统白名单或用户手动打开兜底。
        }
    }
}
