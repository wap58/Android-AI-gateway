package dev.aigw.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aigw.app.service.GatewayTileService
import dev.aigw.app.ui.pushPopSpec
import dev.aigw.app.ui.tabContentSpec
import dev.aigw.app.ui.AppUiState
import dev.aigw.app.ui.ApiSettingsScreen
import dev.aigw.app.ui.AppViewModel
import dev.aigw.app.ui.AppearanceThemeScreen
import dev.aigw.app.ui.CustomProviderScreen
import dev.aigw.app.ui.CreditCenterScreen
import dev.aigw.app.ui.HomeScreen
import dev.aigw.app.ui.KeepAliveScreen
import dev.aigw.app.ui.AccountTransferScreen
import dev.aigw.app.ui.FreeModelsScreen
import dev.aigw.app.ui.LoginBrowserDialog
import dev.aigw.app.ui.LogsScreen
import dev.aigw.app.ui.MeScreen
import dev.aigw.app.ui.ModelsScreen
import dev.aigw.app.ui.ProviderDetailScreen
import dev.aigw.app.ui.ProviderListScreen
import dev.aigw.app.ui.RequestRewriteScreen
import dev.aigw.app.ui.ProviderCreditsScreen
import dev.aigw.app.ui.ProxyScreen
import dev.aigw.app.ui.ResilienceScreen
import dev.aigw.app.ui.SecurityProtectionScreen
import dev.aigw.app.ui.SubPage
import dev.aigw.app.ui.UpdateScreen
import dev.aigw.app.ui.TaskCenterScreen
import dev.aigw.app.ui.UsageScreen
import dev.aigw.app.ui.providers.ProviderUiRegistry
import dev.aigw.app.ui.theme.AiGatewayTheme
import dev.aigw.app.ui.theme.AppColors
import dev.aigw.app.ui.theme.LocalAppColors
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.ScaffoldDefaults
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.aigw.app.ui.LocalBottomContentPadding
import androidx.compose.ui.draw.shadow

class MainActivity : ComponentActivity() {

    private var viewModel: AppViewModel? = null

    private val gatewayToggledReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 磁贴启停后通知界面刷新（磁贴无法直接触达 ViewModel）
            viewModel?.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        registerGatewayToggledReceiver()
        setContent {
            val vm: AppViewModel = viewModel(factory = appViewModelFactory())
            viewModel = vm
            val state by vm.state.collectAsStateWithLifecycle()
            AiGatewayTheme(
                dynamicColor = state.dynamicColor,
                themeMode = state.themeMode,
                appColors = AppColors(state.goldStart, state.goldEnd),
                backgroundOverride = state.backgroundOverride,
            ) {
                AppRoot(vm, state)
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(gatewayToggledReceiver) }
        viewModel = null
        super.onDestroy()
    }

    private fun registerGatewayToggledReceiver() {
        val filter = IntentFilter(GatewayTileService.ACTION_GATEWAY_TOGGLED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gatewayToggledReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(gatewayToggledReceiver, filter)
        }
    }

    private fun appViewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(application) as T
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Filled.Home),
    Providers("账号", Icons.Filled.Person),
    Logs("记录", Icons.Filled.History),
    Me("设置", Icons.Filled.Settings),
}

