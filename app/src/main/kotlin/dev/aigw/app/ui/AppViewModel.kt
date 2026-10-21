package dev.aigw.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.TileService
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.app.data.GatewayIntent
import dev.aigw.app.data.KeepAlive
import dev.aigw.app.data.KeepAliveStatus
import dev.aigw.app.data.ModelSnapshotStore
import dev.aigw.app.gatewayEngine
import dev.aigw.app.ui.theme.ThemeMode
import dev.aigw.app.ui.theme.defaultAppColors
import dev.aigw.app.ui.theme.formatHex
import dev.aigw.app.ui.theme.parseHexColor
import dev.aigw.app.service.GatewayService
import dev.aigw.app.service.GatewayTileService
import dev.aigw.core.gateway.BatchActionResult
import dev.aigw.core.gateway.BatchProgress
import dev.aigw.core.gateway.BatchTarget
import dev.aigw.core.gateway.CreditRefreshResult
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.LoginOutcome
import dev.aigw.core.gateway.ModelExposureSettings
import dev.aigw.core.gateway.ModelsCatalog
import dev.aigw.core.gateway.ProviderInfo
import dev.aigw.core.gateway.ProviderModelsChunk
import dev.aigw.core.gateway.ProviderSettings
import dev.aigw.core.gateway.ProxySettings
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.pool.PoolSummary
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.custom.CustomProvider
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.provider.trae.TraeRegion
import dev.aigw.core.provider.trae.TraeProvider
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.LogLine
import dev.aigw.core.usage.StorageReport
import dev.aigw.core.usage.UsageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 验证码重发间隔（秒）：上游有频率限制，连点只会被拒。 */
private const val SMS_RESEND_INTERVAL_MS = 60_000L

/** 金色渐变落盘去抖：取色拖动每帧都调 setGoldColors，攒一小段再写加密存储。 */
private const val GOLD_PERSIST_DEBOUNCE_MS = 300L

/** 项目仓库地址（GitHub）。 */
private const val PROJECT_URL = "https://github.com/wap58/Android-AI-gateway"

/** 上游原项目（本仓库是它的 fork，界面里让用户自己选去哪个）。 */
private const val UPSTREAM_URL = "https://github.com/xibai6668/Android-AI-gateway"

/** GitHub 最新 Release 接口（API 地址；网页地址 .../releases/latest 返回 HTML，不能当 JSON 解析）。 */
private const val RELEASES_API = "https://api.github.com/repos/wap58/Android-AI-gateway/releases/latest"
/**
 * 测试版通道：CI 的固定 tag `ci-latest`（每次 CI 构建覆盖同一份资产）。
 * 不遍历 Release 列表、不看 prerelease 标记 —— 一个地址永远指向最新的 CI 包。
 */
private const val CI_RELEASE_API = "https://api.github.com/repos/wap58/Android-AI-gateway/releases/tags/ci-latest"

/** GitHub API 要求请求带 User-Agent。 */
private const val UPDATE_UA = "ai-gateway-android"

/** 外观偏好：动态取色、明暗模式、页面底色与金色渐变两端。 */
class AppearanceStore(private val store: KeyValueStore) {
    fun dynamicColor(): Boolean = store.read(KEY_DYNAMIC) == "1"

    /**
     * 落盘（同步写加密存储，必须在 IO 线程调用）：界面先更新内存状态，
     * 这里只负责持久化。拖动取色每帧都会调，落盘时机由调用方去抖。
     */
    fun setDynamicColor(enabled: Boolean) {
        store.write(KEY_DYNAMIC, if (enabled) "1" else "0")
    }

    fun themeMode(): ThemeMode = when (store.read(KEY_MODE)) {
        "light" -> ThemeMode.Light
        "dark" -> ThemeMode.Dark
        else -> ThemeMode.System
    }

    fun setThemeMode(mode: ThemeMode) {
        store.write(
            KEY_MODE,
            when (mode) {
                ThemeMode.Light -> "light"
                ThemeMode.Dark -> "dark"
                ThemeMode.System -> "system"
            },
        )
    }

    /** 页面底色覆盖；null 或空串表示无覆盖（用默认底色）。 */
    fun backgroundHex(): String? = store.read(KEY_BG)?.takeIf { it.isNotBlank() }

    fun setBackgroundHex(hex: String?) {
        if (hex.isNullOrBlank()) store.delete(KEY_BG) else store.write(KEY_BG, hex)
    }

    fun goldStartHex(): String = store.read(KEY_GOLD_START) ?: DEFAULT_GOLD_START

    fun setGoldStartHex(hex: String) {
        store.write(KEY_GOLD_START, hex)
    }

    fun goldEndHex(): String = store.read(KEY_GOLD_END) ?: DEFAULT_GOLD_END

    fun setGoldEndHex(hex: String) {
        store.write(KEY_GOLD_END, hex)
    }

    /** 清空全部自定义外观，恢复默认。 */
    fun reset() {
        store.delete(KEY_MODE)
        store.delete(KEY_BG)
        store.delete(KEY_GOLD_START)
        store.delete(KEY_GOLD_END)
    }

    companion object {
        const val DEFAULT_GOLD_START = "#D4A843"
        const val DEFAULT_GOLD_END = "#B88A20"

        private const val KEY_DYNAMIC = "settings/appearance.dynamicColor"
        private const val KEY_MODE = "settings/appearance.themeMode"
        private const val KEY_BG = "settings/appearance.backgroundHex"
        private const val KEY_GOLD_START = "settings/appearance.goldStartHex"
        private const val KEY_GOLD_END = "settings/appearance.goldEndHex"
    }
}

/** 更新通道。 */
enum class UpdateChannel(val label: String, val detail: String) {
    /** 公开版：只跟随正式 Release（非预发布）。 */
    Release("公开版", "从 Release 获取，稳定可日常使用"),

    /** 测试版：直接跟随 CI 构建（每次推送都用 ci-latest 覆盖发布），更新最勤。 */
    Beta("测试版", "跟随 CI 构建，每改一次就有一个新包，可能带问题"),
    ;

    companion object {
        fun parse(raw: String?): UpdateChannel =
            if (raw == "beta") Beta else Release
    }
}

/** 更新检查偏好与上次检查日期。 */
class UpdateStore(private val store: KeyValueStore) {
    /** 是否自动检查更新，默认开启。 */
    fun autoCheck(): Boolean = store.read(KEY_AUTO) != "0"

    fun setAutoCheck(enabled: Boolean) {
        store.write(KEY_AUTO, if (enabled) "1" else "0")
    }

    /** 更新通道，默认公开版（与既有行为一致，老用户升级后无感）。 */
    fun channel(): UpdateChannel = UpdateChannel.parse(store.read(KEY_CHANNEL))

    fun setChannel(channel: UpdateChannel) {
        store.write(KEY_CHANNEL, if (channel == UpdateChannel.Beta) "beta" else "release")
    }

    /** 上次自动检查的日期（epoch day），从未检查过返回 Long.MIN_VALUE。 */
    fun lastCheckEpochDay(): Long = store.read(KEY_LAST)?.toLongOrNull() ?: Long.MIN_VALUE

    fun setLastCheckEpochDay(day: Long) {
        store.write(KEY_LAST, day.toString())
    }

    private companion object {
        const val KEY_AUTO = "settings/update.autoCheck"
        const val KEY_CHANNEL = "settings/update.channel"
        const val KEY_LAST = "settings/update.lastCheckEpochDay"
    }
}

/** 检查更新发现的新版本信息。 */
/**
 * 远端最新版本信息。
 *
 * @param apkUrl Release 里 APK 资产的直链；为空时只能跳浏览器下载
 */
data class UpdateInfo(
    val version: String,
    val url: String,
    val notes: String,
    val apkUrl: String = "",
    /** 该 Release 的发布时间（毫秒）；0 表示未知。CI 通道靠它识别「同版本号但更新的构建」。 */
    val publishedAt: Long = 0L,
)

sealed interface ModelTestStatus {
    data object Testing : ModelTestStatus
    data class Success(val latencyMs: Long) : ModelTestStatus
    data class Failed(val reason: String) : ModelTestStatus
}

/**
 * 单个供应商模型目录的流式加载状态（键为 providerId，见 [AppUiState.modelLoadStates]）。
 * 模型页可用它画骨架屏/逐组点亮的动画，而不必等全部供应商拉完。
 */
sealed interface ModelLoadState {
    /** 已发起、尚未就绪。 */
    data object Loading : ModelLoadState
    /** 就绪，[count] 为该供应商贡献的模型条数（区域型供应商按区域拆分后合计）。 */
    data class Done(val count: Int) : ModelLoadState
    /** 拉取失败，[reason] 为异常消息或供应商自报的目录错误。 */
    data class Failed(val reason: String) : ModelLoadState
}

/**
 * 批量动作进行中的进度（供 UI 显示「已完成 X/N（当前：某账号）」）。
 */
@Immutable
data class BatchProgressUi(val done: Int, val total: Int, val current: String)

/** 批量结果里的单个失败账号条目。 */
@Immutable
data class BatchItem(val account: String, val message: String)

/**
 * 一次批量动作的结构化结果：UI 据此展示「成功 N 个 / 失败 M 个」+ 逐条原因，
 * 而不是把整批失败拼成一条读不完的字符串。
 */
@Immutable
data class BatchOutcome(
    val title: String,
    val succeeded: Int,
    val failed: Int,
    val items: List<BatchItem>,
)

/**
 * 界面状态的唯一快照。
 *
 * 标 [Immutable]：字段虽含 List/Map，但都是每次整体替换、不再就地修改，
 * 让 Compose 能把接收它的页面当作稳定参数，字段未变时跳过重组。
 */
