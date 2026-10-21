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
                // Root 守护状态要回读，不能只看「按钮点过没有」。
                // 回到本页时强制重探 root 权限：用户可能刚在 Root 管理器里授权完，
                // 不强制的话要等 30 秒缓存过期卡片才出现。
                viewModel.refreshRootKeeperState(forceSuCheck = true)
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
            // running 顺带刷一下：用户可能刚用控制中心磁贴停/开了网关，
            // 本页的「当前服务未启动」提示与悬浮窗联动文字要跟着变。
            // 只读 engine.isRunning()（内存标志），零成本。
            viewModel.refreshRunning()
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
                    Text("后台自动开启", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "应用退到后台并停留满 1 分钟后，若网关没在运行就自动拉起来。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.bgAutoStart,
                    onCheckedChange = { viewModel.setBgAutoStart(it) },
                )
            }
            Text(
                text = "说明：你手动点过「停止服务」就不会被自动拉起，关掉网关就是真的关掉；" +
                    "回到前台会重新计时，不会打断当前操作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(enterIndex = 3) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("开机自启", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "系统启动后恢复到上次的状态：上次是开着的就自动拉起网关，上次是关着的就保持关闭。",
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

        SectionCard(enterIndex = 4) {
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

        // Root 卡片只在「确实拿得到 root」时出现。
        // 没有 root / 拿不到授权时整块隐藏：否则界面上永远挂着一排红色未通过项，
        // 用户既做不了什么，也看不懂为什么全是叉。
        // 首次进入时 rootKeeper 还是 null（尚未探测），此时先不渲染，探测完再决定显隐。
        if (state.rootKeeper?.suAvailable == true) SectionCard(enterIndex = 5) {
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
                text = "守护脚本每 5 秒探活一次 http://127.0.0.1:${state.port}/healthz：进程没了立刻拉起，" +
                    "进程在但端口不通连续 2 次就重启；期望状态持久化在 /data/adb/tyd/，重启后照样恢复。" +
                    "脚本固定放在 /data/adb/tyd/，一个脚本适配 Magisk / KernelSU / APatch / Sui 及纯 su，" +
                    "不用按方案换目录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 实测状态：装没装上、跑没跑起来，必须回读，不能只看按钮点过没有
            val rk = state.rootKeeper
            // 探针没跑出结果时，先提示「读取失败」而不是展示一整排红叉 ——
            // 那些红叉会把「守护其实在跑」误导成「彻底坏了」。
            if (rk != null && rk.probeFailed) {
                Text(
                    text = "⚠ 状态读取失败（探针未能执行）。已知守护进程：" +
                        (if (rk.processAlive) "在跑（PID ${rk.pid}）" else "未检测到") +
                        "。其余项目下轮重试；若持续失败请点「重装」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
                // 直接显示守护脚本实际使用的探活通道（curl / busybox / toybox / /proc 兜底），
                // 排障时一眼能看出用的是哪一级，不必再猜是工具缺失还是网关真死了。
                RootStatusLine(
                    label = "探活通道",
                    ok = rk.probeWorks,
                    text = if (rk.probeWorks) "可用（${rk.probeOutput.trim()}）"
                    else "不可用（脚本 probe 输出「${rk.probeOutput.trim().ifEmpty { "空" }}」）",
                )
                RootStatusLine(
                    label = "开机目录",
                    ok = rk.registeredDirs.isNotEmpty(),
                    text = if (rk.registeredDirs.isEmpty()) "无（系统没有 service.d，重启后需重开应用）"
                    else rk.registeredDirs.joinToString("、") { it.substringAfterLast('/') },
                )
                RootStatusLine(
                    label = "记忆状态",
                    ok = rk.desiredRunning != false,
                    text = when (rk.desiredRunning) {
                        true -> "运行中（重启后自动恢复）"
                        false -> "已停止（守护待命，不会自己拉起）"
                        null -> "未记录（首次写入后生效）"
                    },
                )
                RootStatusLine(
                    label = "脚本版本",
                    ok = !rk.outdated,
                    text = when {
                        rk.scriptVersion.isEmpty() -> "读取失败（可能未安装或权限不足）"
                        rk.outdated -> "${rk.scriptVersion} → 需重装（APK 内置 ${rk.bundledVersion}）"
                        else -> rk.scriptVersion
                    },
                )
                RootStatusLine(
                    label = "整体",
                    ok = rk.ok,
                    text = if (rk.ok) "已生效（被杀会自动复活）" else "未生效，请看上面哪项没通过",
                )
                // 日志只露 3 行：守护每 5 秒一轮，完整日志很快就能把卡片撑很长。
                // 整块可点，末尾「查看更多」进弹窗看完整的。
                if (rk.logTail.isNotEmpty()) {
                    val lines = rk.logTail.lines().filter { it.isNotBlank() }
                    val preview = lines.takeLast(3).joinToString("\n")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                fullLogText = ""
                                showLogDialog = true
                                viewModel.loadFullRootKeeperLog { fullLogText = it }
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

            // 脚本有更新时直接提示：否则用户装了新版 APK 却还在跑旧守护，
            // 表现为「修好的问题又出现了」，极难自查。
            if (rk.outdated) {
                Text(
                    text = "⚠ 守护脚本有更新（${rk.scriptVersion} → ${rk.bundledVersion}），点「重装」即可热替换，不必重启手机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
        SectionCard(enterIndex = 6) {
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

        SectionCard(enterIndex = 7) {
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

        SectionCard(enterIndex = 8) {
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
