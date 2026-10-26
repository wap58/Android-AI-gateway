package dev.aigw.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.app.ui.providers.ProviderUiRegistry
import dev.aigw.app.ui.login.WebCookieLoginActivity
/**
 * 供应商详情：登录入口 + 账号列表 + 专属设置。
 *
 * 账号卡片里的「专属信息区」由该供应商自己的 UI 组件渲染（[ProviderUiRegistry]），
 * 所以这里不需要认识设备指纹、签到、兑换码这些概念。
 */
@Composable
fun ProviderDetailScreen(
    providerId: String,
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onEditCustom: (String) -> Unit,
) {
    val ui = remember(providerId) { ProviderUiRegistry.require(providerId) }
    val info = state.providerOf(providerId)
    val allAccounts = state.accountsOf(providerId)
    // ★ 有区域概念的供应商（Qoder）按「当前选中区域」过滤账号列表：
    //   选国际版就只显示国际版账号，国内版账号不混进来。
    //   没有区域概念的供应商 regionsOf 返回空，原样显示全部账号。
    // 按当前选中区域过滤账号列表（和 Trae / WorkBuddy 的行为一致：
    // 选中哪个区就只显示那个区的账号）。区域 id 已统一成 cn / global。
    // 区域过滤。
    //
    // ★ 必须用 **Compose 状态** 承载 selectedRegion：之前直接读
    //   viewModel.selectedRegionOf()（普通函数调用），值变了但 AppUiState
    //   没变 → 不触发重组 → 界面永远停在旧区域，表现为「切到国内版还显示国际账号」。
    val regions = remember(providerId) { viewModel.regionsOf(providerId) }

    // ★ 当前区域以 settings 为唯一来源。
    //   之前用 remember(providerId) 存本地副本：进出页面时本地值与 settings 会脱节
    //   （只有 providerId 变化才重新初始化），表现为「停在哪个区就哪个区有账号，
    //   点别的区就没有」，退出重进又恢复。
    //   现在 settings 变化会经 refresh 进入 state，state 变化触发重组，直接读最新值。
    // ★ 区域从快照里的 ProviderInfo 读：settings 一变就会经 refresh 进 state，
    //   state 变化触发重组，界面自然跟上。之前用本地 remember 副本，
    //   只在 providerId 变化时才初始化，退出重进时与 settings 脱节。
    val selectedRegion = info?.region.orEmpty()

    val onRegionChanged: (String) -> Unit = { r ->
        viewModel.updateProviderOption(providerId, "region", r)
    }
    // ★ 只读快照里的 region，不再实时查 engine：
    //   engine 与 state 是两个数据源，刷新不同步，实时查会漏账号（时有时无）。
    val accounts = if (regions.size > 1 && selectedRegion.isNotEmpty()) {
        allAccounts.filter { it.region == selectedRegion }
    } else {
        allAccounts
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pendingCookieProvider by remember { mutableStateOf("") }

    // 内嵌网页抓 cookie 登录：需要读 WebView 的 cookie 存储，必须留在 App 内
    val cookieLoginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val cookie = result.data?.getStringExtra(WebCookieLoginActivity.EXTRA_RESULT_COOKIE).orEmpty()
        val cookieAlt = result.data?.getStringExtra(WebCookieLoginActivity.EXTRA_RESULT_COOKIE_ALT).orEmpty()
        val targetProvider = pendingCookieProvider
        pendingCookieProvider = ""
        if (cookie.isNotEmpty() && targetProvider.isNotEmpty()) {
            // 两份凭证一起交：kdocs（对话专业版）+ wps.cn（模型/签到/额度）
            // 用不可见分隔符拼接两份：kdocs（对话用）在后面，wps.cn（业务用）在前
            val merged = if (cookieAlt.isEmpty()) cookie else "$cookieAlt\u0001$cookie"
            viewModel.importCredentials(targetProvider, merged)
        } else {
            viewModel.notice("没有读到登录 cookie，请重新登录")
        }
    }

    // 缓存回调集合：30+ 个 lambda 每次重组重建会让账号卡片永远无法跳过重组。
    // creditPacks/taskLists 随 state 变化，用 rememberUpdatedState 读取最新值。
    val creditPacks = rememberUpdatedState(state.creditPacks)
    val taskLists = rememberUpdatedState(state.taskLists)
    val pointsBills = rememberUpdatedState(state.pointsBills)
    val actions = remember(viewModel, providerId) {
        ProviderUiActions(
            // 登录一律交给系统浏览器：本机 WebView 渲染异常（整页缩放错乱、输入框文字镜像），
            // 网关会在本地临时监听回调端口接住登录结果。
            onWebLogin = { id, region -> viewModel.beginBrowserLogin(id, region) },
            onDeviceLogin = { id, region -> viewModel.beginDeviceLogin(id, region) },
            onSmsLogin = { id, phone, code, ccode, onResult ->
                if (code.isEmpty()) {
                    viewModel.sendSmsCode(id, phone, ccode, onResult)
                } else {
                    viewModel.smsLogin(id, phone, code, ccode, onResult)
                }
            },
            onCookieLogin = { id, startUrl, cookieHost, cookieName, ssoUrl, verifyUrl, clearFirst ->
                pendingCookieProvider = id
                val intent = android.content.Intent(ctx, WebCookieLoginActivity::class.java).apply {
                    putExtra(WebCookieLoginActivity.EXTRA_START_URL, startUrl)
                    putExtra(WebCookieLoginActivity.EXTRA_COOKIE_HOST, cookieHost)
                    putExtra(WebCookieLoginActivity.EXTRA_COOKIE_NAME, cookieName)
                    putExtra(WebCookieLoginActivity.EXTRA_CLEAR_FIRST, clearFirst)
                    putExtra(WebCookieLoginActivity.EXTRA_SSO_URL, ssoUrl)
                    putExtra(WebCookieLoginActivity.EXTRA_VERIFY_URL, verifyUrl)
                }
                cookieLoginLauncher.launch(intent)
            },
            onImport = { id, raw -> viewModel.importCredentials(id, raw) },
            onAddCustomAccount = { id, nickname, apiKey, models -> viewModel.addCustomAccount(id, nickname, apiKey, models) },
            onRenameAccount = { id, uid, nickname -> viewModel.renameAccount(id, uid, nickname) },
            accountApiKeyOf = { id, uid -> viewModel.accountApiKey(id, uid) },
            customModelsOf = { id -> viewModel.customModels(id) },
            customBaseUrlOf = { id -> viewModel.customBaseUrl(id) },
            onFetchCustomModels = { baseUrl, apiKey, onResult ->
                viewModel.fetchCustomModels(baseUrl, apiKey, onResult)
            },
            onRefreshCredits = { id, uid -> viewModel.refreshCredits(id, uid) },
            onAction = { id, uid, action, payload -> viewModel.performAction(id, uid, action, payload) },
            onToggleAccount = { id, uid, enabled -> viewModel.setAccountEnabled(id, uid, enabled) },
            onRemoveAccount = { id, uid -> viewModel.removeAccount(id, uid) },
            onRegionPicked = onRegionChanged,
            onUpdateSecret = { id, uid, secret, notice ->
                viewModel.updateAccountSecret(id, uid, secret)
                viewModel.notice(notice)
            },
            secretOf = { id, uid -> viewModel.secretOf(id, uid) },
            deviceIdOf = { id, uid -> viewModel.deviceIdOf(id, uid) },
            regionOf = { id, uid -> viewModel.accountRegion(id, uid) },
            providerOptionOf = { id, key, fallback -> viewModel.providerOption(id, key, fallback) },
            onUpdateProviderOption = { id, key, value -> viewModel.updateProviderOption(id, key, value) },
            creditPacksOf = { id, uid ->
                creditPacks.value["$id/$uid"].orEmpty()
            },
            onLoadCreditPacks = { id, uid -> viewModel.loadCreditPacks(id, uid) },
            taskListOf = { id, uid -> taskLists.value["$id/$uid"] },
            onLoadTaskList = { id, uid -> viewModel.loadTaskList(id, uid) },
            pointsBillsOf = { id, uid -> pointsBills.value["$id/$uid"] },
            onLoadPointsBills = { id, uid -> viewModel.loadPointsBills(id, uid) },
            onRegenerateDeviceId = { id, uid -> viewModel.regenerateDeviceId(id, uid) },
            onSetDeviceId = { id, uid, value -> viewModel.setDeviceId(id, uid, value) },
            onNotice = { viewModel.notice(it) },
        )
    }

    PageScaffold(
        title = info?.displayName ?: providerId,
        subtitle = "${accounts.size} 个账号 · 可用 ${accounts.count { it.usable }}",
        leading = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(6.dp),
            )
        },
        actions = {
            if (providerId.startsWith(ProviderUiRegistry.CUSTOM_PREFIX)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "编辑供应商",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onEditCustom(providerId) }
                        .padding(6.dp),
                )
            }
        },
    ) {
        // LoginEntry 自带卡片，这里不再包一层，避免双层卡片
        ui.LoginEntry(accounts, actions)

        // 账号列表由供应商组件自管（如 WorkBuddy 按国内外分区）时，不再重复渲染通用账号卡
        if (accounts.isNotEmpty() && !ui.managesOwnAccounts) {
            SectionCard(enterIndex = 0) {
                SectionLabel("账号")
                PagedAccountList(accounts, keyOf = { it.uid }) { pageIndex, account ->
                    AccountRow(account, ui, actions, index = pageIndex)
                }
                OutlineActionButton("刷新额度") { actions.onRefreshCredits(providerId, null) }
            }
        }

        ui.SettingsSection(viewModel.providerSettings(providerId)) { updated ->
            viewModel.updateProviderSettings(providerId, updated)
        }
    }
}

@Composable
private fun AccountRow(
    account: dev.aigw.core.pool.AccountStatus,
    ui: dev.aigw.app.ui.providers.ProviderUi,
    actions: ProviderUiActions,
    index: Int,
) {
    AccountCardShell(
        account = account,
        providerId = account.providerId,
        actions = actions,
        modifier = Modifier.fillMaxWidth().staggeredAppear(index),
        extra = { ui.AccountExtra(account, actions) },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LinkText("删除账号") { actions.onRemoveAccount(account.providerId, account.uid) }
            }
        },
    )
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