@Composable
private fun AppRoot(viewModel: AppViewModel, state: AppUiState) {
    var tab by remember { mutableStateOf(Tab.Home) }
    var subPage by remember { mutableStateOf<SubPage?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        if (state.notice.isNotEmpty()) {
            snackbarHostState.showSnackbar(state.notice)
            viewModel.consumeNotice()
        }
    }
    LaunchedEffect(state.busy) {
        if (state.busy.isNotEmpty()) snackbarHostState.showSnackbar(state.busy)
    }

    // 登录前选浏览器：同一个上游的多个账号在同一个浏览器里登录会互相顶掉，
    // 每个账号换一个浏览器就能各自保留 Cookie，不必反复退出重登。
    if (state.pendingLogin != null) {
        LoginBrowserDialog(
            onDismiss = { viewModel.dismissPendingLogin() },
            onPick = { pkg: String -> viewModel.launchLoginIn(pkg) },
        )
    }

    // 系统返回与页内返回按钮同层级：二级页回它的上级，其余回主 tab
    BackHandler(enabled = subPage != null) {
        subPage = when (val page = subPage) {
            is SubPage.ProviderCredits -> SubPage.CreditCenter
            is SubPage.CustomProviderEdit ->
                page.key?.let { SubPage.ProviderDetail(ProviderUiRegistry.CUSTOM_PREFIX + it) }
            else -> null
        }
    }

    // 玻璃采样状态：内容层写入，底栏读取
    val hazeState = remember { dev.chrisbanes.haze.HazeState() }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalBottomContentPadding provides if (state.floatingNavBar) 92.dp else 0.dp,
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 悬浮底栏时让内容铺到屏幕底部，由导航栏自己层叠在上面
        contentWindowInsets = if (state.floatingNavBar) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            if (state.floatingNavBar) {
                FloatingNavBar(tab, subPage, hazeState, onSelect = { item ->
                    subPage = null
                    tab = item
                })
            } else {
            val selectedGold = LocalAppColors.current.goldStart
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = subPage == null && tab == item,
                        onClick = {
                            subPage = null
                            tab = item
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                            selectedIconColor = selectedGold,
                            selectedTextColor = selectedGold,
                            unselectedIconColor = Color(0xFF777777),
                            unselectedTextColor = Color(0xFF777777),
                        ),
                    )
                }
            }
            }
        },
    ) { insets ->
        Box(
            Modifier
                .fillMaxSize()
                // 悬浮底栏时内容铺满全屏，不因 insets 向上抬：
                // 否则底栏背后是一片空白背景，玻璃无像素可采样，看起来完全没模糊
                .then(
                    if (state.floatingNavBar) {
                        Modifier.hazeSource(hazeState)
                    } else {
                        Modifier.padding(bottom = insets.calculateBottomPadding())
                    },
                ),
        ) {
            // 二级页推拉过渡：推入从右滑入，返回向右滑出
            AnimatedContent(
                targetState = subPage,
                transitionSpec = { pushPopSpec() },
                label = "subPage",
            ) { page ->
                when (page) {
                is SubPage.ProviderDetail -> ProviderDetailScreen(
                    providerId = page.providerId,
                    state = state,
                    viewModel = viewModel,
                    onBack = { subPage = null },
                    onEditCustom = { subPage = SubPage.CustomProviderEdit(it.removePrefix("custom:")) },
                )

                                is SubPage.CustomProviderEdit -> CustomProviderScreen(
                    existing = state.customProviders.firstOrNull { it.key == page.key },
                    onSave = {
                        viewModel.saveCustomProvider(it)
                        subPage = SubPage.ProviderDetail(it.providerId)
                    },
                    onDelete = {
                        viewModel.removeCustomProvider(it)
                        subPage = null
                    },
                    onBack = {
                        subPage = page.key?.let { SubPage.ProviderDetail(ProviderUiRegistry.CUSTOM_PREFIX + it) }
                    },
                )

                SubPage.Usage -> UsageScreen(state, viewModel) { subPage = null }
                SubPage.AccountTransfer -> AccountTransferScreen(state, viewModel) { subPage = null }
                SubPage.FreeModels -> FreeModelsScreen(state, onBack = { subPage = null }, onOpenModel = { id -> subPage = SubPage.Models(id) })
                SubPage.TaskCenter -> TaskCenterScreen(state, viewModel) { subPage = null }
                // 账号任务明细由供应商详情内部用局部状态渲染（那里才有 ProviderUiActions），
                // 走到这里说明是异常路径，直接把用户退回上一页。
                is SubPage.ProviderTaskCenter -> subPage = null
                SubPage.CreditCenter -> CreditCenterScreen(
                    state = state,
                    onOpen = { subPage = SubPage.ProviderCredits(it) },
                    onBack = { subPage = null },
                )
                is SubPage.ProviderCredits -> ProviderCreditsScreen(
                    providerId = page.providerId,
                    state = state,
                    viewModel = viewModel,
                    onBack = { subPage = SubPage.CreditCenter },
                )
                SubPage.KeepAlive -> KeepAliveScreen(state, viewModel) { subPage = null }
                SubPage.Proxy -> ProxyScreen(state, viewModel) { subPage = null }
                SubPage.ApiSettings -> ApiSettingsScreen(state, viewModel) { subPage = null }
                SubPage.SecurityProtection -> SecurityProtectionScreen(state, viewModel) { subPage = null }
                SubPage.Resilience -> ResilienceScreen(state, viewModel) { subPage = null }
                SubPage.RequestRewrite -> RequestRewriteScreen(state, viewModel) { subPage = null }
                SubPage.AppearanceTheme -> AppearanceThemeScreen(state, viewModel) { subPage = null }
                is SubPage.Models -> ModelsScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = { subPage = null },
                    // ★ 必须用 when 的绑定值 page，不能用外层的 subPage 强转：
                    //   AnimatedContent 退出动画期间 subPage 已置 null，强转会 NPE。
                    highlightModel = page.highlight,
                )
                SubPage.Update -> UpdateScreen(state, viewModel) { subPage = null }
                null -> MainTabs(
                    viewModel = viewModel,
                    state = state,
                    tab = tab,
                    onTab = { newTab ->
                        subPage = null
                        tab = newTab
                    },
                    onSubPage = { subPage = it },
                )
                }
            }
        }
    }
    }

    state.updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdate() },
            title = {
                // 远端版本形如 0.0.9.27（末段是 versionCode），标题只展示主版本三段
                val parts = info.version.split('.')
                val shown = if (parts.size > 3) parts.dropLast(1).joinToString(".") else info.version
                Text("发现新版本 $shown")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "更新日志",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (info.notes.isEmpty()) {
                        Text(
                            "该版本没有提供更新说明。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = info.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // 应用内下载 + 自动拉起安装器；没有直链时才退回浏览器
                    viewModel.startInAppUpdate()
                    viewModel.dismissUpdate()
                }) { Text(if (info.apkUrl.isEmpty()) "前往下载" else "立即更新") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdate() }) { Text("稍后") }
            },
        )
    }
}

