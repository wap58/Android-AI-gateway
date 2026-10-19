package dev.aigw.app.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 「悬浮窗保活」：在屏幕上留一个 1×1 的近乎透明像素点。
 *
 * 原理：带可见悬浮窗（TYPE_APPLICATION_OVERLAY）的应用会被系统视为前台可见进程，
 * 后台回收优先级低于纯后台服务；它只影响系统回收，不改变任何上游行为。
 *
 * 与上游实现的差别（本项目定制）：
 *  - 尺寸 WRAP_CONTENT → 1×1，肉眼不可见，不遮挡任何内容；
 *  - 关闭拖动与点击（1×1 也点不中），因此去掉 attachDrag 与 OnClickListener；
 *  - 位置固定在左上角 (0,0)，最不影响使用；不再落盘位置（固定即可，无需持久化）；
 *  - alpha 用 0.01 而非 0：完全透明（0）有被部分 ROM 从「可见性判定」里剔除的风险，
 *    0.01 在判定上等同于可见，同时肉眼不可辨。
 *
 * 保活仍依赖「窗口真的可见」这一前提：因此不允许把窗口移出屏幕，
 * 也不使用 FLAG_NOT_TOUCHABLE（该 flag 在部分 ROM 上会影响可见性判定）。
 *
 * 全部方法必须在主线程调用；窗口用 try/catch 包住——权限被收回或系统拒绝时只返回 false，
 * 不影响网关本身运行。
 */
object FloatingKeepAlive {

    /** 悬浮窗边长（像素）。1×1 肉眼不可见，但对系统仍是一个「可见窗口」。 */
    private const val VIEW_SIZE_PX = 1

    /** 近乎全透明：0 有被 ROM 剔除可见性判定的风险，0.01 肉眼不可辨但判定为可见。 */
    private const val VIEW_ALPHA = 0.01f

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** 是否已获得「显示在其他应用上层」权限。 */
    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isShowing(): Boolean = view != null

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(android.net.Uri.parse("package:${context.packageName}"))

    /**
     * 显示悬浮窗；未授权或添加失败时返回 false（调用方据此提示用户）。
     * [label] 仅用于日志与状态展示，不再显示在窗口上（窗口是 1×1 不可见）。
     */
    fun show(context: Context, label: String): Boolean {
        if (view != null) return true
        if (!canDraw(context)) return false
        val app = context.applicationContext
        val windowManager =
            app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        return try {
            val windowParams = buildParams(app)
            val pixel = TextView(app).apply {
                // 1×1 且全透明：不绘制任何可辨内容，也绝不响应触摸
                background = ColorDrawable(0x00000000)
                text = ""
                alpha = VIEW_ALPHA
                isClickable = false
                isFocusable = false
            }
            windowManager.addView(pixel, windowParams)
            view = pixel
            params = windowParams
            true
        } catch (_: Exception) {
            view = null
            params = null
            false
        }
    }

    fun hide(context: Context) {
        val current = view ?: return
        val windowManager =
            context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { windowManager?.removeView(current) }
        view = null
        params = null
    }

    private fun buildParams(@Suppress("UNUSED_PARAMETER") app: Context): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            VIEW_SIZE_PX,
            VIEW_SIZE_PX,
            type,
            // 不可获焦：不影响任何输入。
            // 注意不加 FLAG_NOT_TOUCHABLE——1×1 已被内部 View 自身消费触摸，
            // 且该 flag 在部分 ROM 上会影响「可见性」判定，与保活目的相悖。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 固定在左上角：最不影响使用的锚点，不落盘、不可拖动，因此不会「拖丢」
            x = 0
            y = 0
        }
    }

    /** 供状态展示：悬浮窗是否正在显示（1×1 无文字，仅返回是否挂载）。 */
    fun currentLabel(): String? = if (view != null) "" else null
}
