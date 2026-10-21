package dev.aigw.app.service

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Root 守护的一键安装 / 卸载 / 状态查询。
 *
 * ## 一个脚本适配所有 Root 方案
 *
 * 真身只有一份，固定在 `/data/adb/tyd/`：
 * ```
 * /data/adb/tyd/tyd_keepalive.sh   守护脚本
 * /data/adb/tyd/probe.sh           自检探针
 * /data/adb/tyd/state              期望状态（on <端口> <开机恢复> / off）
 * /data/adb/tyd/keepalive.log      运行日志
 * ```
 * root 方案（Magisk / KernelSU / APatch / 纯 su）只影响**谁在开机时执行**，
 * 所以我们在每个存在的开机目录里放一个 3 行的转发脚本：
 * 它只做「真身存在吗 / 已经有守护在跑吗 / 拉起真身」三件事，
 * 谁执行都行、同一目录执行几次也无害（转发脚本自己做单实例判断）。
 *
 * 这样就不必再「先认框架再决定铺哪」—— 认错一次就整条链路失效
 * （实测 APatch 各版本目录布局不一致，原来那套识别把它判成未知，脚本根本没铺进去）。
 *
 * 纯 `su` 环境没有任何人负责执行开机目录，那种设备靠 APP 自己的开机广播兜底；
 * 本守护负责「被杀后复活」，两者互补。
 */
object RootKeeper {

    private const val SCRIPT_NAME = "tyd_keepalive.sh"

    /** 部署到 root 域的自检探针文件名（固定 /data/adb/tyd/probe.sh）。 */
    private const val PROBE_SCRIPT_NAME = "probe.sh"

    /** APK assets 里的探针文件名（保持不变，避免打包规则跟着改）。 */
    private const val ASSET_PROBE = "rootkeep_probe.sh"

    /** 开机目录里那个「转发脚本」的文件名（它只负责把 /data/adb/tyd 里的真身拉起来）。 */
    private const val SHIM_NAME = "tyd_keepalive.sh"

    /**
     * root 域的工作目录：**所有文件都固定在这里，与 root 方案无关**。
     *
     * 以前是「先识别 Magisk / KernelSU / APatch，再把脚本铺到对应的 service.d」——
     * 识别错一次就全盘失效（APatch 的目录布局各版本不一致，实测认不出来 →
     * 脚本没铺进去 → 开机不生效，界面还显示成「Root 方案未知」）。
     *
     * 现在只往这一个目录写：
     *   /data/adb/tyd/tyd_keepalive.sh   守护脚本（唯一一份）
     *   /data/adb/tyd/probe.sh           自检探针
     *   /data/adb/tyd/state              期望状态（on <port> <autostart> / off）
     *   /data/adb/tyd/keepalive.log      运行日志
     * 再在**所有可能存在的**开机目录里放一个 3 行的转发脚本指向它，
     * 谁执行都行、重复执行也无害（转发脚本自己做了单实例判断）。
     */
    private const val BASE_DIR = "/data/adb/tyd"

    private val SCRIPT_PATH get() = "$BASE_DIR/$SCRIPT_NAME"
    private val PROBE_PATH get() = "$BASE_DIR/$PROBE_SCRIPT_NAME"
    private val LOG_PATH get() = "$BASE_DIR/keepalive.log"
    private val STATE_PATH get() = "$BASE_DIR/state"

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
        /** 卸载干净：进程、开机转发、真身目录、App 内副本全部清掉。 */
        data object Done : UninstallResult
        data class NoRoot(val detail: String) : UninstallResult
        /** 卸载后仍有残留（进程没死 / 文件删不掉），[detail] 说明还剩什么。 */
        data class Partial(val detail: String) : UninstallResult
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
        /** 探针脚本这次没跑出结果（读不到 / su 被拒）：其余字段不可信，界面要如实说明。 */
        val probeFailed: Boolean = false,
        /** 当前在跑的守护实例数（正常应为 1；>1 说明单实例锁失效）。 */
        val instances: Int = 0,
        /** /data/adb/tyd 下的文件清单（排障用：应只有脚本、探针、状态、日志、锁）。 */
        val workDirContents: List<String> = emptyList(),
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