/** 主 Tab 页面：切换时按索引方向做小幅滑移 + 淡入淡出。 */
@Composable
private fun MainTabs(
    viewModel: AppViewModel,
    state: AppUiState,
    tab: Tab,
    onTab: (Tab) -> Unit,
    onSubPage: (SubPage?) -> Unit,
) {
    AnimatedContent(
        targetState = tab,
        transitionSpec = { tabContentSpec { it.ordinal } },
        label = "tab",
    ) { page ->
        when (page) {
            Tab.Home -> HomeScreen(
                state = state,
                viewModel = viewModel,
                openProviders = { onTab(Tab.Providers) },
                openProviderDetail = { onSubPage(SubPage.ProviderDetail(it)) },
                openCustomProvider = { onSubPage(SubPage.CustomProviderEdit(null)) },
                openModels = { onSubPage(SubPage.Models()) },
                openCredits = { onSubPage(SubPage.CreditCenter) },
                openUsage = { onSubPage(SubPage.Usage) },
                openLogs = { onTab(Tab.Logs) },
                openTaskCenter = { onSubPage(SubPage.TaskCenter) },
                openFreeModels = { onSubPage(SubPage.FreeModels) },
            )
            Tab.Providers -> ProviderListScreen(
                state = state,
                onOpen = { onSubPage(SubPage.ProviderDetail(it)) },
                onAddCustom = { onSubPage(SubPage.CustomProviderEdit(null)) },
                onOpenModels = { onSubPage(SubPage.Models()) },
            )
            Tab.Logs -> LogsScreen(state, viewModel)
            Tab.Me -> MeScreen(state, viewModel, open = { onSubPage(it) })
        }
    }
}

/**
 * 悬浮底栏：iOS 26 液态玻璃胶囊（移植自上游 2.37.1）。
 *
 * 形状是**胶囊**：圆角半径 = 高度的一半，所以用 CircleShape 而不是固定 dp；
 * 宽度按内容收缩后居中，不铺满屏幕 —— 铺满再加圆角矩形看起来完全不是那个东西。
 *
 * 玻璃靠 Haze 把内容层当采样源做背景模糊 + 饱和度提升 + 色调叠加，
 * 再自己画顶部高光与内描边（高光是玻璃的关键，没有它只是一块毛玻璃）。
 */
@Composable
private fun FloatingNavBar(
    tab: Tab,
    subPage: SubPage?,
    haze: dev.chrisbanes.haze.HazeState,
    onSelect: (Tab) -> Unit,
) {
    val selectedGold = LocalAppColors.current.goldStart
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.30f),
                    spotColor = Color.Black.copy(alpha = 0.40f),
                )
                .clip(CircleShape)
                .hazeEffect(
                    state = haze,
                    style = dev.chrisbanes.haze.HazeStyle(
                        backgroundColor = MaterialTheme.colorScheme.surface.copy(
                            alpha = if (isLight) 0.40f else 0.24f,
                        ),
                        tints = listOf(
                            dev.chrisbanes.haze.HazeTint(
                                color = MaterialTheme.colorScheme.surface.copy(
                                    alpha = if (isLight) 0.26f else 0.18f,
                                ),
                            ),
                        ),
                        blurRadius = 28.dp,
                        noiseFactor = 0.05f,
                    ),
                )
                .drawWithContent {
                    drawContent()
                    // 胶囊内描边：玻璃边缘的一圈亮线
                    val border = 1.dp.toPx()
                    drawRoundRect(
                        color = Color.White.copy(alpha = if (isLight) 0.60f else 0.20f),
                        topLeft = Offset(border / 2f, border / 2f),
                        size = Size(size.width - border, size.height - border),
                        cornerRadius = CornerRadius(size.height / 2f),
                        style = Stroke(width = border),
                    )
                    // 顶部高光：胶囊上半部分被照亮
                    val highlightHeight = size.height * 0.55f
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (isLight) 0.55f else 0.18f),
                                Color.Transparent,
                            ),
                            startY = 0f,
                            endY = highlightHeight,
                        ),
                        size = Size(size.width, highlightHeight),
                    )
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { item ->
                    val selected = subPage == null && tab == item
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            // 选中项用一层极淡的亮色「胶囊内胶囊」，而不是改文字颜色
                            .background(
                                if (selected) {
                                    selectedGold.copy(alpha = if (isLight) 0.16f else 0.20f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable { onSelect(item) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected) selectedGold else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp),
                        )
                        // 只给选中项显示文字，未选中只留图标：这才是 iOS 胶囊栏的形态
                        if (selected) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = selectedGold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
