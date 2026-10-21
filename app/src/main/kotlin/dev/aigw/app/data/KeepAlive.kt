package dev.aigw.app.data

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dev.aigw.app.AiGatewayApp

/** 保活相关的系统状态快照。 */
data class KeepAliveStatus(
    /** 通知权限（API 33+ 需要申请，否则常驻通知不展示）。 */
    val notificationsGranted: Boolean,
    /** 是否已加入电池优化白名单（「不限制耗电」）。 */
    val batteryUnrestricted: Boolean,
    /** 系统是否把本应用限制了后台（API 28+）。 */
    val backgroundRestricted: Boolean,
    /** 是否开启了流量节省模式（可能影响后台联网）。 */
    val dataSaverRestricted: Boolean,
    /** 是否已允许「显示在其他应用上层」（可选的悬浮窗保活需要）。 */
    val overlayGranted: Boolean,
) {
    val allGood: Boolean
        get() = notificationsGranted && batteryUnrestricted && !backgroundRestricted && !dataSaverRestricted
}

/** 可选的保活开关（加密存储，服务与界面共读）。 */
object KeepAlivePrefs {
    private const val KEY_OVERLAY = "keepalive/overlay"
    private const val KEY_AUTOSTART = "keepalive/autostart"
    private const val KEY_HIDE_RECENTS = "keepalive/hide_recents"

    /**
     * 最后一次「用户期望的网关状态」：运行中=1，已停止=0。
     * 每次显式启停都落盘；进程被意外杀死时来不及改写，保留的仍是 1，
     * 这样开机（或保活拉起）后能据此恢复到上次的状态。
     */
    private const val KEY_DESIRED_RUNNING = "keepalive/desired_running"

    /**
     * 后台自动开启：退到后台连续 1 分钟后若网关没在跑就自动拉起。
     * 默认开启（用户 2026-10-21 要求该行为，但仍允许手动关掉）。
     */
    private const val KEY_BG_AUTOSTART = "keepalive/bg_autostart"

    /** 上次的网关状态；从未记录过（全新安装）返回 false。 */
    fun desiredRunning(store: dev.aigw.core.store.KeyValueStore): Boolean =
        store.read(KEY_DESIRED_RUNNING) == "1"

    /** 后台 1 分钟自动开启；未设置过按开启处理。 */
    fun bgAutoStartEnabled(store: dev.aigw.core.store.KeyValueStore): Boolean =
        store.read(KEY_BG_AUTOSTART) != "0"

    fun setBgAutoStartEnabled(store: dev.aigw.core.store.KeyValueStore, enabled: Boolean) {
        store.write(KEY_BG_AUTOSTART, if (enabled) "1" else "0")
    }

    /** null = 用户从未表过态（全新安装 / 装上还没开过）。 */
    fun desiredStateOrNull(store: dev.aigw.core.store.KeyValueStore): Boolean? =
        when (store.read(KEY_DESIRED_RUNNING)) {
            "1" -> true
            "0" -> false
            else -> null
        }

    fun setDesiredRunning(store: dev.aigw.core.store.KeyValueStore, running: Boolean) {
        store.write(KEY_DESIRED_RUNNING, if (running) "1" else "0")
    }

    fun overlayEnabled(store: dev.aigw.core.store.KeyValueStore): Boolean =
        store.read(KEY_OVERLAY) == "1"

    fun setOverlayEnabled(store: dev.aigw.core.store.KeyValueStore, enabled: Boolean) {
        store.write(KEY_OVERLAY, if (enabled) "1" else "0")
    }

    /** 开机自启：系统启动后自动把网关服务拉起来。 */
    fun autoStartEnabled(store: dev.aigw.core.store.KeyValueStore): Boolean =
        store.read(KEY_AUTOSTART) == "1"

    fun setAutoStartEnabled(store: dev.aigw.core.store.KeyValueStore, enabled: Boolean) {
        store.write(KEY_AUTOSTART, if (enabled) "1" else "0")
    }

    /** 隐藏多任务：离开应用后从最近任务列表移除，减少被一键清理的概率。 */
    fun hideRecentsEnabled(store: dev.aigw.core.store.KeyValueStore): Boolean =
        store.read(KEY_HIDE_RECENTS) == "1"

    fun setHideRecentsEnabled(store: dev.aigw.core.store.KeyValueStore, enabled: Boolean) {
        store.write(KEY_HIDE_RECENTS, if (enabled) "1" else "0")
    }
}

/** 保活相关的状态检测与系统设置跳转。 */
object KeepAlive {

