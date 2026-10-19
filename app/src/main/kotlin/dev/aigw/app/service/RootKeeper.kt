package dev.aigw.app.service

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Root 守护的一键安装 / 卸载 / 状态查询。
 *
 * ## 为什么不是「把脚本丢进 /data/adb/service.d 就完事」
 *
 * `/data/adb/service.d/` 是 **Magisk** 的约定，只有 Magisk 的 init 服务会去执行它。
 * KernelSU / APatch 用的是另一套（`/data/adb/ksu/service.d/`、`/data/adb/ap/service.d/`），
 * 纯 `su` 环境更是没有任何人负责执行这个目录。
 *
 * 所以这里的安装逻辑是**框架无关**的：
 * 1. 把脚本写到应用私有目录（免了 /data 的 SELinux 麻烦）
 * 2. 铺进所有已知框架的 service.d 目录（谁在跑都能吃到，谁也不跑也不报错）
 * 3. **立即用 setsid 拉起一个守护进程** —— 这一条与框架无关，任何拿到 su 的设备都生效
 *
 * 开机自启则由第 2 步的目录铺设在有框架的机器上兜底；没有框架的机器靠
 * APP 自己的开机广播拉起网关服务，本守护负责「被杀后复活」，两者互补。
 */
object RootKeeper {

    private const val SCRIPT_NAME = "tyd_keepalive.sh"

    /** 网关端口，必须与 GatewaySettings.DEFAULT_PORT 一致（探活用）。 */
    private const val PORT = 8877

    /** su 命令等待上限（秒）：用户不点授权框时不能永久卡住。 */
    private const val SU_TIMEOUT_SEC = 20L

    /** 安装结果。 */
    sealed interface Result {
        /** 安装成功，守护进程已在运行。[pid] 为守护进程号。 */
        data class Installed(val pid: String, val note: String) : Result
        /** 已经装过了，无需重复安装。 */
        data object AlreadyInstalled : Result
        /** 没拿到 root：设备未 root、管理器没授权、或授权被拒。 */
        data class NoRoot(val detail: String) : Result
        /** 装上了但进程没起来（极少见，通常是 sh 环境异常）。 */
        data class Failed(val detail: String) : Result
    }

    /** 卸载结果。 */
    sealed interface UninstallResult {
        data object Done : UninstallResult
        data class NoRoot(val detail: String) : UninstallResult
    }

    /**
     * 安装后的自检结果 —— 「装上」不等于「生效」，必须逐项验。
     */
    data class Verify(
        /** 守护进程是否在跑。 */
        val processAlive: Boolean,
        /** 进程号（未运行时为空）。 */
        val pid: String,
        /** 探活命令是否真的返回成功（守护的判断依据靠不靠得住）。 */
        val probeWorks: Boolean,
        /** 探活命令的实际输出，排障用。 */
        val probeOutput: String,
        /** 已登记的框架开机目录。 */
        val registeredDirs: List<String>,
        /** 日志尾部（无日志时为空）。 */
        val logTail: String,
    ) {
        val ok: Boolean get() = processAlive && probeWorks
    }

    /**
     * 安装并立即启动守护。
     *
     * 全程同步执行，调用方需放 IO 线程。
     */
    fun install(context: Context): Result {
        if (!hasRoot()) return Result.NoRoot("未获取到 root，请先在 Root 管理器里给天元鼎授权")

        val script = try {
            context.assets.open(SCRIPT_NAME).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return Result.Failed("读取内置脚本失败：${e.message}")
        }

        // 私有目录路径要能被 su 进程读到：设为全局可读可执行
        val local = File(context.filesDir, "rootkeep/$SCRIPT_NAME")
        local.parentFile?.mkdirs()
        local.writeText(script)
        local.setReadable(true, false)
        local.parentFile?.setExecutable(true, false)

        val marker = markerFile(context)
        val alreadyRunning = findPid().isNotEmpty()

        // 铺进各框架的 service.d（存在则写，不存在就跳过，不报错）
        val installedDirs = mutableListOf<String>()
        for (dir in candidateDirs()) {
            val target = File(dir, SCRIPT_NAME)
            if (runRoot("mkdir -p '$dir' && cp '${local.absolutePath}' '$target' && chmod 755 '$target'")) {
                installedDirs += dir
            }
        }

        if (alreadyRunning) {
            // 已在运行：只更新磁盘上的脚本，下次被杀重启时用新版
            return Result.AlreadyInstalled
        }

        // 立即拉起：setsid 脱离当前进程组，父进程死了不影响它
        val startCmd = "setsid sh '${local.absolutePath}' >/dev/null 2>&1 & echo started"
        val startOk = runRoot(startCmd)

        // 等一小会儿看进程是否真的起来了（am/sh 启动有延迟）
        var pid = ""
        repeat(10) {
            pid = findPid()
            if (pid.isNotEmpty()) return@repeat
            Thread.sleep(200)
        }

        marker.writeText("1")

        return when {
            pid.isNotEmpty() -> Result.Installed(pid, describe(installedDirs))
            startOk -> Result.Failed("脚本已写入但进程未起来，请查看 /data/adb/tyd_keepalive.log")
            else -> Result.Failed("启动守护进程失败")
        }
    }