@Immutable
data class AppUiState(
    val running: Boolean = false,
    val port: Int = GatewaySettings.DEFAULT_PORT,
    val localUrl: String = "",
    val lanUrls: List<String> = emptyList(),
    val pool: PoolSummary = PoolSummary(0, 0, 0, 0, 0, 0),
    val today: UsageStats = UsageStats(),
    /** 最近 30 天的每日聚合（下标 0 最早、末尾今天）。 */
    val daily: List<UsageStats> = emptyList(),
    /** 全部记录汇总（今日 + 历史）。 */
    val total: UsageStats = UsageStats(),
    val providers: List<ProviderInfo> = emptyList(),
    val accounts: List<AccountStatus> = emptyList(),
    val customProviders: List<CustomProviderConfig> = emptyList(),
    val models: List<RoutedModel> = emptyList(),
    val modelsError: String = "",
    /** 模型目录是否正在流式加载（各供应商就绪即增量写入 [models]）。 */
    val modelsLoading: Boolean = false,
    /** 进行中这轮加载里每个供应商的状态，键为 providerId；加载结束后保留终态供回看。 */
    val modelLoadStates: Map<String, ModelLoadState> = emptyMap(),
    /** 模型测速状态，键为 `model.fullId`。 */
    val modelTestLatencies: Map<String, ModelTestStatus> = emptyMap(),
    val calls: List<CallRecord> = emptyList(),
    /** 存储中的记录条数（含未载入内存的）。 */
    val storedCalls: Int = 0,
    /** 已拉取的额度包明细，键为 `providerId/uid`。 */
    val creditPacks: Map<String, List<QuotaPack>> = emptyMap(),
    /** 已拉取的成长任务明细，键为 `providerId/uid`。 */
    val taskLists: Map<String, dev.aigw.core.provider.ProviderTaskListView> = emptyMap(),
    /** 已拉取的积分流水明细，键为 `providerId/uid`。 */
    val pointsBills: Map<String, dev.aigw.core.provider.PointsBillListView> = emptyMap(),
    val logs: List<LogLine> = emptyList(),
    val settings: GatewaySettings = GatewaySettings(),
    /** 代理设置。 */
    val proxy: ProxySettings = ProxySettings(),
    val dynamicColor: Boolean = false,
    /** 明暗模式。 */
    val themeMode: ThemeMode = ThemeMode.System,
    /** 页面底色覆盖；null 表示用默认底色。 */
    val backgroundOverride: Color? = null,
    /** 金色渐变起始色。 */
    val goldStart: Color = defaultAppColors.goldStart,
    /** 金色渐变结束色（也是金色文字色）。 */
    val goldEnd: Color = defaultAppColors.goldEnd,
    val keepAlive: KeepAliveStatus? = null,
    /** 存储占用明细（含各类记录与设置）。 */
    val storage: StorageReport? = null,
    /** 安全防护与风控设置。 */
    val security: dev.aigw.core.security.SecuritySettings = dev.aigw.core.security.SecuritySettings(),
    /** 可选的悬浮窗保活是否开启（需「显示在其他应用上层」权限）。 */
    val overlayKeepAlive: Boolean = false,
    /** 开机自启：系统启动后自动拉起网关服务。 */
    val autoStart: Boolean = false,
    /** 后台自动开启：退到后台满 1 分钟且网关没在跑时自动拉起。 */
    val bgAutoStart: Boolean = true,
    /** 隐藏多任务：离开应用后从最近任务列表移除。 */
    val hideRecents: Boolean = false,
    /** 今天是否已签到成功（成功则首页按钮置灰）。 */
    val checkedInToday: Boolean = false,
    /** 上次签到结果文案（成功/失败都会写，界面直接展示）。 */
    val checkinStatusText: String = "",
    /**
     * Root 守护的实测状态（null 表示还没查过）。
     *
     * 不能靠「点了安装按钮」就认为装好了 —— 必须回读进程与脚本落盘情况，
     * 否则用户装完仍然一脸茫然（这正是之前的缺陷）。
     */
    val rootKeeper: dev.aigw.app.service.RootKeeper.Verify? = null,
    /** 模型开放名单：只对外暴露勾选的模型。 */
    val modelExposure: dev.aigw.core.gateway.ModelExposureSettings = dev.aigw.core.gateway.ModelExposureSettings(),
    /** 故障转移与并发配置。 */
    val failover: dev.aigw.core.failover.ModelFailoverSettings = dev.aigw.core.failover.ModelFailoverSettings(),
    /** 出网取证记录列表。 */
    val evidences: List<dev.aigw.core.security.OutboundEvidence> = emptyList(),
    /** 当前生效的脱敏规则词汇数。 */
    val sanitizerRulesCount: Int = 0,
    val busy: String = "",
    val notice: String = "",
    /** 批量动作进行中的进度；非空时 UI 显示「已完成 X/N（当前：某账号）」。 */
    val batchProgress: BatchProgressUi? = null,
    /** 批量动作结束后要展示的结构化结果；非空时弹结果面板，逐条列出失败原因。 */
    val batchOutcome: BatchOutcome? = null,
    /** 是否正在检查更新。 */
    val updateChecking: Boolean = false,
    /** 检查到的新版本；非空时界面弹更新窗。 */
    val updateInfo: UpdateInfo? = null,
    /** 是否自动检查更新（启动时每天最多一次）。 */
    val autoCheckUpdate: Boolean = true,
    /** 更新通道：公开版 / 测试版。 */
    val updateChannel: UpdateChannel = UpdateChannel.Release,
) {
    /** 某个供应商的账号。 */
    fun accountsOf(providerId: String): List<AccountStatus> = accounts.filter { it.providerId == providerId }

    fun providerOf(providerId: String): ProviderInfo? = providers.firstOrNull { it.id == providerId }
}

private class Snapshot(
    val running: Boolean,
    val port: Int,
    val localUrl: String,
    val lanUrls: List<String>,
    val pool: PoolSummary,
    val today: UsageStats,
    val daily: List<UsageStats>,
    val total: UsageStats,
    val providers: List<ProviderInfo>,
    val accounts: List<AccountStatus>,
    val customProviders: List<CustomProviderConfig>,
    val calls: List<CallRecord>,
    val logs: List<LogLine>,
    val settings: GatewaySettings,
    val proxy: ProxySettings,
    val keepAlive: KeepAliveStatus,
    val security: dev.aigw.core.security.SecuritySettings,
    val evidences: List<dev.aigw.core.security.OutboundEvidence>,
    val sanitizerRulesCount: Int,
    val modelExposure: dev.aigw.core.gateway.ModelExposureSettings,
    val failover: dev.aigw.core.failover.ModelFailoverSettings,
    val overlayKeepAlive: Boolean,
    val autoStart: Boolean,
    val bgAutoStart: Boolean,
    val hideRecents: Boolean,
    val checkedInToday: Boolean,
    val checkinStatusText: String,
)

