package dev.aigw.app.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 增强：让无 Root 的手机把系统压制等级调低。
 *
 * ## 身份与通道
 *
 * Shizuku 给的是 adb shell（uid 2000）身份。要执行 `am` / `cmd` 这类命令，
 * 靠 [ShellProxyService] 这个 UserService —— 它跑在 Shizuku 拉起的独立进程里，
 * 那里的 `Runtime.exec` 就是 adb 身份。
 *
 * ## 已在本机（Android 16 / API 36）逐条实测的子命令
 *
 * | 手段 | 命令 | 说明 |
 * |---|---|---|
 * | Doze 白名单 | `cmd deviceidle whitelist +<pkg>` | 深度睡眠豁免，**系统级最强** |
 * | Standby 分级 | `am set-standby-bucket <pkg> active` | Job/Alarm/FCM 频率 |
 * | 后台限制等级 | `am set-bg-restriction-level <pkg> unrestricted` | 对应设置里的开关 |
 * | 休眠豁免 | `am set-inactive <pkg> false` | 防长期不用被 hibernation 冻结 |
 * | 省流量白名单 | `cmd netpolicy add restrict-background-whitelist <uid>` | 省流量下后台联网 |
 *
 * 注意 `deviceidle` 的白名单语法是 `+包名` / `-包名`，
 * **没有** `whitelist add` 这种子命令（`cmd deviceidle` 的输出里确认过）。
 *
 * ## 与 Root 守护的分工
 *
 * - 本类：把系统压制开关一次性调到最有利
 * - [RootKeeper]：被杀后自动复活（只有 root 能做）
 *
 * Shizuku 每次重启需重新激活（无线调试授权会失效），所以它是「开机后点一次」。
 */
object ShizukuBoost {

    private const val TAG = "ShizukuBoost"
    private const val REQUEST_CODE = 0x5A17

    /** 用户服务版本：改了 exec 协议（[ShellProxyService]）就要 +1，Shizuku 才会重建服务进程。 */
    private const val PROXY_VERSION = 1

    /** UserService 的 tag，用于在多个进程里区分不同用途的代理。 */
    private const val PROXY_TAG = "tyd_shell_proxy"

    // ---------------------------------------------------------------- 状态

    sealed interface State {
        data object Ready : State
        data object NeedPermission : State
        data object NotRunning : State
        data class TooOld(val version: Int) : State
    }

    /** 增强结果，逐项报告，便于用户看到底哪条生效了。 */
    data class Report(val items: List<Item>) {
        val okCount: Int get() = items.count { it.ok }
        val allOk: Boolean get() = items.isNotEmpty() && items.all { it.ok }

        data class Item(val name: String, val ok: Boolean, val detail: String)
    }

    /** 是否走的是 Sui（Magisk 模块形态），而不是 Shizuku APK。 */
    @Volatile
    private var suiMode = false

    /** 初始化过一次就不再重复（Sui.init 规定只能调一次）。 */
    @Volatile
    private var binderInitialized = false

    /**
     * 获取 Shizuku / Sui 的 binder —— **必须在任何 Shizuku API 之前调用一次**。
     *
     * 两条路径完全不同：
     * - **Shizuku**（APK）：清单里的 `ShizukuProvider` 在进程启动时把 binder 递过来，无需主动调用。
     * - **Sui**（Magisk 模块，zygisk-sui）：**不走 ContentProvider**，必须主动
     *   `Sui.init(packageName)`，它才会通过 binder 事务把服务交出来。
     *
     * 之前只声明了 ShizukuProvider 就以为万事大吉，结果在 Sui 设备上
     * `pingBinder()` 恒为 false，UI 永远显示「未授权」—— 就是这个原因。
     *
     * @return true 表示拿到了 binder（Sui 或 Shizuku 均可）
     */
    fun initBinder(context: Context): Boolean {
        if (binderInitialized) return Shizuku.pingBinder()
        binderInitialized = true
        // Sui 先试：它是模块形态，没有 ContentProvider 兜底，不主动拿就永远拿不到。
        // Sui.init 自带判断，没装 Sui 时直接返回 false，不会抛异常。
        val suiOk = runCatching { rikka.sui.Sui.init(context.packageName) }
            .onFailure { Log.w(TAG, "Sui.init 失败", it) }
            .getOrDefault(false)
        suiMode = suiOk
        if (suiOk) {
            Log.i(TAG, "已通过 Sui 获得 binder（模块形态）")
            return true
        }
        // 再看 Shizuku 的 provider 是否已经把 binder 递过来了
        return Shizuku.pingBinder()
    }

