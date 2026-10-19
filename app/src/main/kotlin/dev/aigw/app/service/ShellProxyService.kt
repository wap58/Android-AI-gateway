package dev.aigw.app.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku UserService：给 [ShizukuBoost] 提供 adb shell 身份的进程执行能力。
 *
 * 这个 Service 跑在 Shizuku 用 `app_process` 拉起的独立进程里，uid 是 shell（2000），
 * 因此这里的 `ProcessBuilder` 拿到的就是 adb 身份 —— 这正是普通 APP 进程做不到的部分。
 *
 * ## 为什么用 Messenger 而不是 AIDL
 *
 * AIDL 在 AGP 8.x 下需要额外构建配置才生成接口类，容易踩「接口类没生成」的坑
 * （实测 `src/main/aidl` 放了文件、配了 sourceSets，Kotlin 侧依然 Unresolved reference）。
 * Messenger + Bundle 是纯 framework API，零配置，也足够表达「传一条命令、拿一段输出」。
 *
 * 协议（request 的 Bundle）：
 * - `what`    = [WHAT_EXEC]
 * - `"cmd"`   = String，要执行的 shell 命令
 * - `replyTo` = Messenger，用于回传结果
 *
 * reply 的 Bundle：
 * - `"out"`   = String，stdout 与 stderr 合并后的完整输出
 * - `"ok"`    = Boolean，是否正常走到执行（不是退出码）
 *
 * ## 为什么要它
 *
 * Shizuku 自带的 `ShizukuRemoteProcess` / `Shizuku.newProcess()` 在 API 13 已废弃、
 * API 14 直接移除，官方给出的替代方案就是「用一个 UserService 承载 exec」。
 *
 * ## 声明要点
 *
 * 只需在 Manifest 里声明这个 Service（`android:exported="false"`），
 * **不要**加 `android:permission` —— UserService 不走 bindService，
 * 绑定校验由 Shizuku 侧按「调用方 uid 与包名一致」完成
 * （见 `UserServiceManager.ensureCallingPackageForUserService`）。
 * 加一个不存在的权限只会让这条路永远失败。
 */
class ShellProxyService : Service() {

    override fun onBind(intent: Intent?): IBinder {
        // Handler 用主 Looper：UserService 进程就是为这件事起的，没有别的线程要调度
        val handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != WHAT_EXEC) return
                val cmd = msg.data.getString(KEY_CMD).orEmpty()
                val reply = Message.obtain().apply {
                    data = Bundle().apply {
                        putString(KEY_OUT, exec(cmd))
                        putBoolean(KEY_OK, true)
                    }
                }
                try {
                    msg.replyTo?.send(reply)
                } catch (_: RemoteException) {
                    // 调用方进程已死：没有回传对象，直接丢弃即可
                }
            }
        }
        return Messenger(handler).binder
    }

    /** 在 shell 身份下执行命令，返回 stdout+stderr 合并输出。 */
    private fun exec(command: String): String {
        val process: Process = try {
            ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()
        } catch (e: Throwable) {
            return "启动失败：${e.message}"
        }

        val sb = StringBuilder()
        // stdout
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line).append('\n')
            }
        } catch (_: Exception) {
        }
        // stderr 单独收：命令被系统拒绝时，信息只在这里
        try {
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append("[err] ").append(line).append('\n')
            }
        } catch (_: Exception) {
        }
        try {
            process.waitFor()
        } catch (_: Exception) {
        }
        process.destroy()
        return sb.toString()
    }

    companion object {
        const val WHAT_EXEC = 1
        const val KEY_CMD = "cmd"
        const val KEY_OUT = "out"
        const val KEY_OK = "ok"
    }
}
