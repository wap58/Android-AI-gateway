package dev.aigw.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import dev.aigw.app.data.EncryptedKeyValueStore
import dev.aigw.app.data.LanAddresses
import dev.aigw.core.gateway.GatewayEngine

/**
 * 应用入口。
 *
 * 登录改为「系统浏览器 + 本地回调监听」后，App 里不再有 WebView，
 * 也就没有独立的 `:login` 进程，引擎在主进程构造即可。
 */
class AiGatewayApp : Application() {

    /** 整个进程共享一个网关引擎。 */
    var engine: GatewayEngine? = null
        private set

    /** 全局共享的键值存储（保活开关与引擎共用，避免重复解密）。 */
    lateinit var store: EncryptedKeyValueStore
        private set

    override fun onCreate() {
        super.onCreate()
        // 崩溃现场记录：写到 /sdcard/Download/tyd-crash.txt。
        // 有些设备（USB 调试打不开、Shizuku 激活不了）崩溃后完全拿不到日志，只能靠它。
        CrashRecorder.install(this)
        store = EncryptedKeyValueStore(this)
        val created = GatewayEngine(
            store = store,
            lanAddressProvider = { LanAddresses.current(this) },
            // 调用记录预载与过期清理改走后台，冷启动首帧不再等全量解密
            preloadCallLogs = false,
            // 完整原文溢出到缓存目录（系统空间不足时可回收）：加密存储只留 8K 摘要，
            // 详情页打开时按记录 id 回读全文
            spillDir = java.io.File(cacheDir, "call-fulltext"),
            // 记录落盘改走后台队列：加密存储 commit() 是「全量重加密 + fsync」，
            // 一次请求要写 3 个键，同步做会把几百毫秒压在请求收尾上
            asyncCallLogWrites = true,
        )
        engine = created
        // 「隐藏多任务」保活：应用真正退到后台时把自己从最近任务列表移除。
        // 开关实时读取，用户改了立即生效，无需重新注册。
        dev.aigw.app.service.HideRecents.install(this) {
            dev.aigw.app.data.KeepAlivePrefs.hideRecentsEnabled(store)
        }
        // Shizuku / Sui 的 binder 获取：必须在主进程初始化一次。
        //
        // Shizuku（APK 形态）靠清单里的 ShizukuProvider 回调 binder；
        // 而 Sui 是 Magisk 模块（zygisk-sui），它**不走 ContentProvider**，
        // 必须主动调 Sui.init(packageName) 才会把 binder 递过来。
        // 不调这一句，pingBinder() 永远是 false —— 表现为「明明授权了却显示未授权」。
        //
        // 注意：Sui.init 只能调用一次，且要在任何 Shizuku API 之前完成。
        dev.aigw.app.service.ShizukuBoost.initBinder(this)
        getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_desc) },
        )
    }

    companion object {
        const val CHANNEL_ID = "aigw"
    }
}

/**
 * 取得进程内唯一的网关引擎。
 *
 * 早失败比拿到 null 后静默出错更容易定位。
 */
val Context.gatewayEngine: GatewayEngine
    get() {
        val app = applicationContext as AiGatewayApp
        return app.engine ?: error("网关引擎尚未初始化")
    }