    /** 当前是 Sui 还是 Shizuku，用于界面提示。 */
    fun isSui(): Boolean = suiMode && rikka.sui.Sui.isSui()

    fun currentState(): State = when {
        !Shizuku.pingBinder() -> State.NotRunning
        // Sui 的版本号语义与 Shizuku 不同：Sui 不提供 isPreV11 的可信结果，
        // 能拿到 binder 就说明是可用的新版本，跳过这层判断。
        !suiMode && Shizuku.isPreV11() -> State.TooOld(Shizuku.getVersion())
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.Ready
        else -> State.NeedPermission
    }

    fun requestPermission() {
        if (Shizuku.shouldShowRequestPermissionRationale()) return
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { Log.w(TAG, "requestPermission 失败", it) }
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 一次性把保活相关的系统开关全部调到最有利。
     *
     * 同步方法，调用方放 IO 线程。每项独立成败，一项失败不影响其它项。
     */
    fun boost(context: Context): Report {
        if (currentState() != State.Ready) {
            return Report(listOf(Report.Item("Shizuku", false, stateHint(currentState()))))
        }
        val pkg = context.packageName
        val uid = context.applicationInfo.uid

        val results = mutableListOf<Report.Item>()
        withProxy(context) { proxy ->
            results += probe(proxy, "Doze 白名单", "cmd deviceidle whitelist +$pkg")
            results += probe(proxy, "Standby 分级", "am set-standby-bucket $pkg active")
            // Android 15+ 才有这个子命令，低版本会报 Unknown command —— 失败不影响其它项
            results += probe(proxy, "后台限制等级", "am set-bg-restriction-level $pkg unrestricted")
            results += probe(proxy, "休眠豁免", "am set-inactive $pkg false")
            results += probe(proxy, "省流量白名单", "cmd netpolicy add restrict-background-whitelist $uid")
        }
        if (results.isEmpty()) {
            results += Report.Item("Shizuku", false, "无法启动代理服务")
        }
        return Report(results)
    }

    /**
     * 只读体检：把当前各开关的**真实状态**读出来。
     *
     * 这一层很重要 —— `boost()` 只证明「命令发出去了」，不证明「系统接受了」。
     * 开机自检和「确认生效」都应该看这里的读数。
     */
    fun inspect(context: Context): Report {
        if (currentState() != State.Ready) {
            return Report(listOf(Report.Item("Shizuku", false, stateHint(currentState()))))
        }
        val pkg = context.packageName
        val items = mutableListOf<Report.Item>()
        withProxy(context) { proxy ->
            val whitelistOut = proxy.exec("dumpsys deviceidle whitelist")
            val inWhitelist = whitelistOut.contains(pkg)
            items += Report.Item("Doze 白名单", inWhitelist, if (inWhitelist) "已在名单内" else "不在名单内")

            val inactive = proxy.exec("am get-inactive $pkg").trim()
            items += Report.Item("休眠状态", inactive == "false", "inactive=$inactive（false 为佳）")

            val bucket = proxy.exec("am get-standby-bucket $pkg").trim()
            val bucketOk = bucket == "10"
            items += Report.Item(
                "Standby 分级",
                bucketOk,
                "bucket=$bucket（10=active 最优，5=restricted 最差）",
            )

            val restriction = proxy.exec("am get-bg-restriction-level $pkg").trim()
            items += Report.Item(
                "后台限制等级",
                restriction.isEmpty() || restriction == "unrestricted",
                if (restriction.isEmpty()) "设备不支持该命令" else "当前=$restriction",
            )
        }
        if (items.isEmpty()) items += Report.Item("Shizuku", false, "无法启动代理服务")
        return Report(items)
    }

    /** 撤销增强，把开关恢复默认。 */
    fun revert(context: Context): Report {
        if (currentState() != State.Ready) {
            return Report(listOf(Report.Item("Shizuku", false, stateHint(currentState()))))
        }
        val pkg = context.packageName
        val items = mutableListOf<Report.Item>()
        withProxy(context) { proxy ->
            items += probe(proxy, "移除 Doze 白名单", "cmd deviceidle whitelist -$pkg")
            items += probe(proxy, "恢复 Standby 分级", "am set-standby-bucket $pkg frequent")
            items += probe(proxy, "恢复休眠判定", "am set-inactive $pkg true")
        }
        if (items.isEmpty()) items += Report.Item("Shizuku", false, "无法启动代理服务")
        return Report(items)
    }

    // ---------------------------------------------------------------- 内部

    private fun stateHint(state: State): String = when (state) {
        State.Ready -> "已就绪"
        State.NeedPermission -> "未授权：请先允许 Shizuku 权限"
        State.NotRunning -> "Shizuku 未运行：请先开启无线调试并启动 Shizuku"
        is State.TooOld -> "Shizuku 版本过低（v${state.version}），需要 11+"
    }

    /**
     * 跑一条命令并把「是否真的成功」判断出来。
     *
     * am/cmd 失败时通常往 stderr 抛 Exception 或报 Unknown command，
     * 单看退出码不可靠（部分子命令失败也返回 0），所以同时检查输出内容。
     */
    private fun probe(proxy: Exec, name: String, cmd: String): Report.Item {
        val out = runCatching { proxy.exec(cmd) }.getOrElse { e ->
            return Report.Item(name, false, "调用失败：${e.message?.take(100)}")
        }.trim()
        val failed = out.contains("Exception", true) ||
            out.contains("Error:", true) ||
            out.contains("Unknown command", true) ||
            out.contains("not found", true) ||
            out.contains("Permission denied", true)
        return Report.Item(name, !failed, if (failed) out.take(150) else "已执行")
    }

    private fun withProxy(context: Context, block: (Exec) -> Unit) {
        if (currentState() != State.Ready) return
        val component = ComponentName(context.packageName, ShellProxyService::class.java.name)
        val args = Shizuku.UserServiceArgs(component)
            .daemon(false)
            .version(PROXY_VERSION)
            .tag(PROXY_TAG)

        val latch = java.util.concurrent.CountDownLatch(1)
        var remote: Messenger? = null

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                remote = service?.let { Messenger(it) }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                latch.countDown()
            }
        }

