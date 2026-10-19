package dev.aigw.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

    /** 用户服务版本：改了 [IShellProxy] 就要 +1，Shizuku 才会重建服务进程。 */
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

    fun currentState(): State = when {
        !Shizuku.pingBinder() -> State.NotRunning
        Shizuku.isPreV11() -> State.TooOld(Shizuku.getVersion())
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
    private fun probe(proxy: IShellProxy, name: String, cmd: String): Report.Item {
        val out = runCatching { proxy.exec(cmd) }.getOrElse { e ->
            return Report.Item(name, false, "调用失败：${e.messa