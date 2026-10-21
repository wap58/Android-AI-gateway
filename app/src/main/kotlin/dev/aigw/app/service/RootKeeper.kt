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

    /** 守护脚本写日志的固定路径（与 assets/tyd_keepalive.sh 里的 LOG 保持一致）。 */
    private const val LOG_PATH = "/data/adb/tyd_keepalive.log"

    /**
     * 守护状态真值（root 域，重启 / 清 App 数据都不丢）：
     * on = 用户希望网关开着，off = 用户主动停止。
     * App 每次启停都尝试改写；没有 root 时守护退回读 App 私有目录的标记文件。
     */
    private const val STATE_PATH = "/data/adb/tyd_keepalive.state"

    /** su 命令等待上限（秒）：用户不点授权框时不能永久卡住。 */
    private const val SU_TIMEOUT_SEC = 20L

    /** su 探测缓存：null = 还没探过。 */
    @Volatile
    private var suCache: Boolean? = null
    @Volatile
    private var suCheckedAt = 0L

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
        /** 识别到的 Root 方案（Magisk / KernelSU-Sukisu / APatch / 纯 su）。 */
        val rootFlavor: String = "未知",
        /** 日志尾部（无日志时为空）。 */
        val logTail: String,
        /** 守护记录的期望状态：true=会保活，false=用户主动停止，null=未记录。 */
        val desiredRunning: Boolean? = null,
        /** 状态文件路径（界面提示用）。 */
        val statePath: String = STATE_PATH,
        /**
         * 当前设备是否真的能拿到 root（su 能返回 uid=0）。
         * 界面据此决定是否显示 Root 卡片：拿不到 root 时整块隐藏，避免一堆永远红色的未通过项。
         */
        val suAvailable: Boolean = false,
        /** 磁盘上正在运行的守护脚本版本（脚本内 VER 变量）；空表示读取失败。 */
        val scriptVersion: String = "",
        /** assets 里随 APK 附带的版本；与 [scriptVersion] 不一致说明需要重装。 */
        val bundledVersion: String = "",
    ) {
        /** 磁盘脚本是否落后于 APK（需要用户点一次「重装」）。 */
        val outdated: Boolean
            get() = scriptVersion.isNotEmpty() && bundledVersion.isNotEmpty() &&
                scriptVersion != bundledVersion

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
        val dirs = serviceDirs()
        // 先清掉上一版「四个目录全写」留下的副本，避免多个守护进程互相抢
        cleanupStrayDirs(dirs)
        val installedDirs = mutableListOf<String>()
        for (dir in dirs) {
            val target = File(dir, SCRIPT_NAME)
            if (runRoot("mkdir -p '$dir' && cp '${local.absolutePath}' '$target' && chmod 755 '$target'")) {
                installedDirs += dir
            }
        }

        if (alreadyRunning) {
            // 已在运行：先停掉旧进程再重启一次。
            // 脚本逻辑会随版本变化（例如「用户主动停止后不拉起」的判断），
            // 只更新磁盘上的副本不动进程，用户要等到下次重启才生效——体验上等于没修。
            val oldPid = findPid()
            if (oldPid.isNotEmpty()) {
                runRoot("kill $oldPid")
                for (i in 0 until 15) {
                    if (findPid().isEmpty()) break
                    Thread.sleep(200)
                }
            }
        }

        // 立即拉起：setsid 脱离当前进程组，父进程死了不影响它
        val startCmd = "setsid sh '${local.absolutePath}' >/dev/null 2>&1 & echo started"
        val startOk = runRoot(startCmd)

        // 等一小会儿看进程是否真的起来了（am/sh 启动有延迟）
        var pid = ""
        // 轮询等进程起来：拿到 PID 就立刻结束等待（return@repeat 只会跳过本轮，不能提前退出）
        for (i in 0 until 10) {
            pid = findPid()
            if (pid.isNotEmpty()) break
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
        for (dir in serviceDirs()) {
            runRoot("rm -f '$dir/$SCRIPT_NAME'")
        }
        // 顺带清掉可能残留在其它框架目录里的副本
        cleanupStrayDirs(emptyList())
        File(context.filesDir, "rootkeep/$SCRIPT_NAME").delete()
        markerFile(context).delete()
        return UninstallResult.Done
    }

    /**
     * 把「用户期望状态」写进 root 域状态文件，让守护在重启后也知道该不该恢复。
     *
     * 异步、失败静默：没有 root 时守护仍能读 App 私有目录的标记文件，
     * 这里失败不影响功能，只是少了「清数据后仍记得」这一层保障。
     */
    fun syncState(running: Boolean) {
        Thread({
            runCatching {
                if (!hasRoot()) return@runCatching
                runRoot("echo ${if (running) "on" else "off"} > $STATE_PATH && chmod 644 $STATE_PATH")
            }
        }, "rootkeep-state").apply { isDaemon = true }.start()
    }

    /** 读取守护侧记录的期望状态；null 表示尚未记录或读不到。 */
    fun readState(): Boolean? {
        val out = runRootOutput("cat $STATE_PATH 2>/dev/null").trim()
        return when (out) {
            "on" -> true
            "off" -> false
            else -> null
        }
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
    fun verify(context: Context, forceSuCheck: Boolean = false): Verify {
        // 拿不到 root 就直接返回「不支持」，不往下跑任何 su 命令：
        // 否则每次轮询都会起好几个 su 子进程（非 root 设备上每个都要等失败），白白耗电。
        if (!suAvailable(force = forceSuCheck)) {
            return Verify(
                processAlive = false,
                pid = "",
                probeWorks = false,
                probeOutput = "",
                registeredDirs = emptyList(),
                rootFlavor = "未知",
                logTail = "",
                desiredRunning = null,
                suAvailable = false,
                scriptVersion = "",
                bundledVersion = bundledScriptVersion(context),
            )
        }
        val pid = findPid()
        // 探活直接调用守护脚本自己的 probe 模式。
        //
        // ★ 为什么不再在 Kotlin 里另写一套判断：
        //   之前 App 端是 `curl || wget || /dev/tcp`，而 APatch 设备上
        //   curl 不存在、busybox 不在 PATH、/dev/tcp 是 mksh 不支持的语法 ——
        //   三条全挂，界面报「探活通道不可用」，而守护内部用的是另一套（有 /proc 兜底）
        //   所以它其实好好活着。两套判断不一致 → 用户看到「都有 root 了居然还没生效」。
        //   现在两边共用同一个实现，界面显示的就是守护真实的判定依据。
        val probeOut = runRootOutput(probeCommand(context))
        val probeWorks = probeOut.isNotBlank() &&
            probeOut.trim() != "FAIL" &&
            !probeOut.contains("[err]")
        val dirs = serviceDirs().filter { dir ->
            runRootOutput("[ -f '$dir/$SCRIPT_NAME' ] && echo yes").trim() == "yes"
        }
        return Verify(
            processAlive = pid.isNotEmpty(),
            pid = pid,
            probeWorks = probeWorks,
            probeOutput = probeOut.trim().take(200),
            registeredDirs = dirs,
            rootFlavor = rootFlavor(),
            logTail = readLog(context).take(400),
            desiredRunning = readState(),
            suAvailable = true,
            scriptVersion = readScriptVersion(context),
            bundledVersion = bundledScriptVersion(context),
        )
    }

    /**
     * 探活命令：调用守护脚本的 `probe` 模式，输出它实际用的通道名（或 FAIL）。
     *
     * 用 `sh <脚本> probe` 而不是把判定逻辑内联成一行：
     * 内联写法要穿过 `su 0 -c` 的一道引号，多层转义很容易失效
     * （曾出现「su 下手动跑正常、App 里却拿到空输出」）；
     * 而脚本文件是现成的、逻辑与守护完全一致，不存在两边漂移。
     */
    internal fun probeCommand(context: Context): String =
        "sh '${ensureLocalScript(context).absolutePath}' probe 2>/dev/null"

    /**
     * 确保本机有一份可执行/可读的守护脚本副本（自检探活用）。
     * 已安装时由 install() 写入；未安装时也从 assets 取一份，保证自检可用。
     */
    private fun ensureLocalScript(context: Context): File {
        val local = File(context.filesDir, "rootkeep/$SCRIPT_NAME")
        if (local.exists() && local.length() > 0) return local
        runCatching {
            local.parentFile?.mkdirs()
            context.assets.open(SCRIPT_NAME).bufferedReader().use { r ->
                local.writeText(r.readText())
            }
            local.setReadable(true, false)
            local.parentFile?.setExecutable(true, false)
        }
        return local
    }

    /**
     * 磁盘上正在运行的守护脚本版本。
     *
     * 以 app 私有目录的副本为准（install 每次都覆盖它，重装后立即准确），
     * 读不到再退回各框架目录里铺的那份。
     */
    private fun readScriptVersion(context: Context): String {
        val local = File(context.filesDir, "rootkeep/$SCRIPT_NAME")
        if (local.exists()) {
            val parsed = parseVer(
                runCatching { local.readLines().firstOrNull { it.startsWith("VER=") }.orEmpty() }
                    .getOrDefault(""),
            )
            if (parsed.isNotEmpty()) return parsed
        }
        // 框架目录按 rootFlavor 决定，这里一并尝试已知位置
        val out = runRootOutput(
            "grep -m1 '^VER=' /data/adb/service.d/$SCRIPT_NAME " +
                "/data/adb/ksu/service.d/$SCRIPT_NAME /data/adb/ap/service.d/$SCRIPT_NAME 2>/dev/null",
        )
        return parseVer(out)
    }

    /** APK 内置脚本的版本（assets）。 */
    private fun bundledScriptVersion(context: Context): String {
        val lines = runCatching {
            context.assets.open(SCRIPT_NAME).bufferedReader().use { r -> r.readLines() }
        }.getOrDefault(emptyList())
        return parseVer(lines.firstOrNull { it.startsWith("VER=") }.orEmpty())
    }

    private fun parseVer(line: String): String =
        line.substringAfter("VER=", "").trim().trim('"').take(40)

    /** 读守护日志尾部（排障用）。 */
    fun readLog(context: Context): String {
        val local = File(context.filesDir, "rootkeep/$SCRIPT_NAME")
        if (!local.exists()) return ""
        return runRootOutput("tail -n 20 $LOG_PATH 2>/dev/null").trim()
    }

    /** 读完整日志（「更多」弹窗用）：最多取末尾 200 行，避免超大文件把弹窗撑爆。 */
    fun readFullLog(context: Context): String {
        val local = File(context.filesDir, "rootkeep/$SCRIPT_NAME")
        if (!local.exists()) return ""
        return runRootOutput("tail -n 200 $LOG_PATH 2>/dev/null").trim()
    }

    /**
     * 清空守护日志。
     *
     * 用 `: > file` 而不是 `rm`：守护进程还持有该文件的写入句柄，
     * 删掉后它继续往已 unlink 的 inode 写，日志会「消失」但磁盘空间不释放。
     * 截断则保持 inode 不变，守护下一行写入自然接上。
     */
    fun clearLog(): Boolean = runRoot(": > $LOG_PATH")

    // ---------------------------------------------------------------- 内部实现

    private fun markerFile(context: Context) = File(context.filesDir, "rootkeep/installed")

    /**
     * 探测当前实际使用的 Root 方案，只返回**它自己**的开机脚本目录。
     *
     * 不能把四个目录全写一遍：
     * - `mkdir -p` 会凭空造出 `/data/adb/ap/` 这种空目录，看着像装了 APatch；
     * - 同一份脚本被执行多次，会出现多个守护进程互相抢着拉起服务。
     *
     * 判据用「框架自己的标志文件」，而不是「目录是否存在」——
     * 因为空目录可能是我们上一次 `mkdir -p` 留下的。
     */
    private fun serviceDirs(): List<String> {
        val out = runRootOutput(
            "if [ -f /data/adb/magisk/magisk ]; then echo magisk; " +
                "elif [ -d /data/adb/ksu/bin ]; then echo ksu; " +
                "elif [ -d /data/adb/ap/bin ]; then echo apatch; " +
                "else echo none; fi"
        ).trim().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        return when (out) {
            // Magisk：两个阶段都受支持，用 service.d（晚启动，系统服务已就绪）
            "magisk" -> listOf("/data/adb/service.d")
            // KernelSU / Sukisu：官方约定就是 ksu/service.d
            "ksu" -> listOf("/data/adb/ksu/service.d")
            // APatch 执行的是 Magisk 兼容目录 /data/adb/service.d，
            // 不是 /data/adb/ap/service.d（那个目录只放 APatch 自己的二进制）。
            // 早先写成 ap/service.d 时，脚本倒是铺进去了，但开机根本没人执行它。
            "apatch" -> listOf("/data/adb/service.d")
            else -> emptyList()
        }
    }

    /** 当前 Root 方案的展示名，用于界面提示。 */
    fun rootFlavor(): String {
        val out = runRootOutput(
            "if [ -f /data/adb/magisk/magisk ]; then echo Magisk; " +
                "elif [ -d /data/adb/ksu/bin ]; then echo KernelSU/Sukisu; " +
                "elif [ -d /data/adb/ap/bin ]; then echo APatch; " +
                "else echo 纯 su; fi"
        ).trim().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        return out.ifEmpty { "未知" }
    }

    /** 清理上一版铺错位置的脚本（四个目录全写那版留下的）。 */
    private fun cleanupStrayDirs(keep: List<String>) {
        val all = listOf(
            "/data/adb/service.d",
            "/data/adb/ksu/service.d",
            "/data/adb/ap/service.d",
            "/data/adb/post-fs-data.d",
        )
        for (dir in all) {
            if (dir in keep) continue
            runRoot("rm -f '$dir/$SCRIPT_NAME'")
        }
    }

    /** 探测 root 是否可用且已授权（超时短，失败要快）。 */
    private fun hasRoot(): Boolean = suAvailable(force = true)

    /** su 探测结果的缓存时间：页面每 3 秒轮询一次，没必要每次都起 su 进程。 */
    private const val SU_CACHE_MS = 30_000L

    /**
     * 当前能否拿到 root（su 返回 uid=0）。
     *
     * 用 3 秒短超时，而不是通用的 20 秒：没 root 的设备上 `su` 可能不存在（立刻失败），
     * 也可能存在但会弹授权框并挂住 —— 两种情况都不该让界面卡 20 秒。
     */
    fun suAvailable(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val cached = suCache
        if (!force && cached != null && now - suCheckedAt < SU_CACHE_MS) return cached
        val ok = runSuQuick("id").trim().contains("uid=0")
        suCache = ok
        suCheckedAt = now
        return ok
    }

    /** 以 root 跑一条短命令并回收输出，超时 3 秒即放弃（拿不到 root 的场景用）。 */
    private fun runSuQuick(cmd: String): String {
        val p = try {
            ProcessBuilder("su", "0", "-c", cmd).redirectErrorStream(true).start()
        } catch (_: Exception) {
            return ""
        }
        val sb = StringBuilder()
        try {
            p.inputStream.bufferedReader().use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) sb.append(line).append('\n')
            }
        } catch (_: Exception) {
        }
        return try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                ""
            } else {
                sb.toString()
            }
        } catch (_: Exception) {
            runCatching { p.destroyForcibly() }
            ""
        }
    }

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