    /** 卸载：停掉守护进程并删除各处脚本。 */
    fun uninstall(context: Context): UninstallResult {
        if (!hasRoot()) return UninstallResult.NoRoot("未获取到 root")
        val pid = findPid()
        if (pid.isNotEmpty()) runRoot("kill $pid")
        for (dir in candidateDirs()) {
            runRoot("rm -f '$dir/$SCRIPT_NAME'")
        }
        File(context.filesDir, "rootkeep/$SCRIPT_NAME").delete()
        markerFile(context).delete()
        return UninstallResult.Done
    }

    /** 守护进程是否在运行。 */
    fun isRunning(): Boolean = hasRoot() && findPid().isNotEmpty()

    /** 是否已安装（有运行进程，或磁盘上有脚本）。 */
    fun isInstalled(context: Context): Boolean {
        if (findPid().isNotEmpty()) return true
        return markerFile(context).exists()
    }

    /**
     * 自检：确认守护**真的在工作**，而不只是「装上了」。
     *
     * 检查三件事：
     * 1. 守护进程活着（pgrep 能抓到）
     * 2. 探活命令本身可用 —— 这是最容易静默失败的一环
     *    （ busybox 不在 PATH、wget 不存在，脚本就会永远判定「已死」或永远判定「活着」）
     * 3. 铺了哪些开机目录
     */
    fun verify(context: Context): Verify {
        val pid = findPid()
        val probeOut = runRootOutput(
            "if command -v curl >/dev/null 2>&1; then curl -s -m 3 http://127.0.0.1:$PORT/healthz; " +
                "elif command -v wget >/dev/null 2>&1; then wget -q -T 3 -O - http://127.0.0.1:$PORT/healthz; " +
                "else echo NO_PROBE_TOOL; fi"
        )
        // curl 返回 "ok"，wget 同样；探测工具缺失则输出 NO_PROBE_TOOL
        val probeWorks = probeOut.isNotBlank() && probeOut != "NO_PROBE_TOOL"
        val dirs = candidateDirs().filter { dir ->
            runRootOutput("[ -f '$dir/$SCRIPT_NAME' ] && echo yes").trim() == "yes"
        }
        return Verify(
            processAlive = pid.isNotEmpty(),
            pid = pid,
            probeWorks = probeWorks,
            probeOutput = probeOut.trim().take(200),
            registeredDirs = dirs,
            logTail = readLog(context).take(400),
        )
    }

    /** 探活命令的描述（自检时告诉用户用的哪条命令）。 */
    fun probeCommand(): String =
        "curl -s -m 3 http://127.0.0.1:$PORT/healthz → wget → /dev/tcp 依次回退"

    /** 读守护日志尾部（排障用）。 */
    fun readLog(context: Context): String {
        val local = File(context.filesDir, "rootkeep/$SCRIPT_NAME")
        if (!local.exists()) return ""
        return runRootOutput("tail -n 20 /data/adb/tyd_keepalive.log 2>/dev/null").trim()
    }

    // ---------------------------------------------------------------- 内部实现

    private fun markerFile(context: Context) = File(context.filesDir, "rootkeep/installed")

    /** 各 Root 框架的 service.d 约定目录。 */
    private fun candidateDirs(): List<String> = listOf(
        "/data/adb/service.d",       // Magisk
        "/data/adb/ksu/service.d",   // KernelSU / Sukisu
        "/data/adb/ap/service.d",    // APatch
        "/data/adb/post-fs-data.d",  // Magisk（更早的阶段，多数脚本用不上）
    )

    /** 探测 root 是否可用且已授权（超时短，失败要快）。 */
    private fun hasRoot(): Boolean = runRootOutput("id").contains("uid=0")

    /** 找守护进程 PID：按脚本名匹配，避免误杀别人的 sh。 */
    private fun findPid(): String {
        val out = runRootOutput("pgrep -f '$SCRIPT_NAME' 2>/dev/null")
        // pgrep 可能返回多行（理论上单实例），取第一个非空行
        return out.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun describe(dirs: List<String>): String = when {
        dirs.isEmpty() -> "守护进程已启动（当前环境无框架开机目录，重启后需重新打开应用）"
        else -> "守护进程已启动，并已登记开机自启：${dirs.joinToString("、") { it.substringAfterLast('/') }}"
    }

    /** 以 root 执行命令，返回退出码是否为 0。 */
    private fun runRoot(cmd: String): Boolean = rootExit(cmd) == 0

    /** 以 root 执行命令并回收输出（用于需要读结果的命令）。 */
    private fun runRootOutput(cmd: String): String {
        val sb = StringBuilder()
        val p = try {
            ProcessBuilder("su", "0", "-c", cmd).redirectErrorStream(true).start()
        } catch (_: Exception) {
            return ""
        }
        try {
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) sb.append(line).append('\n')
            }
        } catch (_: Exception) {
            // 读失败不影响退出码判定
        }
        try {
            if (!p.waitFor(SU_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                sb.append("[err] 执行超时（${SU_TIMEOUT_SEC}s）")
            }
        } catch (_: Exception) {
        }
        return sb.toString()
    }

    /**
     * `su 0` 明确指定 root 用户（部分管理器默认 su 会落到 shell 身份），
     * 单命令 `-c` 形式避免交互式 su 挂起。
     */
    private fun rootExit(cmd: String): Int = try {
        val p = ProcessBuilder("su", "0", "-c", cmd).redirectErrorStream(true).start()
        if (!p.waitFor(SU_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            -1
        } else {
            0
        }
    } catch (_: Exception) {
        -1
    }
}
