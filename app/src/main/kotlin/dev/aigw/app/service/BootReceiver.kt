package dev.aigw.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import dev.aigw.app.AiGatewayApp
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