        // ★ 唯一一份脚本，固定落在 /data/adb/tyd/：不随 root 方案变化。
        //   识别 Magisk / KernelSU / APatch 只用于界面展示，不再决定文件去哪。
        if (!putFile(SCRIPT_PATH, script, "755")) {
            return Result.Failed("写入 $SCRIPT_PATH 失败（root 权限不足或 /data/adb 不可写）")
        }
        // 探针顺手也铺到同一目录，自检直接跑它
        val probeText = runCatching {
            context.assets.open(ASSET_PROBE).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        if (probeText.isNotEmpty() && putFile(PROBE_PATH, probeText, "755")) {
            probeDeployedSha = probeText.hashCode()
        }

        // 老版本把 state / 日志放在 /data/adb 根下，且脚本直接铺在 service.d 里。
        // 迁移一次：否则升级后「上次是停止状态」会被重置成默认开，
        // 用户会觉得「我刚关的怎么又开了」。
        runRootOutput(
            "mkdir -p '$BASE_DIR' && " +
                "if [ -f /data/adb/tyd_keepalive.state ] && [ ! -f '$STATE_PATH' ]; then " +
                "mv /data/adb/tyd_keepalive.state '$STATE_PATH'; fi && " +
                "if [ -f /data/adb/tyd_keepalive.log ] && [ ! -f '$LOG_PATH' ]; then " +
                "mv /data/adb/tyd_keepalive.log '$LOG_PATH'; fi",
        )

        // 转发脚本只铺**当前方案对应的那一个**目录。
        // 真身（/data/adb/tyd/tyd_keepalive.sh）三家共用没问题，
        // 但开机入口必须精确：铺多个的话，同一个开关在不同框架下被触发多次，
        // 一份内容不一致就成不可预期的故障，用户看界面也会疑惑「为什么三个」。
        val shim = shimText()
        val installedDirs = mutableListOf<String>()
        for (dir in launchDirs()) {
            if (putFile("$dir/$SHIM_NAME", shim, "755")) installedDirs += dir
        }

        // 清掉其余目录里的历史副本（老版本曾在四个目录全写）。
        // 不清的话界面会继续显示好几个开机目录，而那些是残留、根本不该在。
        cleanupOldCopies(keep = installedDirs)

        // 已在跑就先停，保证新脚本立即生效（否则要等下次重启）。
        // 锁目录也要清：被 kill -9 的实例来不及执行 trap，残缺的锁会让新实例起不来。
        for (oldPid in findPids()) runRoot("kill $oldPid")
        for (i in 0 until 15) {
            if (findPids().isEmpty()) break
            Thread.sleep(200)
        }

        runRoot("rm -rf '$BASE_DIR/daemon.lock'")
        val startOk = runRoot("setsid sh '$SCRIPT_PATH' >/dev/null 2>&1 & echo started")
        var pid = ""
        for (i in 0 until 12) {
            pid = findPid()
            if (pid.isNotEmpty()) break
            Thread.sleep(200)
        }

        return when {
            pid.isNotEmpty() -> {
                markerFile(context).writeText("1")
                Result.Installed(pid, describe(installedDirs))
            }
            startOk -> Result.Failed("脚本已写入 $SCRIPT_PATH，但进程没起来；请看 $LOG_PATH")
            else -> Result.Failed("启动守护进程失败（$SCRIPT_PATH）")
        }
    }

