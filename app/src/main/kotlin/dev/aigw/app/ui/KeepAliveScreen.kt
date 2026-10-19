package dev.aigw.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.aigw.app.data.KeepAlive
import dev.aigw.app.service.FloatingKeepAlive

/**
 * 保活与权限：把网关在后台持续运行所需的系统条件逐项检测出来，并给出一键跳转。
 *
 * 锁屏后端口还能被客户端调通，需要几件事同时成立：通知权限、应用不在电池优化名单里、
 * 系统没有限制它后台运行。这些都没有 API 能直接代改，只能检测 + 引导。
 */
@Composable
fun KeepAliveScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val status = state.keepAlive

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    // 从系统授权页返回时（ON_RESUME）重检权限并补显示悬浮窗：
    // 用户去设置里授权后回到本页，开关无需重新点一遍。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reapplyOverlayIfEnabled()
                viewModel.refresh()
                // Root 守护状态要回读，不能只看「按钮点过没有」
                viewModel.refreshRootKeeperState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Root 守护状态：进页面查一次，之后停留在本页期间每 3 秒复查一次。
    //
    // 为什么需要轮询：守护进程的 PID、探活结果会变化（被杀后重新拉起、网关重启等），
    // 只查一次的话界面会一直停在进入页面那一刻的快照，用户必须退出重进才看到最新状态
    // （这正是之前「退出重进又正常了」的现象）。
    // 查询走 IO 线程、每次只起几个短命令，3 秒间隔对系统负担可忽略。
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshRootKeeperState()
            kotlinx.coroutines.delay(3_000)
        }
    }

    // Shizuku / Sui 状态要事件驱动：binder 是异步递过来的，
    // 只靠 ON_RESUME 查一次的话，首次进本页时 binder 常常还没就绪，
    // 界面会一直停在「未授权」，用户必须退出去再进来才刷新。
    var shizukuState by remember { mutableStateOf(dev.aigw.app.service.ShizukuBoost.currentState()) }
    var suiMode by remember { mutableStateOf(dev.aigw.app.service.ShizukuBoost.isSui()) }
    // 完整日志弹窗的显隐
    var showLogDialog by remember { mutableStateOf(false) }
    // 弹窗里的完整日志：点「更多」时加载一次，避免重组期间反复起 su 进程
    var fullLogText by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        // binder 到达 / 断开时立刻重算，不用等用户手动切页面
        val stop = dev.aigw.app.service.ShizukuBoost.observe {
            shizukuState = dev.aigw.app.service.ShizukuBoost.currentState()
            suiMode = dev.aigw.app.service.ShizukuBoost.isSui()
        }
        onDispose { stop() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                shizukuState = dev.aigw.app.service.ShizukuBoost.currentState()
                suiMode = dev.aigw.app.service.ShizukuBoost.isSui()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PageScaffold(
        title = "保活与权限",
        subtitle = "让网关在后台持续运行",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("保活状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    text = if (status?.allGood == true) "已就绪" else "有待处理",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (status?.allGood == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = "通知权限 + 电池优化白名单 + 不被限制后台，这几项都满足后才能锁屏稳定可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.running) {
                Text(
                    text = "当前服务未启动：先去首页启动，否则锁屏后没有进程监听端口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard(enterIndex = 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("悬浮窗保活", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "在屏幕上留一个小悬浮标签，让系统把本应用视为可见进程，降低后台被回收的概率；" +
                            "网关服务启动后出现、停止时移除，可拖动、点按回到应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.overlayKeepAlive,
                    onCheckedChange = { viewModel.setOverlayKeepAlive(it) },
                )
            }
            if (state.overlayKeepAlive && !state.running) {
                Text(
                    text = "网关未启动：悬浮窗将在服务启动后显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.overlayKeepAlive && status?.overlayGranted != true) {
                Text(
                    text = "缺少「显示在其他应用上层」权限，悬浮窗尚未显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (status?.overlayGranted != true) {
                ChoiceChip("授予悬浮窗权限", false) {
                    launch(context, FloatingKeepAlive.permissionIntent(context), "无法打开悬浮窗权限设置")
                }
            }
            Text(
                text = "说明：悬浮窗只影响系统的后台回收策略，不改变请求链路；关闭开关会立即移除悬浮窗。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(enterIndex = 2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("开机自启", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "系统启动完成后自动把网关服务拉起来，不用每次手动打开应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.autoStart,
                    onCheckedChange = { viewModel.setAutoStart(it) },
                )
            }
            Text(
                text = "注意：还需在系统的「自启动管理」里放行本应用，否则开机广播会被厂商拦截（用下面的入口去设置）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(enterIndex = 3) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("隐藏多任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "离开应用后从最近任务列表里隐藏自身。国产系统的「一键清理」主要扫最近任务，" +
                            "列表里看不到本应用，被清掉的概率明显下降。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.hideRecents,
                    onCheckedChange = { viewModel.setHideRecents(it) },
                )
            }
            Text(
                text = "说明：只在真正退到后台时生效（应用内切页面不会触发），网关服务不受影响，仍然在后台运行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(enterIndex = 4) {
            // 标题已写明「可选」，不再单挂一个「需 ROOT」红标（与文案重复且抢占视线）
            Column(Modifier.fillMaxWidth()) {
                Text("Root 守护（可选）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "本应用自身全部功能都不需要 root：悬浮窗、开机自启、隐藏多任务走的是系统公开权限。" +
                        "只有「被杀自动复活」这一层需要 root，点下面的按钮即可一键安装，无需手动操作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "守护脚本每 20 秒探活一次 http://127.0.0.1:${state.port}/healthz，连续 2 次无响应就重新拉起网关服务。" +
                    "兼容 Magisk / KernelSU / APatch / Sui 及纯 su 环境。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 实测状态：装没装上、跑没跑起来，必须回读，不能只看按钮点过没有
            val rk = state.rootKeeper
            if (rk == null) {
                Text(
                    text = "正在查询守护状态…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                RootStatusLine(
                    label = "Root 方案",
                    ok = rk.rootFlavor != "未知" && rk.rootFlavor != "纯 su",
                    text = rk.rootFlavor,
                )
                RootStatusLine(
                    label = "守护进程",
                    ok = rk.processAlive,
                    text = if (rk.processAlive) "运行中（PID ${rk.pid}）" else "未运行",
                )
                RootStatusLine(
                    label = "探活通道",
                    ok = rk.probeWorks,
                    text = if (rk.probeWorks) "可用（返回「${rk.probeOutput}」）"
                    else "不可用（输出「${rk.probeOutput.ifEmpty { "空" }}」）",
                )
                RootStatusLine(
                    label = "开机目录",
                    ok = rk.registeredDirs.isNotEmpty(),
                    text = if (rk.registeredDirs.isEmpty()) "未铺设（无框架或权限不足）"
                    else rk.registeredDirs.joinToString("、") { it.substringAfterLast('/') },
                )
                RootStatusLine(
                    label = "整体",
                    ok = rk.ok,
                    text = if (rk.ok) "已生效（被杀会自动复活）" else "未生效，请看上面哪项没通过",
                )
                // 日志只露 3 行：守护每 20 秒一轮，完整日志很快就能把卡片撑很长。
                // 整块可点，末尾「查看更多」进弹窗看完整的。
                if (rk.logTail.isNotEmpty()) {
                    val lines = rk.logTail.lines().filter { it.isNotBlank() }
                    val preview = lines.takeLast(3).joinToString("\n")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                fullLogText = viewModel.loadFullRootKeeperLog()
                                showLogDialog = true
                            },
                    ) {
                        Text(
                            text = "最近日志",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "查看更多",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // 四个操作按钮固定一行：文案都是两个字（安装/重装、自检、清除、卸载），
            // 宽度刚好排满，不需要折行逻辑。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(
                    if (state.rootKeeper?.ok == true) "重装" else "安装",
                    selected = false,
                ) { viewModel.installRootKeeper() }
                ChoiceChip("自检", selected = false) { viewModel.verifyRootKeeper() }
                ChoiceChip("清除", selected = false) { viewModel.clearRootKeeperLog() }
                ChoiceChip("卸载", selected = false) { viewModel.uninstallRootKeeper() }
            }
        }

        // 完整日志弹窗：「更多」打开，可滚动查看最近 200 行。
        // 日志只在打开时加载一次（存进 fullLog 状态），不能写在重组里 ——
        // 那会让每帧都起一次 su 进程，界面直接卡死。
        if (showLogDialog) {
            AlertDialog(
                onDismissRequest = { showLogDialog = false },
                title = { Text("守护日志") },
                text = {
                    Text(
                        text = fullLogText.ifEmpty { "暂无日志" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showLogDialog = false }) { Text("关闭") }
                },
            )
        }

        // Shizuku 增强：无 Root 也能把系统的压制等级调低（Doze 白名单、standby 分级等）
        SectionCard(enterIndex = 5) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (suiMode) "Sui 增强（可选）" else "Shizuku 增强（可选）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "无需 Root。用 Shizuku / Sui 的 adb 身份把系统压制开关一次性调到最有利：" +
                            "加入 Doze 白名单（深度睡眠豁免）、standby 提到 active、解除休眠冻结。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = when (shizukuState) {
                        dev.aigw.app.service.ShizukuBoost.State.Ready -> "已就绪"
                        dev.aigw.app.service.ShizukuBoost.State.NeedPermission -> "待授权"
                        dev.aigw.app.service.ShizukuBoost.State.NotRunning -> "未连接"
                        is dev.aigw.app.service.ShizukuBoost.State.TooOld -> "版本过低"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (shizukuState == dev.aigw.app.service.ShizukuBoost.State.Ready) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Text(
                text = "说明：Shizuku 每次重启后需重新激活（无线调试授权会失效），所以它是「开机后点一次」；" +
                    "无人值守的复活能力仍需 Root 守护。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // shizukuState 是 by remember 委托属性，Kotlin 不能对它做 smart cast，
                // 必须先取到局部变量再 when
                val state = shizukuState
                when (state) {
                    dev.aigw.app.service.ShizukuBoost.State.Ready -> {
                        ChoiceChip("一键增强", selected = false) { viewModel.shizukuBoost() }
                        ChoiceChip("自检", selected = false) { viewModel.shizukuInspect() }
                        ChoiceChip("撤销", selected = false) { viewModel.shizukuRevert() }
                    }
                    dev.aigw.app.service.ShizukuBoost.State.NeedPermission ->
                        ChoiceChip("请求授权", selected = false) { viewModel.shizukuRequestPermission() }
                    dev.aigw.app.service.ShizukuBoost.State.NotRunning ->
                        Text(
                            text = "未检测到 Shizuku 或 Sui。Shizuku 需先开启无线调试并启动服务；" +
                                "Sui 是 Magisk 模块，刷入后重启即可，无需手动启动。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    is dev.aigw.app.service.ShizukuBoost.State.TooOld ->
                        Text(
                            text = "Shizuku 版本过低（v${state.version}），需要 11 以上。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                }
            }
        }

        SectionCard(enterIndex = 6) {
            StatusRow(
                title = "通知权限",
                detail = if (status?.notificationsGranted == true) "已允许，常驻通知可见" else "未允许，后台通知不会显示",
                ok = status?.notificationsGranted == true,
                actionLabel = "去开启",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    launch(context, KeepAlive.notificationSettingsIntent(context), "无法打开通知设置")
                }
            }
            StatusRow(
                title = "电池优化",
                detail = if (status?.batteryUnrestricted == true) {
                    "已加入白名单（不限制耗电）"
                } else {
                    "受系统电池优化限制，锁屏后可能被冻结、端口调不通"
                },
                ok = status?.batteryUnrestricted == true,
                actionLabel = "不限制",
            ) {
                // 部分 ROM 不支持直接请求，失败就退回到电池优化列表页
                if (!launch(context, KeepAlive.batteryOptimizationIntent(context), null)) {
                    launch(context, KeepAlive.batterySettingsIntent(), "无法打开电池优化设置")
                }
            }
            StatusRow(
                title = "后台运行限制",
                detail = if (status?.backgroundRestricted == true) "系统已限制本应用后台运行" else "未被系统限制后台运行",
                ok = status?.backgroundRestricted != true,
                actionLabel = "去设置",
            ) {
                launch(context, KeepAlive.appDetailsIntent(context), "无法打开应用详情")
            }
            StatusRow(
                title = "流量节省",
                detail = if (status?.dataSaverRestricted == true) "已开启流量节省，后台联网可能受限" else "未开启流量节省",
                ok = status?.dataSaverRestricted != true,
                actionLabel = "去设置",
            ) {
                launch(context, KeepAlive.appDetailsIntent(context), "无法打开应用详情")
            }
        }

        SectionCard(enterIndex = 7) {
            Text("厂商后台管理页", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "国产系统默认会限制第三方应用后台。下面的入口会尝试直接打开厂商的后台管理页；" +
                    "打不开就按提示手动设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceChip("打开厂商后台设置", false) {
                val opened = KeepAlive.autoStartIntents().any { launch(context, it, null) }
                if (!opened) {
                    launch(context, KeepAlive.appDetailsIntent(context), "无法打开系统页面")
                }
                Toast.makeText(context, KeepAlive.manualHint(Build.MANUFACTURER), Toast.LENGTH_LONG).show()
            }
            Text(
                text = KeepAlive.manualHint(Build.MANUFACTURER),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "说明：本应用用「常驻前台服务 + 通知」保持进程存活，并持有唤醒锁与 Wi-Fi 锁" +
                "（锁屏后 CPU/Wi-Fi 不被省电挂起，已有 TCP 连接不会断），也可开启「悬浮窗保活」进一步降低后台被回收的概率。" +
                "如果系统把本应用放进电池优化名单或限制后台，锁屏一段时间后进程仍可能被冻结，客户端就会连接失败——" +
                "按上面的检测项逐条处理即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusRow(
    title: String,
    detail: String,
    ok: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        ChoiceChip(actionLabel, selected = false, onClick = onAction)
    }
}

private fun launch(context: Context, intent: Intent, failureMessage: String?): Boolean {
    return try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        if (failureMessage != null) {
            Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
        }
        false
    }
}

/** Root 守护的单行状态：左侧标签、右侧实测结论，不通过时标红。 */
@Composable
private fun RootStatusLine(label: String, ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = if (ok) "✓ " else "✗ ",
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}
