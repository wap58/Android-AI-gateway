package dev.aigw.app.service

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build

/**
 * 「隐藏多任务」：把本应用任务从最近任务列表里摘掉，降低被一键清理的概率。
 *
 * ## 为什么不用 finishAndRemoveTask（v0.0.1 的真实 bug）
 *
 * 旧实现一检测到「退到后台」就 `finishAndRemoveTask()`，于是用户点
 * 「打开应用信息」「打开厂商后台设置」时**当场闪回桌面**：
 *
 * 跳系统设置 → 本应用退到后台 → 计数归零 → 把我们的任务（以及可能在其上的页面）销毁，
 * 从设置页返回时已无任务可回，只能落在桌面。看着像闪退，其实是被我们自己杀的。
 *
 * ## 正解：setExcludeFromRecents
 *
 * `ActivityManager.AppTask.setExcludeFromRecents(boolean)` 是 **public 普通 API**
 * （非 @SystemApi，AOSP `ActivityManager.java` 中为 public 方法），它只修改任务根 Intent 上的
 * `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` 标记：
 * - 任务**不被销毁**，Activity 栈完整保留，从系统设置返回照常回到本应用
 * - 最近任务列表里看不到本应用
 *
 * 该运行期接口仅 API 30+（Android 11）提供。本项目 minSdk 26，低版本没有既能隐藏
 * 又不销毁任务的等效手段，因此低版本下本开关**不生效**——宁可功能不生效，也不重演杀任务的 bug。
 *
 * ## 判据为什么是「计数归零」
 *
 * `onPause` / `onUserLeaveHint` 在**应用内跳转**时同样触发（AOSP 文档明确说明
 * `onUserLeaveHint` 在启动同应用另一个 Activity 时也会回调），会把用户正在看的页面当场摘掉。
 * 计数归零才算真正离开应用，与 `ProcessLifecycleOwner` 同判据。
 */
object HideRecents {

    /** 正在前台（started）的 Activity 计数，归零即「已退到后台」。 */
    private var startedCount = 0

    private var registered = false

    /** 开关状态由调用方注入，避免此处反向依赖数据层。 */
    @Volatile
    private var enabledProvider: (() -> Boolean)? = null

    fun install(app: Application, enabledProvider: () -> Boolean) {
        if (registered) return
        registered = true
        this.enabledProvider = enabledProvider

        // 冷启动时若开关已是打开的，先把标记打上（任务可能在进程重启后被重建）。
        if (enabledProvider() == true) applyExclude(app, true)

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedCount++
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount--
                if (startedCount > 0) return
                startedCount = 0
                // 计数归零 = 真的离开了应用（而非应用内跳页面）
                if (activity.isChangingConfigurations) return
                if (enabledProvider() == true) applyExclude(activity.applicationContext, true)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** 用户拨动开关时立即同步标记。 */
    fun syncNow(context: Context, enabled: Boolean) {
        applyExclude(context, enabled)
    }

    /**
     * 修改本应用任务的「排除出最近任务」标记。只改标记，**不销毁任务**。
     * 查询自身任务无需 GET_TASKS 权限。
     */
    private fun applyExclude(context: Context, exclude: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        runCatching {
            for (task in am.appTasks) {
                val info = task.taskInfo ?: continue
                val owner = info.baseIntent?.component?.packageName
                    ?: info.baseActivity?.packageName
                    ?: info.topActivity?.packageName
                if (owner == context.packageName) task.setExcludeFromRecents(exclude)
            }
        }
    }
}
