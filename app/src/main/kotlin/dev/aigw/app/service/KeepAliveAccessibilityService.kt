package dev.aigw.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 空壳无障碍服务：不为辅助操作，只为**后台保活**。
 *
 * ## 为什么要它
 *
 * 国产系统（ColorOS / OriginOS / MIUI 等）对「已启用无障碍」的应用有后台豁免：
 * 不进入「待机」分组、一键清理时跳过、部分机型甚至允许无条件后台拉起。
 * 这比折腾电池白名单和自启动管理可靠得多，且不需要 Root。
 *
 * ## 为什么是空的
 *
 * 声明里 `canRetrieveWindowContent=false`、`accessibilityFlags` 为空，
 * 不监听任何窗口事件 —— 本服务**不读取屏幕内容**，只借这个身份换后台优先级。
 * 因此对隐私零影响，用户也能在系统设置里随时看到「已启用」并关闭。
 *
 * ## 它做了什么
 *
 * 什么都不做。`onAccessibilityEvent` 直接返回，不记录、不上报、不弹窗。
 * 唯一的副作用是系统把本应用的 oom_adj 调低、清理时优先跳过。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 刻意留空：不监听窗口内容，只借无障碍身份拿后台优先级。
    }

    override fun onInterrupt() {
        // 无辅助操作，无需中断处理。
    }
}