    /**
     * 开机目录里的转发脚本。
     *
     * 它只有两个职责：确认真身存在、确认没有已在跑的守护，然后拉起它。
     * 这样无论 Magisk / KernelSU / APatch 哪个框架执行、执行几次，都不会起多个守护进程。
     */
    private fun shimText(): String = """
        #!/system/bin/sh
        # 天元鼎 · 开机转发（由 APP 生成，勿手改；真身在 $SCRIPT_PATH）
        [ -f '$SCRIPT_PATH' ] || exit 0
        # 单实例交给真身自己用 mkdir 原子锁判断。
        # 这里不能靠 pgrep：各开机目录几乎同时执行，彼此都在启动窗口内，
        # pgrep 看到的是「还没起来」，于是全都放行 —— 一次开机起好几个守护。
        setsid sh '$SCRIPT_PATH' >/dev/null 2>&1 &
        exit 0
    """.trimIndent() + "\n"

    /** 清掉旧版本留在各开机目录里的「真身脚本」副本（内容与新 shim 不同）。 */
    private fun cleanupOldCopies(keep: List<String>) {
        // 遍历**全部**候选目录：老版本在多个目录都放过脚本，升级后要清掉多余的，
        // 否则界面上又会看到好几个开机目录（那些是历史残留，不是本次写入的）。
        for (dir in allLaunchDirs()) {
            if (dir in keep) continue
            runRoot("rm -f '$dir/$SHIM_NAME'")
        }
    }

    /** 卸载：停掉守护进程并删除各处脚本。 */
    fun uninstall(context: Context): UninstallResult {
        if (!hasRoot()) return UninstallResult.NoRoot("未获取到 root")

        // ★ 顺序很重要：必须先确认进程死透，再删文件。
        //   之前是先 kill 紧接着 rm -rf，可守护脚本在跑的时候会 `mkdir -p /data/adb/tyd`，
        //   残余进程在删除之后又把这个目录建了回来 —— 用户看到的就是
        //   「卸载了但 /data/adb/tyd 和脚本还在」。
        // 多轮收敛：普通 kill → 宽限 → kill -9 → 再宽限，最多三轮。
        // 单次 kill 在这类设备上确实出现过「PID 还在」的情况（进程处于不可中断状态
        // 或 su 会话没真正送达信号），所以必须回读确认，不能发完就认为死了。
        var killed = false
        for (round in 0 until 3) {
            val pids = findPids()
            if (pids.isEmpty()) { killed = true; break }
            for (pid in pids) runRoot("kill $pid")
            if (waitGone(1_500)) { killed = true; break }
            for (pid in findPids()) runRoot("kill -9 $pid")
            if (waitGone(1_500)) { killed = true; break }
        }

        // 删除动作做两轮：第一轮删完后如果目录又出现（极少数情况下是残留进程
        // 或其它副本写回来的），第二轮连同刚重建的一起删掉。
        repeat(2) { attempt ->
            if (attempt > 0 && !baseDirExists()) return@repeat

            // 开机转发脚本：所有候选目录都清（老版本可能在多个目录留过副本）
            for (dir in allLaunchDirs()) runRoot("rm -f '$dir/$SHIM_NAME'")

            // 真身目录整个删掉：脚本、探针、状态、日志、锁都在里面
            runRoot("rm -rf '$BASE_DIR'")
            // 老版本散落在 /data/adb 根下的文件也一并清
            runRoot(
                "rm -f /data/adb/tyd_keepalive.sh /data/adb/tyd_keepalive.state " +
                    "/data/adb/tyd_keepalive.log /data/adb/rootkeep_probe.sh",
            )

            // 目录还在（说明有残留进程在 mkdir，或 rm 被 SELinux 拦）：
            // 先看看是谁在跑，把进程再杀一轮，然后重试删除
            if (baseDirExists() && findPids().isNotEmpty()) {
                for (pid in findPids()) runRoot("kill -9 $pid")
                waitGone(1_200)
            }
        }

        // App 内副本与「已安装」标记
        File(context.filesDir, "rootkeep/$SCRIPT_NAME").delete()
        File(context.filesDir, "rootkeep/$PROBE_SCRIPT_NAME").delete()
        File(context.filesDir, "rootkeep/installed").delete()
        probeDeployedSha = 0

        // 验证：进程、开机转发、真身目录三者都必须没了，否则如实报告残留
        val leftovers = mutableListOf<String>()
        val alive = findPids()
        if (alive.isNotEmpty()) leftovers += "进程仍在（PID ${alive.joinToString(",")}）"
        val shims = allLaunchDirs().filter { dir ->
            runRootOutput("test -f '$dir/$SHIM_NAME' && echo yes").contains("yes")
        }
        if (shims.isNotEmpty()) leftovers += "开机脚本仍在（${shims.joinToString("、")}）"
        if (baseDirExists()) leftovers += "$BASE_DIR 仍在"

        return if (leftovers.isEmpty()) {
            UninstallResult.Done
        } else {
            UninstallResult.Partial(leftovers.joinToString("；"))
        }
    }

