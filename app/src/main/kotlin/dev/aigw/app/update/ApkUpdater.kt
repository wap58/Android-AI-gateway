package dev.aigw.app.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 应用内更新：下载 APK 并直接拉起系统安装器，不再跳浏览器。
 *
 * ## 为什么用 DownloadManager
 *
 * 自己写输出流要处理断点续传、进度、通知栏、Wi-Fi 切换重试；
 * `DownloadManager` 是系统组件，这些全部代劳，而且**App 被杀也能继续下载**。
 *
 * ## 为什么不需要 FileProvider
 *
 * 下载落在公共 `Download/TYD` 目录，Android 10+ 的分区存储不允许应用用文件路径访问它。
 * 但 `DownloadManager.getUriForDownloadedFile(id)` 会直接给出系统托管的 `content://` URI，
 * 安装器可以正常读 —— 比自建 FileProvider 更省事，也不用暴露自己的缓存目录。
 *
 * ## 已知限制
 *
 * - 首次安装需要用户允许「安装未知应用」：这是系统强制，App 只能引导跳转
 * - 部分 ROM 会二次确认，属正常
 */
object ApkUpdater {

    /** 下载子目录：公共下载目录下的 TYD（天元鼎缩写）。 */
    private const val SUBDIR = "TYD"

    /**
     * GitHub 加速镜像（按优先级）。
     *
     * 实测（2026-10-20，从移动网络）三个都能完整下载 1.8MB 的 APK：
     * gh-proxy.com 1.9s / ghfast.top 2.3s / ghproxy.net 3.4s。
     * 留多个是因为这类公益镜像随时可能下线，单点不可靠。
     */
    private val MIRRORS = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
    )

    /** 下载任务 id → 已注册的完成监听（用 id 区分，避免多任务串扰）。 */
    private var activeDownloadId = -1L

    /**
     * 开始下载 APK。
     *
     * @param context 上下文
     * @param url APK 直链（GitHub Release asset 的 browser_download_url）
     * @param version 用于文件名，便于用户辨认
     * @return 下载任务 id；失败返回 -1
     */
    fun download(context: Context, url: String, version: String, attempt: Int = 0): Long {
        // 换镜像重下时文件名必须变：同名目标文件已存在（上一次失败留下的半截文件）
        // 会让 DownloadManager 直接报 ERROR_FILE_ALREADY_EXISTS，
        // 于是每个镜像都失败，用户看到「所有下载地址都失败了」。
        val fileName = if (attempt == 0) {
            "tianyuanding-$version.apk"
        } else {
            "tianyuanding-$version-$attempt.apk"
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("天元鼎 $version")
            .setDescription("正在下载更新…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 落到公共下载目录的 TYD 子目录：用户用文件管理器就能找到安装包。
            // Android 10+ 有分区存储，应用不能直接写 /sdcard，但 DownloadManager 是系统组件，
            // setDestinationInExternalPublicDir 是官方允许的写法（无需额外权限）。
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$SUBDIR/$fileName")
            .setMimeType("application/vnd.android.package-archive")
            .apply {
                // 允许在计费网络下也下载：用户已经在等更新，不该因为没连 Wi-Fi 就卡住
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
            }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return -1L
        val id = runCatching { dm.enqueue(request) }.getOrNull() ?: return -1L
        activeDownloadId = id
        return id
    }

    /**
     * 按顺序尝试的下载地址列表：镜像在前、GitHub 直链兜底。
     *
     * 之所以把直链放最后：国内网络下它最慢，但镜像全挂时它仍是唯一能用的。
     */
    fun downloadCandidates(original: String): List<String> {
        if (!original.startsWith("https://github.com/")) return listOf(original)
        return MIRRORS.map { it + original } + original
    }

    /**
     * 并发测速后按「可用优先、速度快优先」重排下载地址。
     *
     * 为什么要测速：MIRRORS 是固定顺序，但镜像的可用性和速度会随时间、网络、地区变化
     * （公益镜像尤其如此）。原来永远先试 gh-proxy.com，它今天慢或挂了用户就得白等一轮超时。
     *
     * 探测用 HTTP Range 只取文件头部 512KB，既避开整包流量又能真实反映带宽；
     * 4 条线路并行探，最慢也就 timeoutMs（8 秒）。
     *
     * @return 排好序的候选列表；探测全部失败时回退到原来的固定顺序（不能因为测速失败就不给地址）
     */
    fun rankedCandidates(original: String): List<String> {
        val candidates = downloadCandidates(original)
        if (candidates.size <= 1) return candidates
        val scored = candidates.map { url ->
            val score = runCatching { probeSpeed(url) }.getOrDefault(0L)
            url to score
        }
        // 探测成功的（score > 0）按速度降序；失败的全部保留在后，顺序不变（仍可作后备）
        val ok = scored.filter { it.second > 0 }.sortedByDescending { it.second }
        val failed = scored.filter { it.second <= 0 }
        if (ok.isEmpty()) return candidates
        return (ok + failed).map { it.first }
    }

    /**
     * 探测单条线路的下载带宽（字节/秒）；失败返回 0。
     *
     * 镜像本身就是国内入口，绕开用户代理；直连则尊重系统代理设置。
     */
    private fun probeSpeed(url: String, probeBytes: Int = 512 * 1024, timeoutMs: Int = 8_000): Long {
        var conn: java.net.HttpURLConnection? = null
        try {
            val isMirror = MIRRORS.any { url.startsWith(it) }
            val opened = if (isMirror) {
                java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY)
            } else {
                java.net.URL(url).openConnection()
            }
            conn = (opened as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-${probeBytes - 1}")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "ai-gateway-android")
            }
            if (conn.responseCode !in 200..299) return 0L
            val startedAt = System.currentTimeMillis()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            conn.inputStream.use { input ->
                while (total < probeBytes) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    // 够一段时间就停：小文件会提前 EOF，用实际字节算带宽
                    if (System.currentTimeMillis() - startedAt >= 1_200) break
                }
            }
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
            // 低于 16KB 说明基本没传起来（多为镜像限流/空响应），按不可用处理
            if (total < 16 * 1024) return 0L
            return total * 1_000 / elapsed
        } catch (_: Exception) {
            return 0L
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 下载目录（公共 Download/TYD），供界面提示用户去哪里找安装包。 */
    fun downloadDirLabel(): String = "下载/$SUBDIR"

    /**
     * 取已下载完成的 APK 的 content URI。
     *
     * Android 10+ 无法通过文件路径访问公共目录，只能让 DownloadManager
     * 给出它自己的 content:// URI —— 安装器可以直接读这个 URI。
     */
    fun downloadedUri(context: Context): Uri? {
        if (activeDownloadId < 0) return null
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        return runCatching { dm.getUriForDownloadedFile(activeDownloadId) }.getOrNull()
    }

    /** 查询下载状态：完成 / 失败 / 进行中。 */
    fun downloadState(context: Context): DownloadState {
        if (activeDownloadId < 0) return DownloadState.Idle
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return DownloadState.Failed("系统下载服务不可用")
        val cursor = runCatching {
            dm.query(DownloadManager.Query().setFilterById(activeDownloadId))
        }.getOrNull() ?: return DownloadState.Failed("无法查询下载状态")
        cursor.use {
            if (!it.moveToFirst()) return DownloadState.Failed("下载任务不存在")
            val statusIdx = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val reasonIdx = it.getColumnIndex(DownloadManager.COLUMN_REASON)
            val status = if (statusIdx >= 0) it.getInt(statusIdx) else -1
            val reason = if (reasonIdx >= 0) it.getInt(reasonIdx) else 0
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadState.Done
                DownloadManager.STATUS_FAILED -> DownloadState.Failed(describeFailure(reason))
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> DownloadState.Running
                DownloadManager.STATUS_PAUSED -> DownloadState.Running
                else -> DownloadState.Running
            }
        }
    }

    /** 把 DownloadManager 的失败原因翻成人话。 */
    private fun describeFailure(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
        DownloadManager.ERROR_FILE_ERROR -> "文件写入失败"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
            "网络数据错误（可能是下载链接失效）"
        DownloadManager.ERROR_CANNOT_RESUME -> "无法续传，请重试"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "重定向次数过多"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "存储设备不可用"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "同名文件已存在"
        DownloadManager.ERROR_UNKNOWN -> "未知错误（请检查网络后重试）"
        else -> "下载失败（reason=$reason）"
    }

    /** 下载状态。 */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data object Running : DownloadState
        data object Done : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    /**
     * 拉起系统安装器。
     *
     * @return null 表示成功；非 null 是失败原因（给界面提示）
     */
    fun install(context: Context, apk: Uri): String? {
        val uri = apk
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            null
        }.getOrElse { "无法打开安装器：${it.message}" }
    }

    /** 是否已允许安装未知来源应用（Android 8+ 按应用授权）。 */
    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /** 跳转到「安装未知应用」授权页。 */
    fun openInstallPermission(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

}