    fun status(context: Context): KeepAliveStatus {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        return KeepAliveStatus(
            notificationsGranted = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            batteryUnrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true,
            backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                activity?.isBackgroundRestricted == true
            } else {
                false
            },
            dataSaverRestricted = connectivity?.restrictBackgroundStatus ==
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            overlayGranted = Settings.canDrawOverlays(context),
        )
    }

    /** 请求加入电池优化白名单（需在 Manifest 声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。 */
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    /** 电池优化设置页（用户拒绝直接授权时的兜底入口）。 */
    fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /**
     * 国产 ROM 的「自启动 / 后台运行」白名单页。
     *
     * 这些页面都是厂商私有的、未导出的组件，跳转失败是常态，所以逐个尝试，
     * 全部失败时由调用方回退到应用详情页（并提供手动路径说明）。
     */
    fun autoStartIntents(): List<Intent> = listOf(
        // 小米 / 红米
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // 华为 / 荣耀
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        // OPPO / 一加 / realme
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
        // vivo / iQOO
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        // 魅族 / 三星 / 联想
        ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ComponentName("com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity"),
    ).map { component ->
        Intent().apply {
            this.component = component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 厂商引导的手动路径提示，跳转失败时展示。 */
    fun manualHint(manufacturer: String): String = when {
        manufacturer.contains("xiaomi", true) || manufacturer.contains("redmi", true) ->
            "小米：设置 → 应用设置 → 应用管理 → 本应用 → 省电策略选「无限制」"
        manufacturer.contains("huawei", true) || manufacturer.contains("honor", true) ->
            "华为/荣耀：设置 → 应用 → 应用启动管理 → 本应用改为「手动管理」并勾选全部"
        manufacturer.contains("oppo", true) || manufacturer.contains("realme", true) || manufacturer.contains("oneplus", true) ->
            "OPPO/一加：设置 → 电池 → 更多设置 → 应用耗电管理 → 允许完全后台行为"
        manufacturer.contains("vivo", true) || manufacturer.contains("iqoo", true) ->
            "vivo/iQOO：设置 → 电池 → 后台高耗电 → 允许本应用"
        manufacturer.contains("meizu", true) ->
            "魅族：手机管家 → 权限管理 → 后台管理 → 允许后台运行"
        else ->
            "在系统设置的电池 / 应用管理里，把本应用的省电策略改为「无限制」并允许后台运行"
    }
}

/**
 * 「用户期望的网关状态」的唯一写入口。
 *
 * 同时落到两处：
 * 1. 加密存储（App 内读，用于开机自启判断 / 界面回显）
 * 2. 应用私有目录下的标记文件（Root 守护脚本是纯 shell，读不了加密存储，
 *    只能看文件在不在 —— 没有这个标记，用户主动关网关后 20 秒内就会被守护重新拉起来）
 */
object GatewayIntent {

    private const val FLAG_NAME = "gateway_on.flag"

    private val storeWriter: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "gateway-intent").apply { isDaemon = true }
        }

    fun flagFile(context: Context) = java.io.File(context.filesDir, FLAG_NAME)

    /** 文件标记是否存在（= 上次/当前网关处于开启状态）。 */
    fun isMarkedRunning(context: Context): Boolean =
        runCatching { flagFile(context.applicationContext).exists() }.getOrDefault(false)

    /**
     * 用户是否**明确**关过网关（点过「停止服务」）。
     *
     * 与「从未表态」严格区分：全新安装后切个后台就自动开启是合理的，
     * 但用户亲手关掉的，任何自动逻辑都不许把它拉回来。
     * 加密存储读不出来时退回文件标记（存在即视为没关过）。
     */
    fun isExplicitlyStopped(context: Context): Boolean {
        val appContext = context.applicationContext
        val app = appContext as? AiGatewayApp
        val read = runCatching { app?.let { KeepAlivePrefs.desiredStateOrNull(it.store) } }
        // 有三态，必须分清「有过明确表态」和「从未表态」：
        //   true/false → 用户开过/关过，直接采信
        val state = read.getOrNull()
        if (state != null) return !state
        // 存储读失败（未解锁 / 数据损坏）：退回文件标记，没标记就当关过，宁可不自动开
        if (read.isFailure) return !runCatching { flagFile(appContext).exists() }.getOrDefault(false)
        // 从未表态（全新安装，装上还没开过）：允许后台自动开启，否则功能等于默认失效
        return false
    }

    /**
     * 标记文件同步写（几字节，快），加密存储异步写：
     * 加密存储 commit() 是全量重加密 + fsync，几百毫秒，不能在服务启动/停止的主线程上做；
     * 而调用方经常紧接着要读状态，所以文件那一路必须立即生效。
     */
    fun markRunning(context: Context, running: Boolean) {
        val appContext = context.applicationContext
        runCatching {
            val f = flagFile(appContext)
            if (running) {
                if (!f.exists()) {
                    f.parentFile?.mkdirs()
                    f.writeText(System.currentTimeMillis().toString())
                }
            } else if (f.exists()) {
                f.delete()
            }
        }
        runCatching {
            val app = appContext as? AiGatewayApp ?: return@runCatching
            // 单线程串行化：连续快速开关时，后写的不能先落地
            storeWriter.execute {
                runCatching { KeepAlivePrefs.setDesiredRunning(app.store, running) }
                // 同步到 root 域（/data/adb/tyd_keepalive.state）：清 App 数据、重启后守护仍记得状态。
                // 端口一并下发，否则用户改端口后守护会一直探错端口（恒判「死」）。
                // ★ 放在这里是为了避开主线程：settings() 要解密存储，调用方
                //   （服务 onStartCommand、磁贴点击）都在主线程，直接读就是一次掉帧。
                val port = runCatching { app.engine?.settings()?.port ?: 0 }.getOrDefault(0)
                runCatching { dev.aigw.app.service.RootKeeper.syncState(running, port) }
            }
        }
    }
}