    private fun baseDirExists(): Boolean =
        runRootOutput("test -e '$BASE_DIR' && echo yes").contains("yes")

    /** 等守护进程全部退出，返回是否已清空。 */
    private fun waitGone(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (findPids().isEmpty()) return true
            Thread.sleep(150)
        }
        return findPids().isEmpty()
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
        // ★ 未安装（marker 不存在）时不要去部署探针：
        //   之前卸载后 verify 仍每 3 秒跑一次，ensureProbeScript 会把
        //   /data/adb/tyd/probe.sh 又写回去 —— 用户卸载完发现目录还在，就是这个原因。
        if (!markerFile(context).exists() && findPid().isEmpty()) {
            return Verify(
                processAlive = false,
                pid = "",
                probeWorks = false,
                probeOutput = "",
                registeredDirs = emptyList(),
                rootFlavor = rootFlavor(),
                logTail = "",
                desiredRunning = null,
                suAvailable = true,
                scriptVersion = "",
                bundledVersion = bundledScriptVersion(context),
            )
        }

        val script = ensureLocalScript(context)
        // ★ 参数顺序：跑的是**探针脚本**，第一个参数才是守护脚本路径。
        //   写反的后果是探针根本没被执行，输出恒为空 ——
        //   界面表现就是「Root 方案未知 / 守护进程未运行 / 探活不可用 / 开机目录未铺设」全红，
        //   而实际上守护进程跑得好好的（安装成功的 Toast 里 PID 都有）。
        val raw = runRootOutput(
            "sh '${ensureProbeScript(context).absolutePath}' '${script.absolutePath}' 2>/dev/null",
        )
        val fields = raw.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()

        // 探针完全没输出（脚本读不到 / su 被拒 / 输出被截断）：不要直接把界面刷成全红。
        // 那会让「守护明明在跑」看起来像彻底失效，再排障就完全找错方向。
        // 退一步用最原始的一跳 pgrep 确认进程，其余项留空让界面显示「未知」。
        if (fields.isEmpty()) {
            val pidFallback = findPid()
            return Verify(
                processAlive = pidFallback.isNotEmpty(),
                pid = pidFallback,
                probeWorks = false,
                probeOutput = "",
                registeredDirs = emptyList(),
                rootFlavor = "未知",
                logTail = readLog(context).take(400),
                desiredRunning = readState(),
                suAvailable = true,
                scriptVersion = "",
                bundledVersion = bundledScriptVersion(context),
                probeFailed = true,
            )
        }

        val pid = fields["PID"].orEmpty()
        val flavorRaw = fields["FLAVOR"].orEmpty()
        val probeRaw = fields["PROBE"].orEmpty()
        val stateRaw = fields["STATE"].orEmpty()
        val dirs = fields["DIR"].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // 探针回报的是完整路径。/data/adb/service.d 与 /data/adb/ksu/service.d
            // 末段都叫 service.d，界面上要显示成区分得开的短路径（adb/service.d、
            // ksu/service.d），否则看起来像「同一个目录重复了三遍」。

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
            instances = fields["INSTANCES"]?.toIntOrNull() ?: 0,
            workDirContents = fields["WORKDIR"].orEmpty().split(' ').filter { it.isNotBlank() },
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

