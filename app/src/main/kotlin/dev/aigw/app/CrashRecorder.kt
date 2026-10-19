package dev.aigw.app

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃现场记录：把未捕获异常的完整堆栈写到外部可访问的目录。
 *
 * ## 为什么需要它
 *
 * 有设备（如 CPU 虚焊导致基带损坏的机器）既连不上 USB 调试、也激活不了 Shizuku，
 * 崩溃后 `logcat` 拿不到、dropbox 也读不到，等于完全没有线索。
 * 这里把堆栈写到 `/sdcard/Download/tyd-crash.txt`，用户用任意文件管理器就能取出来。
 *
 * ## 为什么同时捕获 Error
 *
 * 脱敏路径上可能出现 `OutOfMemoryError` / `StackOverflowError`，
 * 这些是 `Error` 而不是 `Exception`，`catch (e: Exception)` 接不住，
 * 进程会直接消失、连一行日志都留不下。这里按 `Throwable` 兜底。
 */
object CrashRecorder {

    /** 崩溃文件（Download 目录，用户可见、无需 root）。 */
    private fun crashFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "crash")
        dir.mkdirs()
        return File(dir, "tyd-crash.txt")
    }

    /** 同时往公共 Download 写一份，方便用户直接翻到。 */
    private fun publicCrashFile(): File? = runCatching {
        val dir = File("/sdcard/Download")
        if (!dir.exists() || !dir.canWrite()) return@runCatching null
        File(dir, "tyd-crash.txt")
    }.getOrNull()

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(context, thread, throwable) }
            // 交回系统默认处理：该弹「应用已停止」还是弹，不假装自己存活了
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("=== 天元鼎崩溃记录 ===")
            appendLine("时间: $time")
            appendLine("线程: ${thread.name} (id=${thread.id})")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            // 不依赖 BuildConfig（本工程未开启 buildConfig 生成），直接读包信息
            runCatching {
                val pm = context.packageManager
                val info = pm.getPackageInfo(context.packageName, 0)
                appendLine("版本: ${info.versionName} (${info.longVersionCode})")
            }
            appendLine()
            appendLine(throwable.stackTraceToString())
        }

        runCatching { crashFile(context).writeText(text) }
        runCatching { publicCrashFile()?.writeText(text) }
    }
}
