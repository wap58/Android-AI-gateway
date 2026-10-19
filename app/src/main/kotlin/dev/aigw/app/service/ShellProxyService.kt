package dev.aigw.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku UserService：给 [ShizukuBoost] 提供 adb shell 身份的进程执行能力。
 *
 * 这个 Service 跑在 Shizuku 用 `app_process` 拉起的独立进程里，uid 是 shell（2000），
 * 因此这里的 `ProcessBuilder` 拿到的就是 adb 身份 —— 这正是普通 APP 进程做不到的部分。
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

    override fun onBind(intent: Intent?): IBinder = object : IShellProxy.Stub() {

        override fun exec(command: String): String {
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
    }
}