class AppViewModel(
    private val app: Application,
    private val engine: GatewayEngine = app.gatewayEngine,
) : ViewModel() {

    private val appearance = AppearanceStore(engine.store())
    private val updateStore = UpdateStore(engine.store())

    private val _state = MutableStateFlow(
        AppUiState(
            dynamicColor = appearance.dynamicColor(),
            autoCheckUpdate = updateStore.autoCheck(),
            updateChannel = updateStore.channel(),
            themeMode = appearance.themeMode(),
            // 存储里的非法 HEX 一律回落到默认值，不让坏数据把界面卡死
            backgroundOverride = appearance.backgroundHex()?.let { parseHexColor(it) },
            goldStart = parseHexColor(appearance.goldStartHex()) ?: defaultAppColors.goldStart,
            goldEnd = parseHexColor(appearance.goldEndHex()) ?: defaultAppColors.goldEnd,
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    /** 短信验证码的 msgid，按供应商暂存（发送与登录是两次调用）。 */
    private val smsMsgIds = HashMap<String, String>()

    /** 下次可重新发送验证码的时间，按供应商记（界面倒计时会随页面重置，不能只靠界面拦）。 */
    private val smsResendAt = HashMap<String, Long>()

    /** 金色渐变去抖落盘的挂起任务；每次取色取消上一轮，松手后才真正写盘。 */
    private var persistAppearanceJob: Job? = null

    /** 账号集合签名：判断账号变化是否真的影响模型目录，避免刷新凭证也重复拉取。 */
    private var lastAccountSignature: String = ""

    /** 流式加载代次：连发多次刷新时，只有最新一轮的增量与终态能写入 state。 */
    @Volatile
    private var modelsGeneration = 0

    /** 上次发起模型目录刷新的时刻；为 0 表示本次进程内还没拉过。 */
    @Volatile
    private var modelsFetchedAt = 0L

    init {
        // 登录在浏览器里完成、由网关的回调监听落池，这里接住通知刷新界面。
        // 只有账号集合真的变了才重拉模型：刷新凭证（refreshAccount 回存）也会触发本回调，
        // 那类更新不影响模型列表，重复拉取只是白白多打一轮上游。
        engine.onAccountsChanged = {
            viewModelScope.launch {
                val signature = withContext(Dispatchers.IO) { accountSignature(engine.accounts()) }
                if (signature != lastAccountSignature) {
                    lastAccountSignature = signature
                    refreshModels(silent = true)
                }
                refresh()
            }
        }
        // 调用记录与统计改在引擎后台预载（冷启动不阻塞主线程），就绪后补一次刷新
        engine.setStartupDataReadyListener { refresh() }
        // 模型目录先回填上次的快照：进程重启后不再是一片空白，
        // 之后由 ensureModelsLoaded / 手动刷新去更新（到货逐组替换）。
        //
        // ★ 快照为空（首次使用 / 重装清过数据）时自动拉一次：
        //   否则首页「聚合模型」会一直是 0，用户以为功能坏了，
        //   必须先进模型页才会触发 —— 这个交互太隐蔽。
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching { ModelSnapshotStore.load(engine.store()) }.getOrDefault(emptyList())
            }
            if (cached.isNotEmpty() && _state.value.models.isEmpty()) {
                _state.value = _state.value.copy(models = cached)
                // 有快照也仍然重拉一次（静默）：快照可能已经过时
                refreshModels(silent = true)
                return@launch
            }
            // 无快照：稍等账号池就绪再拉（没账号时拉了也是空，白打一轮上游）。
            // 不用 setStartupDataReadyListener —— 那是个单值字段，重复注册会覆盖
            // 上面那个负责刷新统计的监听器，导致调用记录不再更新。
            kotlinx.coroutines.delay(1_500)
            ensureModelsLoaded()
        }
        refresh()
        // 自动检查更新：默认开启，每天最多一次，失败静默
        maybeAutoCheckUpdate()
    }

    private fun accountSignature(accounts: List<AccountStatus>): String =
        accounts.joinToString("|") { "${it.providerId}/${it.uid}/${it.disabled}/${it.enabled}" }

    override fun onCleared() {
        engine.onAccountsChanged = null
        engine.setStartupDataReadyListener(null)
    }

    // ------------------------------------------------------------------ 读取

    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                Snapshot(
                    running = engine.isRunning(),
                    port = engine.settings().port,
                    localUrl = engine.localUrl(),
                    lanUrls = engine.lanUrls(),
                    pool = engine.pool.summary(),
                    today = engine.callLogStore.todayStats(System.currentTimeMillis()),
                    daily = engine.callLogStore.dailyStats(System.currentTimeMillis()),
                    total = engine.callLogStore.totalStats(),
                    providers = engine.providers(),
                    accounts = engine.accounts(),
                    customProviders = engine.settingsRepository.loadCustomProviders(),
                    calls = engine.callLogStore.list(),
                    logs = engine.requestLog.lines(),
                    settings = engine.settings(),
                    proxy = engine.proxySettings(),
                    keepAlive = KeepAlive.status(app),
                    security = engine.securitySettings(),
                    evidences = engine.sanitizer.evidenceList(),
                    sanitizerRulesCount = engine.sanitizer.currentRulesCount(),
                    modelExposure = engine.modelExposureSettings(),
                    failover = engine.failoverSettings(),
                    overlayKeepAlive = dev.aigw.app.data.KeepAlivePrefs.overlayEnabled(engine.store()),
                    autoStart = dev.aigw.app.data.KeepAlivePrefs.autoStartEnabled(engine.store()),
                    bgAutoStart = dev.aigw.app.data.KeepAlivePrefs.bgAutoStartEnabled(engine.store()),
                    // 与能力相与：Android 11 以下没有运行期接口，开关不该显示为「已开」
                    hideRecents = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        dev.aigw.app.data.KeepAlivePrefs.hideRecentsEnabled(engine.store()),
                    checkedInToday = engine.autoCheckinPrefs.isCheckedToday(System.currentTimeMillis()),
                    checkinStatusText = engine.autoCheckinPrefs.lastResultParsed().second,
                )
            }
            _state.value = _state.value.copy(
                running = snapshot.running,
                port = snapshot.port,
                localUrl = snapshot.localUrl,
                lanUrls = snapshot.lanUrls,
                pool = snapshot.pool,
                today = snapshot.today,
                daily = snapshot.daily,
                total = snapshot.total,
                providers = snapshot.providers,
                accounts = snapshot.accounts,
                customProviders = snapshot.customProviders,
                calls = snapshot.calls,
                logs = snapshot.logs,
                settings = snapshot.settings,
                proxy = snapshot.proxy,
                keepAlive = snapshot.keepAlive,
                security = snapshot.security,
                evidences = snapshot.evidences,
                sanitizerRulesCount = snapshot.sanitizerRulesCount,
                modelExposure = snapshot.modelExposure,
                failover = snapshot.failover,
                overlayKeepAlive = snapshot.overlayKeepAlive,
                autoStart = snapshot.autoStart,
                bgAutoStart = snapshot.bgAutoStart,
                hideRecents = snapshot.hideRecents,
                checkedInToday = snapshot.checkedInToday,
                checkinStatusText = snapshot.checkinStatusText,
            )
        }
    }

    /**
     * 只刷新记录（调用记录 + 请求日志）。
     *
     * 供「记录」页的定时轮询用：那里每 5 秒刷一次，没必要顺带枚举局域网、
     * 复制账号池状态与设置（这些都是低频变化的数据）。
     */
    fun refreshRecords() {
        viewModelScope.launch {
            val (calls, logs) = withContext(Dispatchers.IO) {
                engine.callLogStore.list() to engine.requestLog.lines()
            }
            val current = _state.value
            // 记录页每 5 秒轮询一次：内容没变就不赋值。CallRecord 带大段请求/响应原文，
            // 整体 equals 在主线程上是 O(总字符数) 的开销。
            if (calls.size == current.calls.size && logs.size == current.logs.size &&
                calls.firstOrNull()?.id == current.calls.firstOrNull()?.id &&
                // 用 seq（单调自增）而不是时间戳：同一毫秒内的多条新日志时间戳相同，会漏刷一轮
                logs.firstOrNull()?.seq == current.logs.firstOrNull()?.seq
            ) {
                return@launch
            }
            _state.value = current.copy(calls = calls, logs = logs)
        }
    }

    /**
     * 单独刷新存储占用（用量统计页展示用）。
     *
     * 刻意不放在 [refresh] 里：它要扫全部加密存储键并遍历缓存目录，代价高，
     * 而记录页每 5 秒就会调一次 refresh。
     */
    fun refreshStorage() {
        viewModelScope.launch {
            val (report, count) = withContext(Dispatchers.IO) {
                engine.storageReport() to engine.callLogStore.storedCount()
            }
            _state.value = _state.value.copy(storage = report, storedCalls = count)
        }
    }

    fun refreshModels(silent: Boolean = false, forceRefresh: Boolean = false) {
        val gen = ++modelsGeneration
        modelsFetchedAt = System.currentTimeMillis()
        viewModelScope.launch {
            // 已有目录可展示时降级为静默：旧数据立即可见，新数据分批到货逐组替换，不弹全屏 busy
            val quiet = silent || _state.value.models.isNotEmpty()
            if (!quiet) _state.value = _state.value.copy(busy = "正在拉取模型目录")
            val participants = withContext(Dispatchers.IO) {
                engine.providers().filter { it.enabled }.map { it.id }
            }
            if (gen != modelsGeneration) return@launch
            _state.value = _state.value.copy(
                modelsLoading = true,
                modelsError = "",
                modelLoadStates = participants.associateWith { ModelLoadState.Loading as ModelLoadState },
            )
            val (catalog, error) = withContext(Dispatchers.IO) {
                runCatching {
                    engine.modelsStreaming(forceRefresh = forceRefresh) { chunk ->
                        viewModelScope.launch {
                            if (gen == modelsGeneration) applyModelsChunk(chunk)
                        }
                    }
                }.fold(
                    { it to "" },
                    { ModelsCatalog(emptyList()) to (it.message ?: "拉取失败") },
                )
            }
            if (gen != modelsGeneration) return@launch
            // 全部完成后用最终聚合覆盖：失败供应商的旧条目随之清除（与整体刷新语义一致）。
            // ★ 唯一的例外：整轮结果为空时不能覆盖 —— 那多半是全部供应商都失败（无账号、
            //   上游故障、断网），覆盖等于把界面上原有的目录（含冷启动回填的快照）清成 0 个，
            //   用户看到的就是「模型列表空了」。此时保留旧列表，只报错。
            val providerError = catalog.failures.entries.joinToString("；") { "${it.key}：${it.value}" }
            val keepOld = catalog.models.isEmpty()
            if (keepOld) {
                _state.value = _state.value.copy(
                    modelsLoading = false,
                    modelsError = listOf(error, providerError).filter { it.isNotEmpty() }
                        .joinToString("；").ifEmpty { "本次没有取到任何模型，先显示上次的列表" },
                    busy = if (quiet) _state.value.busy else "",
                )
                pruneStaleExposure(catalog.models)
                return@launch
            }
            _state.value = _state.value.copy(
                models = catalog.models,
                modelsLoading = false,
                modelsError = listOf(error, providerError).filter { it.isNotEmpty() }.joinToString("；"),
                busy = if (quiet) _state.value.busy else "",
            )
            // 全量成功后落盘快照：下次冷启动直接有内容可用
            if (catalog.models.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    runCatching { ModelSnapshotStore.save(engine.store(), catalog.models, force = true) }
                }
            }
            // 清名单要读写加密存储（同步落盘），放到 IO 线程，避免主线程卡帧
            pruneStaleExposure(catalog.models)
        }
    }

    /**
     * 清掉开放名单里远端已不存在的模型。
     *
     * 只处理「本轮真的拉到了模型」的前缀：拉不到（无账号、上游临时故障）时目录可能只是暂时取不到，
     * 不能凭此抹掉用户的勾选。
     */
    private suspend fun pruneStaleExposure(live: List<RoutedModel>) {
        if (live.isEmpty()) return
        val current = engine.modelExposureSettings()
        if (current.exposed.isEmpty()) return
        val liveIds = live.map { it.fullId }.toSet()
        val prefixes = live.map { it.routePrefix }.toSet()
        val stale = current.exposed.filter { it.substringBefore('/') in prefixes && it !in liveIds }
        if (stale.isEmpty()) return
        // 同步落盘（加密存储 commit），必须在 IO 线程
        withContext(Dispatchers.IO) {
            engine.updateModelExposure(current.copy(exposed = current.exposed - stale.toSet()))
        }
        _state.value = _state.value.copy(modelExposure = engine.modelExposureSettings())
    }

    /**
     * 进入模型页时首次拉取：本次进程内还没拉过才拉，之后进入不再自动重拉。
     *
     * 模型目录只在「首次进入模型页」与「手动刷新」时更新，避免每次切页都打上游。
     */
    fun ensureModelsLoaded() {
        // 正在拉的时候不要叠加：模型页每次进入都会调本方法，连点几下会同时起多轮
        if (_state.value.modelsLoading) return
        // 两条触发条件缺一不可：
        //   1. 本次进程还没拉过（modelsFetchedAt == 0）
        //   2. 列表为空 —— 上一轮可能整体失败（无账号、上游故障、超时），
        //      此时 modelsFetchedAt 已被置位，只靠第 1 条会导致「空着也不重拉」，
        //      用户必须手动点刷新，看上去就是「模型列表空了」。
        if (modelsFetchedAt == 0L || _state.value.models.isEmpty()) refreshModels()
    }

    /**
     * 流式增量：把一个供应商就绪的目录合并进 state（已在主线程串行执行，替换该供应商旧条目）。
     *
     * 顺带落盘快照：某个供应商超时会让整轮等不到「全部完成」，那样一条都不存，
     * 下次启动又是空的。这里到货即存，保证「有多少存多少」。
     */
    private fun applyModelsChunk(chunk: ProviderModelsChunk) {
        val current = _state.value
        val states = current.modelLoadStates.toMutableMap()
        val failure = chunk.error
        states[chunk.providerId] =
            if (failure != null) ModelLoadState.Failed(failure) else ModelLoadState.Done(chunk.models.size)
        val merged = current.models.filter { it.providerId != chunk.providerId } + chunk.models
        _state.value = current.copy(models = merged, modelLoadStates = states)
        if (chunk.models.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { ModelSnapshotStore.save(engine.store(), merged) }
            }
        }
    }

    /** 对指定模型执行一次轻量连通性与延迟探测。 */
    fun testModel(routedModel: RoutedModel) {
        val fullId = routedModel.fullId
        val current = _state.value.modelTestLatencies.toMutableMap()
        current[fullId] = ModelTestStatus.Testing
        _state.value = _state.value.copy(modelTestLatencies = current)

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                engine.probeModelLatency(routedModel.providerId, routedModel.model.id, routedModel.region)
            }
            val updated = _state.value.modelTestLatencies.toMutableMap()
            updated[fullId] = if (result.latencyMs > 0) {
                ModelTestStatus.Success(result.latencyMs)
            } else {
                ModelTestStatus.Failed(result.error.ifEmpty { "连接失败" })
            }
            _state.value = _state.value.copy(modelTestLatencies = updated)
        }
    }

    // ------------------------------------------------------------------ 模型开放名单

    fun modelExposure(): ModelExposureSettings = engine.modelExposureSettings()

    fun updateModelExposure(updated: ModelExposureSettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateModelExposure(updated) }
            _state.value = _state.value.copy(modelExposure = updated)
        }
    }

    /** 单个模型勾选/取消勾选。 */
    fun toggleModelExposed(fullId: String, checked: Boolean) {
        val current = engine.modelExposureSettings()
        val exposed = current.exposed.toMutableSet()
        if (checked) exposed.add(fullId) else exposed.remove(fullId)
        updateModelExposure(current.copy(exposed = exposed))
    }

    /** 按分组（routePrefix）批量勾选/取消勾选当前已加载的模型。 */
    fun toggleGroupExposed(routePrefix: String, checked: Boolean) {
        val current = engine.modelExposureSettings()
        val exposed = current.exposed.toMutableSet()
        val groupIds = _state.value.models.filter { it.routePrefix == routePrefix }.map { it.fullId }
        if (checked) exposed.addAll(groupIds) else exposed.removeAll(groupIds.toSet())
        updateModelExposure(current.copy(exposed = exposed))
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = "")
    }

    /** 直接弹一条提示（供 UI 组件上报本地错误）。 */
    fun notice(message: String) {
        _state.value = _state.value.copy(notice = message)
    }

    // ------------------------------------------------------------------ 服务

    fun startGateway() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在启动服务")
            // ★ 先落「用户要它开着」，再起服务：
            //   服务里会根据这个状态决定 START_STICKY / NOT_STICKY，
            //   晚写就会让「首次开启」的这次运行退化成「进程被回收后不自恢复」。
            withContext(Dispatchers.IO) { GatewayIntent.markRunning(app, true) }
            // 先拉前台服务再启动引擎：startForegroundService 立即弹出通知，
            // engine.start() 幂等（server != null 直接返回），Service 里会再调一次兑底。
            // 后台起前台服务在部分 ROM 上会抛异常，包起来：失败也不影响引擎启动。
            runCatching {
                ContextCompat.startForegroundService(app, Intent(app, GatewayService::class.java))
            }
            val error = withContext(Dispatchers.IO) {
                runCatching { engine.start() }.exceptionOrNull()?.let { it.message ?: "启动失败" }
            }
            _state.value = _state.value.copy(busy = "")
            if (error != null) {
                // 引擎没起来就撤掉前台通知，避免「通知说运行中、端口没人听」
                app.stopService(Intent(app, GatewayService::class.java))
                _state.value = _state.value.copy(notice = "启动失败：$error")
            } else {
                // 记住「用户要它开着」：开机自启 / Root 守护据此恢复，而不是无条件拉起
                withContext(Dispatchers.IO) { GatewayIntent.markRunning(app, true) }
            }
            refresh()
            requestTileUpdate()
        }
    }

    fun stopGateway() {
        viewModelScope.launch {
            // 先落盘「用户主动停止」：守护脚本看到标记没了才不会在 20 秒后把它拉回来
            withContext(Dispatchers.IO) { GatewayIntent.markRunning(app, false) }
            withContext(Dispatchers.IO) { engine.stop() }
            app.stopService(Intent(app, GatewayService::class.java))
            _state.value = _state.value.copy(notice = "服务已停止")
            refresh()
            requestTileUpdate()
        }
    }

    /** App 内启停后让控制中心磁贴同步状态。 */
    private fun requestTileUpdate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(
                app,
                ComponentName(app, GatewayTileService::class.java),
            )
        }
    }

    // ------------------------------------------------------------------ 设置

    /**
     * 保存网关设置。
     *
     * ★ 端口 / 是否监听局域网这两项只在 start() 时生效：改完不重启的话，
     * 界面和 /v1/credits 显示的是新端口，实际仍在监听旧的 ——
     * 客户端按新端口去连必然连不上，用户只会看到「网关明明在跑却连不通」。
     * 所以这里检测到这两项变化且网关正在运行时，自动重启一次。
     */
    fun updateSettings(updated: GatewaySettings) {
        viewModelScope.launch {
            val before = withContext(Dispatchers.IO) { engine.settings() }
            val needRestart = before.port != updated.port || before.exposeLan != updated.exposeLan
            val restarting = needRestart && engine.isRunning()
            withContext(Dispatchers.IO) {
                engine.updateSettings(updated)
                if (restarting) {
                    engine.stop()
                    runCatching { engine.start() }
                }
            }
            if (restarting) {
                _state.value = _state.value.copy(notice = "设置已保存，网关已按新配置重启")
            }
            refresh()
        }
    }

    fun updateSecuritySettings(updated: dev.aigw.core.security.SecuritySettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateSecuritySettings(updated) }
            refresh()
        }
    }

    fun updateFailoverSettings(updated: dev.aigw.core.failover.ModelFailoverSettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateFailoverSettings(updated) }
            refresh()
        }
    }

    /**
     * 开关「悬浮窗保活」：落盘后立即生效。
     *
     * 悬浮窗只在**网关服务运行时**显示（它服务于保活，网关没跑时没有意义）；
     * 未启动时只记开关，下次启动网关时由前台服务拉起。
     */
    fun setOverlayKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dev.aigw.app.data.KeepAlivePrefs.setOverlayEnabled(engine.store(), enabled) }
            if (enabled) {
                if (!engine.isRunning()) {
                    notice("已开启，悬浮窗将在网关服务启动后显示")
                } else {
                    val label = "网关 ${engine.settings().port}"
                    val shown = dev.aigw.app.service.FloatingKeepAlive.show(app, label)
                    notice(
                        if (shown) "悬浮窗保活已开启"
                        else "已开启，但缺少「显示在其他应用上层」权限，请在上方授权",
                    )
                }
            } else {
                dev.aigw.app.service.FloatingKeepAlive.hide(app)
                notice("悬浮窗保活已关闭")
            }
            refresh()
        }
    }

    /**
     * 开关「开机自启」：落盘后由 BootReceiver 在系统启动时读取并拉起服务。
     *
     * 注意：开关只写自己的存储，真正的自启还需要系统「自启动」白名单放行
     * （国产 ROM 默认拦截），所以这里同时提示去厂商后台设置里放行。
     */
    /** 开关「后台自动开启」：落盘后 BackgroundAutoStart 实时读取，下一轮后台即按新值判断。 */
    fun setBgAutoStart(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dev.aigw.app.data.KeepAlivePrefs.setBgAutoStartEnabled(engine.store(), enabled)
            }
            notice(if (enabled) "后台自动开启已打开（退到后台 1 分钟后生效）" else "后台自动开启已关闭")
            refresh()
        }
    }

    fun setAutoStart(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dev.aigw.app.data.KeepAlivePrefs.setAutoStartEnabled(engine.store(), enabled)
                // 立刻把新开关下发给守护脚本：否则要等下次启停网关才会写到 state 里，
                // 期间守护仍按旧值决定要不要在开机时拉起。
                val port = runCatching { engine.settings().port }.getOrDefault(0)
                val running = engine.isRunning()
                runCatching {
                    dev.aigw.app.service.RootKeeper.syncState(running, port, enabled)
                }
            }
            notice(if (enabled) "开机自启已开启（需系统自启动白名单放行）" else "开机自启已关闭")
            refresh()
        }
    }

    /**
     * 开关「隐藏多任务」：落盘后立即生效。
     *
     * 不做「立即移除」——用户此刻就在应用里看这个开关，把自己从最近任务里踢掉
     * 会让系统把当前任务也结束掉。真正的移除发生在下次退到后台时。
     */
    fun setHideRecents(enabled: Boolean) {
        // Android 11 以下没有「运行期把任务排除出最近列表」的公开 API，
        // 只有清单里的静态属性。这里直接告知用户，别让开关看着打开却毫无效果。
        if (enabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            notice("当前系统不支持隐藏多任务（需 Android 11 及以上）")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dev.aigw.app.data.KeepAlivePrefs.setHideRecentsEnabled(engine.store(), enabled) }
            // 立即同步标记，无需等下次退后台（只改标记，不销毁任务）
            runCatching { dev.aigw.app.service.HideRecents.syncNow(app, enabled) }
            notice(if (enabled) "已开启，本应用不再出现在最近任务列表" else "隐藏多任务已关闭")
            refresh()
        }
    }

    // ------------------------------------------------------------------ Root 守护

    /**
     * 一键安装 Root 守护：装完立刻自检，确认它**真的在工作**而不只是「装上了」。
     *
     * 自检三项：守护进程活着、探活命令可用、开机目录铺对。
     */
    fun installRootKeeper() = action {
        val r = dev.aigw.app.service.RootKeeper.install(app)
        val v = runCatching { dev.aigw.app.service.RootKeeper.verify(app) }.getOrNull()

        when (r) {
            is dev.aigw.app.service.RootKeeper.Result.NoRoot -> r.detail
            is dev.aigw.app.service.RootKeeper.Result.Failed -> r.detail
            else -> {
                val head = if (r is dev.aigw.app.service.RootKeeper.Result.Installed) {
                    "守护已启动（PID ${r.pid}）"
                } else {
                    "守护已在运行"
                }
                if (v == null) {
                    "$head；自检未执行"
                } else {
                    buildString {
                        append(head)
                        append("\n进程：").append(if (v.processAlive) "在跑（${v.pid}）" else "未启动 ✗")
                        append("\n探活：").append(
                            when {
                                v.probeWorks -> "可用（返回「${v.probeOutput}」）"
                                else -> "不可用 ✗（输出「${v.probeOutput.ifEmpty { "空" }}」）"
                            }
                        )
                        append("\n开机目录：").append(
                            if (v.registeredDirs.isEmpty()) "无（无框架环境，重启后需重开应用）"
                            else v.registeredDirs.joinToString("、")
                        )
                        if (!v.ok) append("\n⚠ 有未通过项，守护不会真正生效")
                    }
                }
            }
        }
    }

    /** 单独跑一次自检（不安装），用于确认已在跑的守护是否健康。 */
    fun verifyRootKeeper() = action {
        val v = dev.aigw.app.service.RootKeeper.verify(app)
        buildString {
            append("Root 守护自检：").append(if (v.ok) "通过" else "未通过")
            append("\n进程：").append(if (v.processAlive) "在跑（${v.pid}）" else "未启动 ✗")
            append("\n探活：").append(
                if (v.probeWorks) "可用（返回「${v.probeOutput}」）"
                else "不可用 ✗（输出「${v.probeOutput.ifEmpty { "空" }}」）"
            )
            append("\n开机目录：").append(
                if (v.registeredDirs.isEmpty()) "无（无框架环境，重启后需重开应用）"
                else v.registeredDirs.joinToString("、")
            )
            if (v.logTail.isNotEmpty()) append("\n最近日志：").append(v.logTail.take(200))
        }
    }

    /** 自检是否正在进行（防止 3 秒轮询叠出多轮）。 */
    @Volatile
    private var rootKeeperChecking = false

    /** 只刷新「网关是否在运行」这一个字段（磁贴/守护可能在后台改了它）。 */
    fun refreshRunning() {
        val running = runCatching { engine.isRunning() }.getOrDefault(_state.value.running)
        if (running != _state.value.running) {
            _state.value = _state.value.copy(running = running)
        }
    }

    /**
     * 刷新 Root 守护的实测状态（进页面时调一次，避免每帧起 su 进程）。
     *
     * [forceSuCheck]：强制重新探测 root 权限（结果有 30 秒缓存）。
     * 用户去 Root 管理器授权完回到本页时传 true，否则要等半分钟卡片才出现。
     */
    fun refreshRootKeeperState(forceSuCheck: Boolean = false) {
        // ★ 防堆积：页面每 3 秒调一次，而一次自检要起 su、跑探活，
        //   网关没起来时能拖到十几秒。不加保护就会同时挂好几轮，
        //   表现是界面越用越卡、su 进程越来越多。
        if (rootKeeperChecking) return
        rootKeeperChecking = true
        viewModelScope.launch {
            val v = try {
                withContext(Dispatchers.IO) {
                    runCatching { dev.aigw.app.service.RootKeeper.verify(app, forceSuCheck) }
                        .getOrNull()
                }
            } finally {
                rootKeeperChecking = false
            }
            // 查询失败（su 超时 / 被拒）时保留上一次的结果：
            // 置 null 会让「Root 卡片」整块消失，用户会以为 root 权限丢了，
            // 实际只是这一次查询没成功。
            if (v != null) _state.value = _state.value.copy(rootKeeper = v)
        }
    }

    /**
     * 读完整守护日志。
     *
     * ★ 必须异步：读日志要起一个 su 进程，首次授权会弹框等待；
     * 之前是同步返回、UI 在点击回调里直接调用 —— 点「查看更多」就是一次 ANR 风险。
     */
    fun loadFullRootKeeperLog(onLoaded: (String) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { dev.aigw.app.service.RootKeeper.readFullLog(app) }.getOrDefault("")
            }
            onLoaded(text)
        }
    }

    /** 清空守护日志。 */
    fun clearRootKeeperLog() = action {
        val ok = runCatching { dev.aigw.app.service.RootKeeper.clearLog() }.getOrDefault(false)
        refreshRootKeeperState()
        if (ok) "守护日志已清空" else "清除失败：没有 root 权限或日志不存在"
    }

    /** 卸载 Root 守护。 */
    fun uninstallRootKeeper() = action {
        when (val r = dev.aigw.app.service.RootKeeper.uninstall(app)) {
            dev.aigw.app.service.RootKeeper.UninstallResult.Done -> {
                // 清空界面上的旧快照：否则卡片会继续显示上次的「运行中 / 已生效」，
                // 用户以为没卸干净（实际是界面数据没刷新）。
                _state.value = _state.value.copy(rootKeeper = null)
                refreshRootKeeperState(forceSuCheck = true)
                "Root 守护已卸载（进程、开机脚本与 /data/adb/tyd 均已清除）"
            }
            is dev.aigw.app.service.RootKeeper.UninstallResult.NoRoot -> r.detail
            is dev.aigw.app.service.RootKeeper.UninstallResult.Partial ->
                "卸载未彻底：${r.detail}。请再点一次「卸载」，或检查 root 管理器是否拦截了删除。"
        }
    }

    // ------------------------------------------------------------------ Shizuku 增强

    /**
     * 一键把系统压制开关调到最有利（Doze 白名单 / standby 分级 / 休眠豁免等）。
     * 装完立刻自检读数，证明系统真的接受了。
     */
    fun shizukuBoost() = action {
        val report = dev.aigw.app.service.ShizukuBoost.boost(app)
        val inspect = dev.aigw.app.service.ShizukuBoost.inspect(app)
        buildString {
            append("Shizuku 增强：${report.okCount}/${report.items.size} 项已执行")
            report.items.forEach { append(if (it.ok) "\n✓ " else "\n✗ ").append(it.name).append("：").append(it.detail) }
            append("\n\n自检（当前真实状态）：")
            inspect.items.forEach { append(if (it.ok) "\n✓ " else "\n✗ ").append(it.name).append("：").append(it.detail) }
            if (!inspect.allOk) append("\n⚠ 有未通过项，需手动在系统设置里处理")
        }
    }

    /** 单独自检：不修改任何东西，只读当前状态。 */
    fun shizukuInspect() = action {
        val inspect = dev.aigw.app.service.ShizukuBoost.inspect(app)
        buildString {
            append("Shizuku 状态自检：").append(if (inspect.allOk) "全部通过" else "${inspect.okCount}/${inspect.items.size} 通过")
            inspect.items.forEach { append(if (it.ok) "\n✓ " else "\n✗ ").append(it.name).append("：").append(it.detail) }
        }
    }

    /** 请求 Shizuku 授权（弹系统确认框）。 */
    fun shizukuRequestPermission() {
        val blocked = dev.aigw.app.service.ShizukuBoost.requestPermission()
        notice(
            if (blocked) {
                "已被拒绝且不再询问：请到 Sui / Shizuku 管理界面里给天元鼎手动开启授权"
            } else {
                "已发出授权请求，请在弹窗里点「允许」"
            }
        )
    }

    /** 撤销 Shizuku 增强。 */
    fun shizukuRevert() = action {
        val report = dev.aigw.app.service.ShizukuBoost.revert(app)
        buildString {
            append("已撤销 ${report.okCount}/${report.items.size} 项")
            report.items.forEach { append("\n").append(it.name).append("：").append(if (it.ok) "已恢复" else it.detail) }
        }
    }

    /**
     * 授权返回后重新应用悬浮窗：仅在网关服务运行时补显示（开关开着且未在显示）。
     */
    fun reapplyOverlayIfEnabled() {
        if (!engine.isRunning()) return
        if (!dev.aigw.app.data.KeepAlivePrefs.overlayEnabled(engine.store())) return
        if (dev.aigw.app.service.FloatingKeepAlive.isShowing()) return
        dev.aigw.app.service.FloatingKeepAlive.show(app, "网关 ${engine.settings().port}")
    }

    /** 重新加载反审核脱敏处理链。 */
    fun reloadSanitizerPipeline() = action {
        val count = engine.reloadSanitizerPipeline()
        "处理链已重载，生效规则数：$count"
    }

    /** 清空出网取证流水。 */
    fun clearEvidences() {
        engine.sanitizer.clearEvidence()
        _state.value = _state.value.copy(evidences = emptyList())
    }

    fun setDynamicColor(enabled: Boolean) {
        _state.value = _state.value.copy(dynamicColor = enabled)
        viewModelScope.launch { withContext(Dispatchers.IO) { appearance.setDynamicColor(enabled) } }
    }

    /** 切换明暗模式（立即生效并落盘）。 */
    fun setThemeMode(mode: ThemeMode) {
        _state.value = _state.value.copy(themeMode = mode)
        viewModelScope.launch { withContext(Dispatchers.IO) { appearance.setThemeMode(mode) } }
    }

    /** 设置页面底色；[hex] 为 null 表示清除覆盖（恢复默认底色）。 */
    fun setBackgroundOverride(hex: String?) {
        if (hex == null) {
            _state.value = _state.value.copy(backgroundOverride = null)
            viewModelScope.launch { withContext(Dispatchers.IO) { appearance.setBackgroundHex(null) } }
            return
        }
        val parsed = parseHexColor(hex)
        if (parsed == null) {
            notice("颜色代码需要是 #RRGGBB 格式")
            return
        }
        _state.value = _state.value.copy(backgroundOverride = parsed)
        val code = formatHex(parsed)
        viewModelScope.launch { withContext(Dispatchers.IO) { appearance.setBackgroundHex(code) } }
    }

    /**
     * 设置金色渐变两端；任一非法则整组忽略并提示。
     *
     * 界面状态立即更新（取色拖动时每帧都会调用），落盘走 300ms 去抖：
     * 加密存储的写入是同步阻塞的，逐帧落盘会让拖动明显掉帧。
     */
    fun setGoldColors(startHex: String, endHex: String) {
        val start = parseHexColor(startHex)
        val end = parseHexColor(endHex)
        if (start == null || end == null) {
            notice("颜色代码需要是 #RRGGBB 格式")
            return
        }
        _state.value = _state.value.copy(goldStart = start, goldEnd = end)
        val startCode = formatHex(start)
        val endCode = formatHex(end)
        persistAppearanceJob?.cancel()
        persistAppearanceJob = viewModelScope.launch {
            delay(GOLD_PERSIST_DEBOUNCE_MS)
            withContext(Dispatchers.IO) {
                appearance.setGoldStartHex(startCode)
                appearance.setGoldEndHex(endCode)
            }
        }
    }

    /** 恢复默认外观（明暗模式、页面底色、金色渐变）。 */
    fun resetAppearance() {
        persistAppearanceJob?.cancel()
        _state.value = _state.value.copy(
            themeMode = ThemeMode.System,
            backgroundOverride = null,
            goldStart = defaultAppColors.goldStart,
            goldEnd = defaultAppColors.goldEnd,
        )
        viewModelScope.launch {
            withContext(Dispatchers.IO) { appearance.reset() }
            _state.value = _state.value.copy(notice = "外观主题已恢复默认")
        }
    }

    fun providerSettings(providerId: String): ProviderSettings = engine.providerSettings(providerId)

    fun updateProviderSettings(providerId: String, settings: ProviderSettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateProviderSettings(providerId, settings) }
            refresh()
        }
    }

    // ------------------------------------------------------------------ 登录

    /**
     * 设备授权的区域感知版本（WorkBuddy）：先把区域同步写进供应商设置，
     * 引擎开登录页时才能读到正确的端点（cn=copilot.tencent.com / global=workbuddy.ai）。
     */
    fun beginDeviceLogin(providerId: String, region: String) {
        val settings = providerSettings(providerId)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                engine.updateProviderSettings(providerId, settings.copy(options = settings.options + ("region" to region)))
            }
        }
        beginBrowserLogin(providerId, region)
    }

    fun providerOption(providerId: String, key: String, fallback: String = ""): String =
        providerSettings(providerId).option(key, fallback)

    fun updateProviderOption(providerId: String, key: String, value: String) {
        val settings = providerSettings(providerId)
        updateProviderSettings(providerId, settings.copy(options = settings.options + (key to value)))
    }

    /**
     * 打开系统浏览器完成登录（Trae / Antigravity / WorkBuddy）。
     *
     * 网关会在本地临时监听回调端口（Trae 51120、Antigravity 51121），
     * 浏览器完成登录后由网关接住回调并落池；WorkBuddy 没有本地回调，
     * 返回轮询用的 state，由 [pollDeviceLogin] 换取 token。
     */
    fun beginBrowserLogin(providerId: String, region: String = "") {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在准备登录")
            val result = withContext(Dispatchers.IO) {
                runCatching { engine.beginBrowserLogin(providerId, region) }
            }
            _state.value = _state.value.copy(busy = "")
            result.fold(
                onSuccess = { ticket ->
                    if (!openBrowser(ticket.loginUrl)) {
                        _state.value = _state.value.copy(notice = "没有可用的浏览器，无法打开登录页")
                        return@fold
                    }
                    if (ticket.pollState.isNotEmpty()) {
                        // 用票据自带的区域轮询：授权页创建在哪个区域，就只能在该区域查这个 state
                        pollDeviceLogin(providerId, ticket.pollState, ticket.region)
                    } else {
                        _state.value = _state.value.copy(notice = "已在浏览器打开登录页，完成后会自动添加账号")
                    }
                },
                onFailure = { _state.value = _state.value.copy(notice = it.message ?: "打开登录页失败") },
            )
        }
    }

    private fun openBrowser(url: String): Boolean = runCatching {
        app.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    /**
     * 设备授权（WorkBuddy）：浏览器里登录完成后轮询换取 token，最长 5 分钟。
     */
    fun pollDeviceLogin(providerId: String, state: String, region: String = "") {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "等待授权完成")
            val deadline = System.currentTimeMillis() + 5 * 60 * 1000
            var message = "授权超时，请重试"
            while (System.currentTimeMillis() < deadline) {
                val poll = withContext(Dispatchers.IO) { engine.pollDeviceAuth(providerId, state, region) }
                when (poll) {
                    is DeviceAuthPoll.Pending -> delay(3_000)
                    is DeviceAuthPoll.Success -> {
                        _state.value = _state.value.copy(busy = "", notice = "登录成功：${poll.account.nickname.ifEmpty { poll.account.uid }}")
                        refresh()
                        return@launch
                    }
                    is DeviceAuthPoll.Failed -> {
                        message = poll.error
                        break
                    }
                }
            }
            _state.value = _state.value.copy(busy = "", notice = message)
            refresh()
        }
    }

    /** 用当前填写的地址与 Key 拉取模型列表（新建供应商时还没保存也能用）。 */
    fun fetchCustomModels(baseUrl: String, apiKey: String, onResult: (List<String>, String) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在拉取模型")
            val result = withContext(Dispatchers.IO) {
                runCatching { CustomProvider.fetchModels(baseUrl, apiKey) }
            }
            _state.value = _state.value.copy(busy = "")
            result.fold(
                onSuccess = { models ->
                    if (models.isEmpty()) {
                        onResult(emptyList(), "没有拉到模型，检查接口地址与 API Key")
                    } else {
                        onResult(models, "")
                    }
                },
                onFailure = { onResult(emptyList(), it.message ?: "拉取失败") },
            )
        }
    }

    /** 发短信验证码（msgid 内部暂存）；[onResult] 回传错误信息（空串表示成功）。 */
    fun sendSmsCode(
        providerId: String,
        phone: String,
        ccode: String = "86",
        onResult: (String) -> Unit = {},
    ) {
        val remain = smsResendRemaining(providerId)
        if (remain > 0) {
            val message = "请 $remain 秒后再获取验证码"
            notice(message)
            onResult(message)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在发送验证码")
            val result = withContext(Dispatchers.IO) {
                runCatching { engine.sendSmsCode(providerId, phone, ccode.ifBlank { "86" }) }
            }
            _state.value = _state.value.copy(busy = "")
            result.fold(
                onSuccess = {
                    smsMsgIds[providerId] = it
                    smsResendAt[providerId] = System.currentTimeMillis() + SMS_RESEND_INTERVAL_MS
                    _state.value = _state.value.copy(notice = "验证码已发送")
                    onResult("")
                },
                onFailure = {
                    val message = it.message ?: "发送验证码失败"
                    _state.value = _state.value.copy(notice = message)
                    onResult(message)
                },
            )
        }
    }

    /** 距离下次可发送验证码还有多少秒（0 表示可以发）。 */
    fun smsResendRemaining(providerId: String): Int {
        val at = smsResendAt[providerId] ?: return 0
        val remain = (at - System.currentTimeMillis()) / 1000
        return remain.coerceAtLeast(0).toInt()
    }

    fun smsLogin(
        providerId: String,
        phone: String,
        code: String,
        ccode: String = "86",
        onResult: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在登录")
            val msgid = smsMsgIds[providerId].orEmpty()
            val outcome = withContext(Dispatchers.IO) {
                engine.completeSmsLogin(providerId, phone, code, msgid, ccode.ifBlank { "86" })
            }
            _state.value = _state.value.copy(busy = "", notice = noticeOf(outcome))
            onResult(if (outcome.ok) "" else outcome.error)
            refresh()
        }
    }

    /** 粘贴凭证导入（Trae 的凭证 JSON、自定义供应商的 API Key）。 */
    fun importCredentials(providerId: String, raw: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在导入")
            val outcome = withContext(Dispatchers.IO) { engine.importAccountJson(providerId, raw) }
            _state.value = _state.value.copy(busy = "", notice = noticeOf(outcome))
            refresh()
        }
    }

    /** 添加自定义供应商账号：每个 key 一个账号，名称可选（默认用供应商名）；同时保存供应商的模型列表。 */
    fun addCustomAccount(providerId: String, nickname: String, apiKey: String, models: List<String>) = action {
        val key = apiKey.trim()
        if (key.isEmpty()) throw IllegalStateException("API Key 不能为空")
        val provider = engine.registry.get(providerId) as? CustomProvider
            ?: throw IllegalStateException("供应商不存在")
        val uid = CustomProvider.uidOf(key)
        if (engine.account(providerId, uid) != null) throw IllegalStateException("这个 API Key 已经添加过了")
        engine.addAccount(providerId, uid, nickname.trim().ifEmpty { provider.displayName }, CustomProvider.secretOf(key))
        saveCustomModels(providerId, models)
        "账号已添加"
    }

    /** 把模型列表写入供应商配置（供「添加账号」时一并保存）。 */
    private fun saveCustomModels(providerId: String, models: List<String>) {
        val repository = engine.settingsRepository
        if (repository.loadCustomProviders().none { it.providerId == providerId }) return
        repository.saveCustomProviders(
            repository.loadCustomProviders().map { if (it.providerId == providerId) it.copy(models = models) else it },
        )
        engine.reloadCustomProviders()
    }

    /** 修改账号显示名称。 */
    fun renameAccount(providerId: String, uid: String, nickname: String) = action {
        val account = engine.account(providerId, uid) ?: throw IllegalStateException("账号不存在")
        val name = nickname.trim()
        if (name.isEmpty()) throw IllegalStateException("名称不能为空")
        engine.addAccount(providerId, uid, name, account.secret)
        "名称已更新"
    }

    /** 读账号的 API Key（仅自定义供应商的凭证里有）。 */
    fun accountApiKey(providerId: String, uid: String): String {
        val account = engine.account(providerId, uid) ?: return ""
        return runCatching {
            com.google.gson.JsonParser.parseString(account.secret).asJsonObject.get("apiKey")?.asString.orEmpty()
        }.getOrDefault("")
    }

    /** 读自定义供应商已保存的模型列表。 */
    fun customModels(providerId: String): List<String> {
        val provider = engine.registry.get(providerId) as? CustomProvider ?: return emptyList()
        return provider.savedModels()
    }

    /** 读自定义供应商的接口地址。 */
    fun customBaseUrl(providerId: String): String {
        val provider = engine.registry.get(providerId) as? CustomProvider ?: return ""
        return provider.baseUrl
    }

    private fun noticeOf(outcome: LoginOutcome): String =
        if (outcome.ok) "登录成功：${outcome.nickname.ifEmpty { outcome.uid }}" else "失败：${outcome.error}"

    // ------------------------------------------------------------------ 账号

    fun setAccountEnabled(providerId: String, uid: String, enabled: Boolean) = action {
        engine.setAccountEnabled(providerId, uid, enabled)
        if (enabled) "已启用该账号" else "已停用该账号"
    }

    fun removeAccount(providerId: String, uid: String) = action {
        if (!engine.removeAccount(providerId, uid)) throw IllegalStateException("账号不存在")
        "账号已删除"
    }

    /**
     * 刷新额度；uid 为空表示刷新该供应商的全部账号。
     *
     * 单账号与「某个供应商全部账号」都可能从供应商详情页这类没有批量结果面板的页面触发，
     * 所以走提示（notice）而不是结果面板；全局的「刷新全部额度」才走 [runBatch]（首页/任务中心有面板）。
     */
    fun refreshCredits(providerId: String, uid: String?) {
        if (uid != null) {
            action {
                val result = engine.refreshCredits(providerId, uid)
                if (result.error.isNotEmpty()) throw IllegalStateException(result.error)
                "额度已刷新"
            }
            return
        }
        // 有界并发（默认并发度）替掉串行；结果汇总成一句话，避免整批失败拼成一条读不完的串。
        action {
            val results = engine.refreshAllCredits(providerId)
            if (results.isEmpty()) throw IllegalStateException("该供应商还没有账号")
            summarizeCredits(results, "（${results.size} 个账号）")
        }
    }

    /** 刷新全部供应商的额度（首页 / 任务中心入口）：有进度与结构化结果面板。 */
    fun refreshAllCredits() = runBatch("刷新额度") { onProgress ->
        if (engine.accounts().isEmpty()) throw IllegalStateException("还没有账号，先去「账号」页添加")
        engine.refreshAllCredits(onProgress = onProgress).map { it.toBatchResult() }
    }

    /** 额度刷新结果 → 批量结果条目（error 为空即成功）。 */
    private fun CreditRefreshResult.toBatchResult(): BatchActionResult =
        BatchActionResult(providerId, uid, nickname, error.isEmpty(), error)

    /** 无结果面板的场景：把额度刷新结果压成一句提示（失败最多列 3 个，其余折叠）。 */
    private fun summarizeCredits(results: List<CreditRefreshResult>, suffix: String): String {
        val failed = results.filter { it.error.isNotEmpty() }
        if (failed.isEmpty()) return "额度已刷新$suffix"
        val head = failed.take(3).joinToString("；") { "${it.nickname.ifEmpty { it.uid }}：${it.error}" }
        val rest = if (failed.size > 3) "…等 ${failed.size} 个" else ""
        return "额度刷新完成：成功 ${results.size - failed.size} 个，失败 ${failed.size} 个（$head$rest）"
    }

    /**
     * 对所有支持签到的账号批量签到。
     *
     * 只签到 Trae 与 WorkBuddy 国内版（cn）：WorkBuddy 国际版（global）与 Trae 国际版
     * 没有签到制度，跳过可避免每次批量签到都报错。
     * 并发与失败隔离由 [GatewayEngine.performActionAll] 负责；这里只负责选目标与汇总结果。
     */
    fun checkinAll() = runBatch("批量签到") { onProgress ->
        val targets = engine.providers()
            .filter { ProviderCapability.CHECKIN in it.capabilities }
            .flatMap { info -> engine.accounts(info.id) }
            // 区域型供应商的国际版没有签到制度（Trae INTL / WorkBuddy global），跳过避免整批报错
            .filter { account -> accountRegion(account.providerId, account.uid) != TraeRegion.INTL.id }
            .map { BatchTarget(it.providerId, it.uid) }
        if (targets.isEmpty()) throw IllegalStateException("还没有支持签到的账号")
        engine.performActionAll(targets, ACTION_CHECKIN, onProgress = onProgress)
    }

    /**
     * 对所有支持任务的账号批量执行任务（目前是 Loomy 的新手任务）。
     *
     * Trae / WorkBuddy 的「任务」实际就是每日签到，已由 [checkinAll] 覆盖。
     * 全部成功时把各账号的上游原话拼进提示（如 Loomy 的领取结果），失败时走结果面板逐条展示。
     */
    fun runAllTasks() = runBatch(
        busyText = "批量任务",
        successNotice = { results ->
            results.filter { it.message.isNotEmpty() }
                .joinToString("；") { "${it.nickname.ifEmpty { it.uid }}：${it.message}" }
                .ifEmpty { "任务完成（${results.size} 个账号）" }
        },
    ) { onProgress ->
        val targets = engine.providers()
            .filter { ProviderCapability.TASKS in it.capabilities }
            .flatMap { info -> engine.accounts(info.id) }
            .map { BatchTarget(it.providerId, it.uid) }
        if (targets.isEmpty()) throw IllegalStateException("还没有支持任务的账号")
        engine.performActionAll(targets, ACTION_TASKS, onProgress = onProgress)
    }

    /**
     * 跑一批批量动作：后台 IO 执行、过程中把进度写回状态、结束后给结构化结果并刷新界面。
     *
     * [block] 在 IO 线程执行，[onProgress] 由引擎在后台线程回调（不保证顺序），
     * 这里只负责把它翻译成 [BatchProgressUi]；窗口内的 Composable 直接读这个字段画进度。
     * [successNotice] 只在全部成功时决定提示文案（默认「成功 N 个」）。
     */
    private fun runBatch(
        busyText: String,
        successNotice: ((List<BatchActionResult>) -> String)? = null,
        block: (onProgress: (BatchProgress) -> Unit) -> List<BatchActionResult>,
    ) {
        viewModelScope.launch {
            // 先置空上一次的结果面板，避免新一轮的进度被旧面板挡住
            _state.value = _state.value.copy(batchProgress = BatchProgressUi(0, 0, ""), batchOutcome = null)
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val results = block { progress -> onBatchProgress(progress) }
                    val failed = results.filter { !it.ok }
                    BatchOutcome(
                        title = busyText,
                        succeeded = results.count { it.ok },
                        failed = failed.size,
                        // 只把失败账号进结果面板：成功账号的文案千篇一律，逐条列出反而淹没真正要看的原因
                        items = failed.map { BatchItem(it.nickname.ifEmpty { it.uid }, it.message) },
                    ) to results
                }
            }
            _state.value = _state.value.copy(batchProgress = null)
            outcome.fold(
                { (result, results) ->
                    if (result.failed > 0) {
                        _state.value = _state.value.copy(
                            batchOutcome = result,
                            notice = "${busyText}完成：成功 ${result.succeeded} 个，失败 ${result.failed} 个",
                        )
                    } else {
                        val text = successNotice?.invoke(results)
                            ?: "${busyText}完成：成功 ${result.succeeded} 个"
                        _state.value = _state.value.copy(notice = text)
                    }
                },
                { error -> _state.value = _state.value.copy(notice = error.message ?: "操作失败") },
            )
            refresh()
        }
    }

    private fun onBatchProgress(progress: BatchProgress) {
        // 多个账号在后台线程并发回调：CAS 循环逐个重试，避免并发写把已完成数覆盖回去。
        val next = BatchProgressUi(progress.done, progress.total, progress.nickname.ifEmpty { progress.uid })
        while (true) {
            val prev = _state.value
            if (_state.compareAndSet(prev, prev.copy(batchProgress = next))) return
        }
    }

    /** 关闭批量结果面板。 */
    fun dismissBatchOutcome() {
        _state.value = _state.value.copy(batchOutcome = null)
    }

    fun performAction(providerId: String, uid: String, action: String, payload: JsonObject = JsonObject()) = action {
        val result: ProviderActionResult = engine.performAction(providerId, uid, action, payload)
        if (!result.ok) throw IllegalStateException(result.message)
        result.message.ifEmpty { "操作完成" }
    }

    fun updateAccountSecret(providerId: String, uid: String, secret: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateAccountSecret(providerId, uid, secret) }
            refresh()
        }
    }

    // ------------------------------------------------------------------ 设备指纹（Trae 专属）

    private fun traeProvider(): TraeProvider =
        engine.registry.get(TraeProvider.ID) as? TraeProvider
            ?: throw IllegalStateException("Trae 供应商不可用")

    fun deviceIdOf(providerId: String, uid: String): String {
        if (providerId != TraeProvider.ID) return ""
        val provider = engine.registry.get(providerId) as? TraeProvider ?: return ""
        val account = engine.account(providerId, uid) ?: return ""
        return provider.deviceIdOf(account)
    }

    /**
     * 账号所属区域（无区域概念的供应商返回空串）；判定统一走 provider 的
     * [dev.aigw.core.provider.RegionAwareSupport.regionOf]——各家的凭证结构与区域字段口径
     * 都不同（Trae 的 secret 是嵌套结构且存区域 id，WorkBuddy 是扁平结构且存主机名），
     * UI 再解析一遍 secret 必然走样。
     */
    fun accountRegion(providerId: String, uid: String): String {
        val provider = engine.registry.get(providerId) as? dev.aigw.core.provider.RegionAwareSupport
            ?: return ""
        val account = engine.account(providerId, uid) ?: return ""
        return provider.regionOf(account).orEmpty()
    }

    fun regenerateDeviceId(providerId: String, uid: String) = action {
        val provider = traeProvider()
        val account = engine.account(providerId, uid) ?: throw IllegalStateException("账号不存在")
        engine.updateAccountSecret(providerId, uid, provider.regenerateDeviceIds(account))
        "已重新生成设备指纹，重新签到试试"
    }

    fun setDeviceId(providerId: String, uid: String, value: String) = action {
        val provider = traeProvider()
        val error = provider.validateDeviceId(value)
        if (error.isNotEmpty()) throw IllegalStateException(error)
        val account = engine.account(providerId, uid) ?: throw IllegalStateException("账号不存在")
        engine.updateAccountSecret(providerId, uid, provider.withDeviceId(account, value))
        "设备指纹已更新，重新签到试试"
    }

    // ------------------------------------------------------------------ 自定义供应商

    fun saveCustomProvider(config: CustomProviderConfig) = action {
        val list = engine.settingsRepository.loadCustomProviders().toMutableList()
        val index = list.indexOfFirst { it.key == config.key }
        if (index >= 0) list[index] = config else list.add(config)
        engine.settingsRepository.saveCustomProviders(list)
        engine.reloadCustomProviders()
        engine.syncCustomAccounts(config)
        "供应商已保存"
    }

    fun removeCustomProvider(key: String) = action {
        val providerId = "custom:$key"
        // 先清掉账号池里的 key，再删配置
        for (account in engine.accounts(providerId)) {
            engine.removeAccount(providerId, account.uid)
        }
        val list = engine.settingsRepository.loadCustomProviders().filterNot { it.key == key }
        engine.settingsRepository.saveCustomProviders(list)
        engine.reloadCustomProviders()
        "供应商已删除"
    }

    // ------------------------------------------------------------------ 代理

    fun proxySettings(): ProxySettings = engine.proxySettings()

    fun updateProxySettings(settings: ProxySettings) {
        _state.value = _state.value.copy(proxy = settings)
        viewModelScope.launch { withContext(Dispatchers.IO) { engine.updateProxySettings(settings) } }
    }

    // ------------------------------------------------------------------ 额度包

    /** 拉取某账号的额度包明细（会打上游）。 */
    fun loadCreditPacks(providerId: String, uid: String) {
        viewModelScope.launch {
            val packs = withContext(Dispatchers.IO) { engine.creditPacks(providerId, uid) }
            _state.value = _state.value.copy(
                creditPacks = _state.value.creditPacks + ("$providerId/$uid" to packs),
            )
        }
    }

    /** 拉取某账号的成长任务明细（会打上游）；支持任务中心的供应商才返回结果。 */
    fun loadTaskList(providerId: String, uid: String) {
        viewModelScope.launch {
            val view = withContext(Dispatchers.IO) { engine.taskList(providerId, uid) }
            if (view == null) return@launch
            _state.value = _state.value.copy(
                taskLists = _state.value.taskLists + ("$providerId/$uid" to view),
            )
        }
    }

    /** 拉取某账号的积分流水明细（会打上游）；支持账本的供应商才返回结果。 */
    fun loadPointsBills(providerId: String, uid: String) {
        viewModelScope.launch {
            val view = withContext(Dispatchers.IO) { engine.pointsBills(providerId, uid) }
            if (view == null) return@launch
            _state.value = _state.value.copy(
                pointsBills = _state.value.pointsBills + ("$providerId/$uid" to view),
            )
        }
    }

    // ------------------------------------------------------------------ 数据管理

    /** 更新保留天数（越界由 core 夹回 1~3650）。 */
    fun setLogRetentionDays(days: Int) = action {
        val clamped = GatewaySettings.clampRetentionDays(days)
        engine.updateSettings(engine.settings().copy(logRetentionDays = clamped))
        if (clamped != days) "保留天数需在 1~3650 之间，已按 $clamped 天保存" else "保留天数已设为 $clamped 天"
    }

    // ------------------------------------------------------------------ 记录

    fun deleteCall(id: String) = action(alsoRefreshStorage = true) {
        engine.callLogStore.delete(id)
        "记录已删除"
    }

    fun clearCalls() = action(alsoRefreshStorage = true) {
        engine.callLogStore.clear()
        "调用记录已清空"
    }

    fun clearLogs() = action(alsoRefreshStorage = true) {
        engine.requestLog.clear()
        "请求日志已清空"
    }

    /** 回读调用记录某字段的完整原文（溢出文件缺失时返回 null，调用方回落用摘要）。 */
    fun engineFullText(id: String, field: dev.aigw.core.usage.CallTextField): String? =
        engine.callLogStore.fullText(id, field)

    // ------------------------------------------------------------------ 关于与更新

    /** 手动检查更新：无论有无新版本都给出提示。 */
    fun checkUpdate() = performUpdateCheck(manual = true)

    /**
     * 自动检查更新：默认开启，每天最多一次（当天检查过即跳过），失败静默。
     * 启动进入主页时调用。
     */
    fun maybeAutoCheckUpdate() {
        if (!_state.value.autoCheckUpdate) return
        // 读「今天检查过没有」也要解密存储，同样不能在主线程做
        viewModelScope.launch {
            val checkedToday = withContext(Dispatchers.IO) {
                updateStore.lastCheckEpochDay() == currentEpochDay()
            }
            if (!checkedToday) performUpdateCheck(manual = false)
        }
    }

    fun setAutoCheckUpdate(enabled: Boolean) {
        _state.value = _state.value.copy(autoCheckUpdate = enabled)
        viewModelScope.launch { withContext(Dispatchers.IO) { updateStore.setAutoCheck(enabled) } }
    }

    /**
     * 切换更新通道。
     *
     * 切换后立刻重置「今天已检查」：否则用户在测试版通道上等到明天才会看到预发布版本，
     * 体验像是「切了没反应」。同时清掉已弹出的更新提示，避免残留旧通道的结果。
     */
    fun setUpdateChannel(channel: UpdateChannel) {
        _state.value = _state.value.copy(updateChannel = channel, updateInfo = null)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                updateStore.setChannel(channel)
                updateStore.setLastCheckEpochDay(Long.MIN_VALUE)
            }
            notice("已切换到${channel.label}通道")
        }
    }

    fun dismissUpdate() {
        _state.value = _state.value.copy(updateInfo = null)
    }

    /** 打开指定仓库地址（由「项目地址」的选择弹窗调用）。 */
    fun openRepo(url: String) {
        if (!openBrowser(url)) {
            _state.value = _state.value.copy(notice = "没有可用的浏览器，无法打开项目地址")
        }
    }

    fun openProjectRepo() = openRepo(PROJECT_URL)

    fun openUpstreamRepo() = openRepo(UPSTREAM_URL)

    fun openReleasePage(url: String) {
        if (!openBrowser(url)) {
            _state.value = _state.value.copy(notice = "没有可用的浏览器，无法打开下载页")
        }
    }

    // ------------------------------------------------------------------ 应用内更新

    /**
     * 应用内下载最新 APK。
     *
     * 没有 APK 直链（Release 里没传资产）时退回浏览器，不给用户一个「点了没反应」的按钮。
     */
    /** 当前下载尝试到第几个地址（镜像失败会自动换下一个）。 */
    private var downloadAttempt = 0

    /**
     * 正在进行的下载轮询任务。
     *
     * 必须持有并取消：用户连点两次「立即更新」会起两个轮询协程，
     * 它们盯着同一个 activeDownloadId —— 下载完成时**两次**拉起安装器，
     * 表现为连续弹两个安装界面；而且旧的协程还会拿着过期的 downloadAttempt
     * 把新一轮的计数改乱，镜像轮换彻底失控。
     */
    private var downloadWatchJob: Job? = null

    fun startInAppUpdate() {
        val info = _state.value.updateInfo ?: return
        if (info.apkUrl.isEmpty()) {
            notice("该版本没有提供安装包直链，已打开下载页")
            openReleasePage(info.url)
            return
        }
        // 已经在下载中：不重复发起，只提示一句
        if (downloadWatchJob?.isActive == true) {
            notice("正在下载中，请稍候…")
            return
        }
        downloadAttempt = 0
        beginDownload(info)
    }

    /**
     * 启动一次下载尝试。
     *
     * GitHub 直链在国内经常拉不动，所以优先走加速镜像；某个镜像失败时
     * 自动换下一个（GitHub 直链留作最后兜底），避免「一次失败就让用户手动去点浏览器」。
     */
    private fun beginDownload(info: UpdateInfo) {
        val candidates = dev.aigw.app.update.ApkUpdater.downloadCandidates(info.apkUrl)
        if (downloadAttempt >= candidates.size) {
            notice("所有下载地址都失败了，已打开下载页")
            openReleasePage(info.url)
            return
        }
        val id = dev.aigw.app.update.ApkUpdater.download(
            app,
            candidates[downloadAttempt],
            info.version,
            attempt = downloadAttempt,
        )
        if (id < 0) {
            downloadAttempt++
            beginDownload(info)
            return
        }
        notice("开始下载（第 ${downloadAttempt + 1}/${candidates.size} 个地址），完成后会弹出安装")
        watchDownload(info.version, info)
    }

    /** 轮询下载状态：完成就拉起安装器，失败就提示。 */
    private fun watchDownload(version: String, info: UpdateInfo) {
        // 每轮新下载都取代旧的轮询（旧协程可能因镜像失败已 return，这里再兜一次）
        downloadWatchJob?.cancel()
        downloadWatchJob = viewModelScope.launch {
            repeat(600) {  // 600 × 2s = 最多等 20 分钟（大包在慢网下需要）
                delay(2_000)
                when (val st = dev.aigw.app.update.ApkUpdater.downloadState(app)) {
                    dev.aigw.app.update.ApkUpdater.DownloadState.Done -> {
                        val uri = dev.aigw.app.update.ApkUpdater.downloadedUri(app)
                        if (uri == null) {
                            notice("下载完成但无法读取安装包")
                            return@launch
                        }
                        // 未授权「安装未知应用」时先引导授权，下次点更新即可直接装
                        if (!dev.aigw.app.update.ApkUpdater.canInstall(app)) {
                            notice("请先允许「安装未知应用」，然后到 ${dev.aigw.app.update.ApkUpdater.downloadDirLabel()} 手动安装")
                            dev.aigw.app.update.ApkUpdater.openInstallPermission(app)
                            return@launch
                        }
                        val err = dev.aigw.app.update.ApkUpdater.install(app, uri)
                        if (err != null) notice(err)
                        return@launch
                    }
                    is dev.aigw.app.update.ApkUpdater.DownloadState.Failed -> {
                        // 镜像挂了就换下一个地址再试，别让用户看到一次失败就结束
                        downloadAttempt++
                        val next = dev.aigw.app.update.ApkUpdater.downloadCandidates(info.apkUrl)
                        if (downloadAttempt < next.size) {
                            notice("该地址下载失败（${st.message}），正在换下一个…")
                            beginDownload(info)
                        } else {
                            notice("下载失败：${st.message}")
                        }
                        return@launch
                    }
                    else -> Unit
                }
            }
            notice("下载超时，请到 ${dev.aigw.app.update.ApkUpdater.downloadDirLabel()} 查看")
        }
    }

    /**
     * 检查更新的统一实现。
     *
     * [manual] 为 true（用户点「检查更新」）时无论结果都给提示；false（自动检查）只在
     * 发现新版本时弹窗，其余情况静默，避免打扰。
     */
    private fun performUpdateCheck(manual: Boolean) {
        if (_state.value.updateChecking) return
        _state.value = _state.value.copy(updateChecking = true)
        viewModelScope.launch {
            // ★ try/finally 兜住 updateChecking：中间任何一处抛异常（JSON 解析、NPE 等）
            //   都会让这个标志永远停在 true —— 此后手动「检查更新」被开头的 return 挡掉、
            //   自动检查也再不会跑，按钮永久置灰，只能杀进程重来。
            try {
                val outcome = withContext(Dispatchers.IO) { runCatching { fetchLatestRelease() } }
                // 自动检查无论成败都记一次「今天已检查」，保证每天最多一次。
                // ★ 必须放 IO 线程：加密存储的 write 是「全量重加密 + fsync」，几百毫秒，
                //   在主线程做就是一次可见的卡顿（冷启动自动检查时正好压在首帧上）。
                if (!manual) {
                    withContext(Dispatchers.IO) { updateStore.setLastCheckEpochDay(currentEpochDay()) }
                }
                outcome.fold(
                    onSuccess = { info ->
                        if (info.version.isEmpty()) {
                            // 通道里还没发布过任何版本（例如测试版通道刚开）
                            if (manual) {
                                _state.value = _state.value.copy(
                                    notice = "已是最新版本（${installedVersionName()}）；${_state.value.updateChannel.label}通道暂无新版本",
                                )
                            }
                            return@fold
                        }
                        // 测试通道同版本号会反复重建（CI 每次都是新包），
                        // 仅比版本号会永远显示「已是最新」。补一条：发布晚于本机安装时间也算有更新。
                        val rebuilt = _state.value.updateChannel == UpdateChannel.Beta &&
                            !isNewer(info.version, installedVersionName()) &&
                            info.publishedAt > installedUpdateTime() + 60_000L
                        if (isNewer(info.version, installedVersionName()) || rebuilt) {
                            _state.value = _state.value.copy(updateInfo = info)
                        } else if (manual) {
                            _state.value = _state.value.copy(notice = "已是最新版本（${installedVersionName()}）")
                        }
                    },
                    onFailure = { e ->
                        if (manual) {
                            _state.value = _state.value.copy(notice = "检查更新失败：${e.message ?: "网络异常"}")
                        }
                    },
                )
            } finally {
                _state.value = _state.value.copy(updateChecking = false)
            }
        }
    }

    /**
     * 直连 GitHub 取最新版本。
     *
     * 两个通道用不同地址：
     * - 公开版：`/releases/latest` —— GitHub 保证返回最新**正式** Release
     * - 测试版：`/releases/tags/ci-latest` —— CI 每次构建覆盖发布的固定 tag
     *
     * 测试版刻意不遍历 Release 列表：CI 天天出包，逐个建 Release 只会让列表变垃圾堆，
     * 还要在 App 端按版本号挑最大（发布顺序 ≠ 版本顺序，容易挑错）。
     * 固定 tag + 覆盖资产 = 一个永不变的地址，指向最新 CI 包。
     */
    private fun fetchLatestRelease(): UpdateInfo {
        val beta = _state.value.updateChannel == UpdateChannel.Beta
        val api = if (beta) CI_RELEASE_API else RELEASES_API
        val conn = (URL(api).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UPDATE_UA)
        }
        try {
            val status = conn.responseCode
            if (status == 404) {
                throw IllegalStateException(if (beta) "测试通道暂无构建包" else "暂无发布版本")
            }
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JsonParser.parseString(body).asJsonObject
            // ci-latest 的 tag 恒为 "ci-latest"，真正的版本号在 Release 名字里（v0.1.1.44-beta）
            val tag = obj.get("tag_name")?.asString?.trim().orEmpty()
            val nameVersion = obj.get("name")?.asString.orEmpty()
                .trim().removePrefix("v").removePrefix("V")
            val version = if (beta) {
                nameVersion.ifBlank { tag.removePrefix("v") }
            } else {
                tag.removePrefix("v")
            }
            if (version.isEmpty()) throw IllegalStateException("未找到版本信息")
            val url = obj.get("html_url")?.asString.orEmpty().ifEmpty { "$PROJECT_URL/releases" }
            val notes = obj.get("body")?.asString.orEmpty().trim()
            val publishedAt = runCatching {
                java.time.Instant.parse(obj.get("published_at")?.asString.orEmpty()).toEpochMilli()
            }.getOrDefault(0L)
            // 找 APK 资产直链：有了它才能应用内下载，否则只能跳浏览器
            val apkUrl = obj.getAsJsonArray("assets")
                ?.mapNotNull { it.asJsonObject }
                ?.firstOrNull { it.get("name")?.asString?.endsWith(".apk", ignoreCase = true) == true }
                ?.get("browser_download_url")?.asString
                .orEmpty()
            return UpdateInfo(
                version = version,
                url = url,
                notes = notes,
                apkUrl = apkUrl,
                publishedAt = publishedAt,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 逐段比较版本号，远端更大返回 true。 */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val l = local.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 本机 APK 的安装/更新时间（毫秒）；读不到返回 0。 */
    private fun installedUpdateTime(): Long = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    private fun installedVersionName(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun currentEpochDay(): Long = java.time.LocalDate.now().toEpochDay()

    // ------------------------------------------------------------------ 内部

    /**
     * 执行一个操作并在完成后刷新界面。
     *
     * [alsoRefreshStorage] 只在操作会改变存储占用时开启：占用统计要扫全量存储键并逐个解密，
     * 挂在每次点击上都太贵。
     */
    private fun action(alsoRefreshStorage: Boolean = false, block: () -> String) {
        viewModelScope.launch {
            val notice = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: Exception) {
                    e.message ?: "操作失败"
                }
            }
            _state.value = _state.value.copy(notice = notice)
            refresh()
            if (alsoRefreshStorage) refreshStorage()
        }
    }
}
