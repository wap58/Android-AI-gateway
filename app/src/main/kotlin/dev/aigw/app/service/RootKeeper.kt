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

    /** 自检探针：一次 su 调用取回全部守护状态（见 assets/rootkeep_probe.sh）。 */
    private const val PROBE_SCRIPT_NAME = "rootkeep_probe.sh"

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
            // 把包名与私有目录注入脚本：debug 包的 applicationId 带 .debug 后缀，
            // 脚本里写死 dev.tyd.app 的话，pidof / pm path / 标记文件路径全都会对不上，
            // 表现为「装了守护但完全没生效」，而 App 侧自检却显示正常。
            personalize(context, context.assets.open(SCRIPT_NAME).bufferedReader().use { it.readText() })
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
        // 先清掉上一版「四个目录全写」留下的副本，避免多个守护进程互相抢。
        // 但没有框架目录时不能清：那会把用户手工放进 service.d 的脚本一起删掉，
        // 一次「安装」变成一次「卸载」。
        if (dirs.isNotEmpty()) cleanupStrayDirs(dirs)
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
            for (oldPid in findPids()) runRoot("kill $oldPid")
            // 等它们真的退出再起新的，否则新旧脚本会同时 kick
            for (i in 0 until 15) {
                if (findPids().isEmpty()) break
                Thread.sleep(200)
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

        return when {
            pid.isNotEmpty() -> {
                // 只有进程真的起来了才写「已安装」标记：
                // 早先无条件写，导致装失败时按钮也变成「重装」，用户以为装好了
                marker.writeText("1")
                Result.Installed(pid, describe(installedDirs))
            }
            startOk -> Result.Failed("脚本已写入但进程未起来，请查看 /data/adb/tyd_keepalive.log")
            else -> Result.Failed("启动守护进程失败")
        }
    }

    /** 卸载：停掉守护进程并删除各处脚本。 */
    fun uninstall(context: Context): UninstallResult {
        if (!hasRoot()) return UninstallResult.NoRoot("未获取到 root")
        for (pid in findPids()) runRoot("kill $pid")
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
    fun syncState(running: Boolean, port: Int = 0, autoStart: Boolean = true) {
        // ★ 收敛到单线程串行执行 + 只写最后一次意图：
        // 之前每次调用都起一个新线程，快速「开关」几次会出现
        // off 先落地、on 后落地的倒序写入，最终状态和用户看到的不一致。
        lastState = running
        stateWriter.execute {
            val want = lastState ?: running
            runCatching {
                if (!hasRoot()) return@execute
                // 端口一起下发：端口是用户可改的运行时配置，脚本若写死 8877，
                // 用户改成 9000 后探活永远失败 —— 守护会一直把「活着的网关」当死的拉，
                // 界面还显示「探活通道不可用」，看起来像 root 环境坏了。
                // 三段：状态 / 端口 / 是否允许开机恢复。
                // autoStart 也一起下发，否则用户关掉「开机自启」后，
                // Magisk/KSU 的 service.d 里的守护仍会按 state=on 把网关拉起来，
                // 界面显示「已关闭」而网关在跑，两套策略互相打脸。
                val value = if (want) {
                    "on ${if (port > 0) port.toString() else ""} ${if (autoStart) 1 else 0}"
                        .replace("  ", " ")
                } else {
                    "off"
                }
                runRoot("echo '$value' > $STATE_PATH && chmod 644 $STATE_PATH")
            }
        }
    }

    /** 串行写状态文件，保证「最后一次操作」必定是最后落地的那次。 */
    private val stateWriter: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "rootkeep-state").apply { isDaemon = true }
        }

    @Volatile
    private var lastState: Boolean? = null

    /** 读取守护侧记录的期望状态；null 表示尚未记录或读不到。 */
    fun readState(): Boolean? {
        val out = runRootOutput("cat $STATE_PATH 2>/dev/null").trim()
        return when {
            out.startsWith("on") -> true
            out == "off" -> false
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

        // ★ 全部状态用**一次** su 调用取回（assets/rootkeep_probe.sh 输出 KEY=VALUE）。
        // 之前这里是 7 次独立 su：守护页每 3 秒轮询一轮，等于每秒 2 个 su 进程，
        // 锁屏时授权排队会直接卡住界面，电量也顶不住。
        val script = ensureLocalScript(context)
        val raw = runRootOutput("sh '${script.absolutePath}' " +
            "'${ensureProbeScript(context).absolutePath}' 2>/dev/null")
        val fields = raw.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()

        val pid = fields["PID"].orEmpty()
        val flavorRaw = fields["FLAVOR"].orEmpty()
        val probeRaw = fields["PROBE"].orEmpty()
        val stateRaw = fields["STATE"].orEmpty()
        val dirs = fields["DIR"].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // 探活成功的判据：脚本输出的是通道名（含 curl / busybox / toybox / proc / tcp），
        // 只有明确 FAIL 或空才算不可用。
        val probeWorks = probeRaw.isNotEmpty() && probeRaw != "FAIL"

        return Verify(
            processAlive = pid.isNotEmpty(),
            pid = pid,
            probeWorks = probeWorks,
            probeOutput = probeRaw.take(200),
            registeredDirs = dirs,
            rootFlavor = flavorRaw.ifEmpty { "未知" },
            logTail = readLog(context).take(400),
            desiredRunning = when (stateRaw) {
                "on" -> true
                "off" -> false
                else -> null
            },
            suAvailable = true,
            // 以磁盘上真正在跑的那份为准（probe 脚本读的是它），APK 内置版本用于比对
            scriptVersion = fields["VER"].orEmpty(),
            bundledVersion = bundledScriptVersion(context),
        )
    }

    /**
     * 把脚本里的包名/路径占位符换成当前安装的真实值。
     *
     * 只在 install 时做：注入后的脚本会随备份/复用到其它地方，但脚本本身
     * 从来不会被跨设备共享，所以不需要更复杂的机制。
     */
    private fun personalize(context: Context, raw: String): String = raw
        .replace("PKG=\"dev.tyd.app\"", "PKG=\"${context.packageName}\"")
        .replace("SVC=\"dev.tyd.app/", "SVC=\"${context.packageName}/")
        .replace("FLAG_DIR=/data/user/0/dev.tyd.app/files", "FLAG_DIR=${context.filesDir.absolutePath}")

    /** 确保探针脚本也落在私有目录（su 才读得到）。 */
    private fun ensureProbeScript(context: Context): File {
        val local = File(context.filesDir, "rootkeep/$PROBE_SCRIPT_NAME")
        if (local.exists() && local.length() > 0) return local
        runCatching {
            local.parentFile?.mkdirs()
            context.assets.open(PROBE_SCRIPT_NAME).bufferedReader().use { r ->
                local.writeText(r.readText())
            }
            local.setReadable(true, false)
        }
        return local
    }

    // 探活与脚本版本读取已并入 verify() 的单次 su 调用（assets/rootkeep_probe.sh），
    // 不再需要独立的 probeCommand / readScriptVersion。

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
                // 与 install 一样注入真实包名：否则 debug 包上自检探的是 release 包，
                // 结论完全对不上（界面说未运行，实际跑着）
                local.writeText(personalize(context, r.readText()))
            }
            local.setReadable(true, false)
            local.parentFile?.setExecutable(true, false)
        }
        return local
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
    /**
     * 全部守护进程 PID。
     *
     * 多实例是真实场景：`service.d` 开机拉起的那份 + 用户手动点「安装」用 setsid 拉起的那份
     * 会同时存在（路径不同、pgrep -f 都能匹配）。只杀第一个就会留下一个继续 kick，
     * 表现为「卸载了还在复活」。
     */
    private fun findPids(): List<String> =
        // 锚定行尾：自检探针会以 `sh tyd_keepalive.sh probe` 形式启动，
        // 不锚定的话那一瞬间会被误认成守护进程（进程明明死了却显示「运行中」）。
        runRootOutput("pgrep -f '$SCRIPT_NAME\$' 2>/dev/null")
            .lineSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("\\d+")) }
            .toList()

    private fun findPid(): String = findPids().firstOrNull().orEmpty()

    private fun describe(dirs: List<String>): String = when {
        dirs.isEmpty() -> "守护进程已启动（当前环境无框架开机目录，重启后需重新打开应用）"
        else -> "守护进程已启动，并已登记开机自启：${dirs.joinToString("、") { it.substringAfterLast('/') }}"
    }

    /** 以 root 执行命令，返回退出码是否为 0。 */
    private fun runRoot(cmd: String): Boolean = rootExit(cmd) == 0

    /**
     * 以 root 执行命令并回收输出（用于需要读结果的命令）。
     *
     * ★ 读流必须放在**单独线程**里，主线程只管带超时的 waitFor。
     * 之前是「先在当前线程 readLine 读完，再 waitFor」——这有个致命顺序问题：
     * 用户没点 su 授权框时进程根本不输出，readLine 会永久阻塞，
     * 后面那句带超时的 waitFor 永远轮不到执行，调用线程（轮询协程）就此挂死。
     */
    private fun runRootOutput(cmd: String): String {
        val p = try {
            ProcessBuilder("su", "0", "-c", cmd).redirectErrorStream(true).start()
        } catch (_: Exception) {
            return ""
        }
        val sb = StringBuilder()
        val reader = Thread {
            runCatching {
                BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) sb.append(line).append('\n')
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val finished = runCatching { p.waitFor(SU_TIMEOUT_SEC, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { p.destroyForcibly() }
            // 等读线程收尾，避免返回一个半截字符串
            runCatching { reader.join(500) }
            return sb.append("[err] 执行超时（${SU_TIMEOUT_SEC}s）").toString()
        }
        runCatching { reader.join(1_000) }
        return sb.toString()
    }

    /**
     * `su 0` 明确指定 root 用户（部分管理器默认 su 会落到 shell 身份），
     * 单命令 `-c` 形式避免交互式 su 挂起。
     *
     * ★ 必须返回**真实退出码**：早先这里无论成败都返回 0，
     * 于是 `mkdir -p dir && cp script dir/` 这类命令失败也被当成成功 ——
     * 界面显示「已铺设开机目录」，实际什么都没写进去，用户看到的
     * 是「装了却开机不生效」，极难自查。
     */
    private fun rootExit(cmd: String): Int = try {
        val p = ProcessBuilder("su", "0", "-c", cmd).redirectErrorStream(true).start()
        // 不读流会撑满管道缓冲导致子进程阻塞，这里起个丢弃线程
        Thread {
            runCatching { p.inputStream.bufferedReader().use { r -> r.readText() } }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(SU_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            runCatching { p.destroyForcibly() }
            -1
        } else {
            p.exitValue()
        }
    } catch (_: Exception) {
        -1
    }
}