    /**
     * 准备探针脚本，返回 su 侧实际该执行的那个路径：固定 `/data/adb/tyd/probe.sh`。
     *
     * app 私有目录（/data/user/0/<pkg>/files）在部分 root 域读不到，
     * 所以只要拿得到 root 就统一部署到 /data/adb/tyd —— 那里任何 su 域都能读。
     */
    private fun ensureProbeScript(context: Context): File {
        val local = File(context.filesDir, "rootkeep/$PROBE_SCRIPT_NAME")
        val text = runCatching {
            context.assets.open(ASSET_PROBE).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        if (text.isEmpty()) return local

        runCatching {
            if (!local.exists() || local.readText() != text) {
                local.parentFile?.mkdirs()
                local.writeText(text)
                local.setReadable(true, false)
            }
        }

        // 内容指纹没变就不重复写；失败后退避 60 秒再试（verify 每 3 秒一轮）
        if (probeDeployedSha != text.hashCode() &&
            System.currentTimeMillis() - probeDeployFailedAt > 60_000L
        ) {
            val ok = runCatching { putFile(PROBE_PATH, text, "755") }.getOrDefault(false)
            probeDeployedSha = if (ok) text.hashCode() else 0
            probeDeployFailedAt = if (ok) 0L else System.currentTimeMillis()
        }
        return if (probeDeployedSha != 0) File(PROBE_PATH) else local
    }

    /**
     * 把一段文本以 root 身份写到指定路径。
     *
     * 用 here-doc 而不是 `echo '...'`：脚本里含单引号、反引号、`$` 时，
     * 拼进 echo 会被 su 的 shell 二次解析（历史问题：su 下手动跑正常、App 里拿到空文件）。
     */
    private fun putFile(path: String, text: String, mode: String): Boolean =
        runRoot(
            "mkdir -p '${path.substringBeforeLast('/')}' && " +
                "cat > '$path' <<'TYD_END_OF_FILE'\n$text\nTYD_END_OF_FILE\n" +
                "chmod $mode '$path'",
        )

    /** 已部署到 /data/adb 的探针内容指纹；0 表示尚未部署。 */
    @Volatile
    private var probeDeployedSha = 0

    /** 上次部署失败的时刻（毫秒），用于退避重试。 */
    @Volatile
    private var probeDeployFailedAt = 0L

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
    /**
     * 需要放「开机转发脚本」的目录：**全部候选都放，不再区分 root 方案**。
     *
     * 只有一个是真的会被执行的，但多放几个的代价是几个 3 行的文件，
     * 换来的是「任何方案都能开机自启」——比识别错一次就整条链路失效划算得多。
     * 目录不存在就不建（`mkdir -p` 会凭空造出 /data/adb/ap/ 这种假目录，
     * 之后识别 root 方案时反而会误判）。
     *
     * 转发脚本自身做了单实例判断，即使多个目录都被执行也不会起多个守护。
     */
    /** Root 方案。 */
    enum class Flavor(val label: String) {
        Magisk("Magisk"),
        KernelSu("KernelSU/Sukisu"),
        APatch("APatch"),
        /** 有模块目录但认不出具体框架。 */
        MagiskLike("Magisk 系"),
        /** 纯 su（无框架）：没有任何开机目录会被执行。 */
        PlainSu("纯 su"),
        ;

        /** APatch 与 Magisk 都走 /data/adb/service.d（APatch 用的是 Magisk 兼容目录）。 */
        val launchDir: String?
            get() = when (this) {
                Magisk, APatch, MagiskLike -> "/data/adb/service.d"
                KernelSu -> "/data/adb/ksu/service.d"
                PlainSu -> null
            }
    }

    /**
     * 识别当前 Root 方案。
     *
     * APatch 放在最前：它也会带一份 magisk 兼容目录，放在后面判会被认成 Magisk，
     * 而 APatch 自己的标志是 /data/adb/apd（二进制）或 /data/adb/ap（目录）。
     */
    fun detectFlavor(): Flavor {
        val out = runRootOutput(
            "if [ -f /data/adb/apd ] || [ -d /data/adb/ap ]; then echo apatch; " +
                "elif [ -d /data/adb/ksu ]; then echo ksu; " +
                "elif [ -d /data/adb/magisk ]; then echo magisk; " +
                "elif [ -d /data/adb/modules ]; then echo magisklike; " +
                "else echo plain; fi"
        ).trim().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        return when (out) {
            "apatch" -> Flavor.APatch
            "ksu" -> Flavor.KernelSu
            "magisk" -> Flavor.Magisk
            "magisklike" -> Flavor.MagiskLike
            else -> Flavor.PlainSu
        }
    }

    /**
     * 本次要铺转发脚本的开机目录：**只铺一个**，就是当前方案自己的那一个。
     *
     * 铺全部的代价不是几个小文件，而是「同一件事有好几份配置」——
     * 用户看界面会疑惑为什么要三个；一旦其中某份内容不一致（旧版本残留、
     * 手改过、复制时被截断），故障现象就变得不可预期。
     * 精度换来的是「看得懂、可预期」。
     *
     * 只有当方案对应的目录不存在时才退让一步：从其它候选里挑**一个**已存在的，
     * 保证「有 root 但目录缺失」的机器也能开机自启，同时仍然只有一个入口。
     */
    private fun launchDirs(): List<String> {
        val preferred = detectFlavor().launchDir
        if (preferred != null && dirExists(preferred)) return listOf(preferred)

        // 退让候选：按可靠性排序（service.d 最通用，post-fs-data.d 太早，系统服务常未就绪）
        val fallbacks = listOf("/data/adb/service.d", "/data/adb/ksu/service.d", "/data/adb/ap/service.d")
        return (listOfNotNull(preferred) + fallbacks)
            .distinct()
            .filter { it != preferred && dirExists(it) }
            .take(1)
    }

    private fun dirExists(dir: String): Boolean =
        runRootOutput("test -d '$dir' && echo yes").contains("yes")

    /** 所有可能放转发脚本的目录（用于清理旧版本的多余副本）。 */
    private fun allLaunchDirs(): List<String> = listOf(
        "/data/adb/service.d",
        "/data/adb/ksu/service.d",
        "/data/adb/ap/service.d",
        "/data/adb/post-fs-data.d",
    )

    /** 当前 Root 方案的展示名，用于界面提示。 */
    fun rootFlavor(): String = detectFlavor().label

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
        runRootOutput(
            // 两条路兜底：pgrep 在个别 busybox 环境里行为不一致（-f 不完全匹配 cmdline），
            // 用 ps 再过一遍，避免「明明在跑却抓不到 PID」导致卸载杀不掉。
            "pgrep -f '$SCRIPT_NAME\$' 2>/dev/null; " +
                "ps -A -o PID,ARGS 2>/dev/null | grep -F '$SCRIPT_NAME' | grep -v grep | awk '{print \$1}'",
        )
            .lineSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("\\d+")) }
            .distinct()
            .toList()

    private fun findPid(): String = findPids().firstOrNull().orEmpty()

    private fun describe(dirs: List<String>): String = when {
        dirs.isEmpty() ->
            "守护进程已启动，脚本固定在 $SCRIPT_PATH（当前系统没有可用的开机目录，重启后需重新打开应用）"
        else ->
            "守护进程已启动，脚本固定在 $SCRIPT_PATH；开机目录：${dirs.joinToString("、") { it.substringAfterLast('/') }}"
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