        try {
            Shizuku.bindUserService(args, conn)
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.w(TAG, "绑定 UserService 超时")
            }
            val m = remote
            if (m != null) {
                block(Shell(m))
                return
            }
        } catch (e: Throwable) {
            Log.w(TAG, "UserService 执行失败", e)
        } finally {
            runCatching { Shizuku.unbindUserService(args, conn, true) }
        }
        // Sui 不提供 UserService（官方 API 里没有 bindUserService 的实现），
        // 拿不到代理时退回 root：Sui 本就是 Magisk 模块，su 一定可用。
        if (RootShell.available()) {
            block(RootShell)
        } else {
            Log.w(TAG, "既没有 UserService，也没有可用的 su")
        }
    }
}

/** 执行通道的统一抽象：Shizuku 走 UserService，Sui 走 root。 */
private interface Exec {
    fun exec(command: String): String
}

private class Shell(private val messenger: Messenger) : Exec {
    override fun exec(command: String): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var output = ""

        val replyTo = Messenger(Handler(Looper.getMainLooper()) { msg ->
            output = msg.data.getString(ShellProxyService.KEY_OUT).orEmpty()
            latch.countDown()
            true
        })

        val request = Message.obtain().apply {
            what = ShellProxyService.WHAT_EXEC
            data = Bundle().apply { putString(ShellProxyService.KEY_CMD, command) }
            this.replyTo = replyTo
        }

        try {
            messenger.send(request)
        } catch (e: Throwable) {
            return "发送失败：${e.message}"
        }
        if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) return "等待结果超时"
        return output
    }
}

/**
 * 以 root 身份执行命令的回退通道。
 *
 * Sui 是 Magisk 模块形态，`su` 一定在；Shizuku 在已 root 的设备上也同样可用。
 * 用 `su 0 -c` 明确指定 root 用户，并用 `-c` 单命令形式避免交互式 su 挂起。
 */
private object RootShell : Exec {

    @Volatile
    private var checked = false

    @Volatile
    private var usable = false

    /** su 是否可用（只探测一次，结果缓存）。 */
    fun available(): Boolean {
        if (checked) return usable
        checked = true
        usable = runCatching {
            val p = ProcessBuilder("su", "0", "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            out.contains("uid=0")
        }.getOrDefault(false)
        return usable
    }

    override fun exec(command: String): String = runCatching {
        val p = ProcessBuilder("su", "0", "-c", command).redirectErrorStream(false).start()
        val sb = StringBuilder()
        p.inputStream.bufferedReader().use { r ->
            var line: String?
            while (r.readLine().also { line = it } != null) sb.append(line).append('\n')
        }
        p.errorStream.bufferedReader().use { r ->
            var line: String?
            while (r.readLine().also { line = it } != null) sb.append("[err] ").append(line).append('\n')
        }
        p.waitFor()
        p.destroy()
        sb.toString()
    }.getOrElse { "执行失败：${it.message}" }
}
