package dev.aigw.app.service

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle

/**
 * 「隐藏多任务」：应用真正退到后台时，把自己从最近任务列表里移除。
 *
 * 这是保活手段之一 —— 国产 ROM 的「一键清理」主要按最近任务列表杀进程，
 * 列表里看不到本应用，被清掉的概率显著下降。
 *
 * ## 为什么不用 onPause / onUserLeaveHint
 *
 * `excludeFromRecents` 是清单里的静态属性，没有运行期开关，只能运行期调
 * `finishAndRemoveTask()` 做等效。
 *
 * 而「用户离开应用」这个时机极易判断错：
 * - `onPause`：应用内跳转（设置页 → 主页）也会触发 → 会把用户正在看的页面**当场杀掉**
 * - `onUserLeaveHint`：AOSP 文档明确写了启动**同一个应用的另一个 Activity 也回调** → 同样误杀
 *
 * （这两个坑在「智码通」项目里都真实踩过，现象是「点主题闪退」。）
 *
 * 唯一可靠的判据是**统计处于 started 状态的 Activity 数量**：
 * 归零才算真正离开应用 —— 与 `ProcessLifecycleOwner` 用的是同一套判据。
 */
object HideRecents {

    /** Activity 计数，归零即「已退到后台」。 */
    private var startedCount = 0

    /** 是否已注册（避免重复注册）。 */
    private var registered = false

    /** 开关状态由调用方注入，避免此处反向依赖数据层。 */
    @Volatile
    private var enabledProvider: (() -> Boolean)? = null

    /**
     * 注册生命周期回调。
     *
     * @param enabledProvider 每次退后台时实时查询开关，无需在开关变化时重新注册。
     */
    fun install(app: Application, enabledProvider: () -> Boolean) {
        if (registered) return
        registered = true
        this.enabledProvider = enabledProvider

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedCount++
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount--
                if (startedCount <= 0) {
                    startedCount = 0
                    // 计数归零 = 用户真的离开了应用（而不是在应用内跳页面）
                    if (enabledProvider() && !activity.isChangingConfigurations) {
                        removeFromRecents(activity.applicationContext)
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * 把本应用的任务从最近列表移除。
     *
     * 用 `appTasks` 逐个 `finishAndRemoveTask()`，而不是 `finishAffinity()`：
     * 后者只结束 Activity 栈，任务记录仍在最近列表里，达不到隐藏效果。
     */
    private fun removeFromRecents(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        runCatching {
            for (task in am.appTasks) {
                val base = task.taskInfo?.baseIntent?.component?.packageName
                    ?: task.taskInfo?.baseActivity?.packageName
                if (base == context.packageName) {
                    task.finishAndRemoveTask()
                }
            }
        }
    }
}
