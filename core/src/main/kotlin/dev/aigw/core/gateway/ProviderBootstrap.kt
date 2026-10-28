package dev.aigw.core.gateway

import dev.aigw.core.provider.ProviderRegistry
import dev.aigw.core.provider.agnes.AgnesProvider
import dev.aigw.core.provider.codebuddy.CodeBuddyProvider
import dev.aigw.core.provider.lingxi.LingxiProvider
import dev.aigw.core.provider.loomy.LoomyProvider
import dev.aigw.core.provider.raccoon.RaccoonProvider
import dev.aigw.core.provider.trae.TraeConstants
import dev.aigw.core.provider.trae.TraeProvider
import dev.aigw.core.provider.trae.TraeVersion

/**
 * 内置供应商的登记处。
 *
 * 新增一个内置供应商时，只在这里加一行 `register(...)`，网关与其它 provider 都不需要改。
 */
internal fun registerBuiltinProviders(engine: GatewayEngine) {
    val registry: ProviderRegistry = engine.registry

    registry.register(
        TraeProvider(
            store = engine.store(),
            version = {
                val settings = engine.providerSettings(TraeProvider.ID)
                TraeVersion(
                    ideVersion = settings.option("ideVersion", "0.1.52"),
                    ideVersionCode = settings.option("ideVersionCode", "20260811"),
                )
            },
            defaultModel = {
                engine.providerSettings(TraeProvider.ID).defaultModel
                    .ifEmpty { TraeConstants.DEFAULT_CONFIG_NAME }
            },
            hooks = engine.hooks,
            nowMillis = { engine.now() },
        ),
    )

    registry.register(
        LoomyProvider(
            defaultModel = { engine.providerSettings(LoomyProvider.ID).defaultModel },
            nowMillis = { engine.now() },
            hooks = engine.hooks,
        ),
    )

    // Antigravity（Google 账号 OAuth）已从内置列表移除：
    // 用户反馈用不上，且它的 OAuth 流程在部分网络环境下不可达。
    // 实现类保留，需要时可在此处恢复注册。

    registry.register(
        CodeBuddyProvider(
            region = { engine.providerSettings(CodeBuddyProvider.ID).option("region", CodeBuddyProvider.REGION_CN) },
            nowMillis = { engine.now() },
            // 接上 hooks：刷新失败从前静默无日志，排查「登录失效」时看不到任何原因
            hooks = engine.hooks,
        ),
    )

    registry.register(
        RaccoonProvider(
            hooks = engine.hooks,
            nowMillis = { engine.now() },
        ),
    )


    // Agnes AI：标准 OpenAI 兼容接口，国内/国际两个独立站点，各自的 API Key。
    // 无签到、无余额可查，限制是每分钟 10 次。
    registry.register(AgnesProvider(nowMillis = { engine.now() }))

    // WPS 灵犀（lingxi.kdocs.cn）：登录态就是一个 wps_sid cookie，
    // 对话走「建会话 → 会话内 completions」，额度单位是智点。
    registry.register(LingxiProvider(nowMillis = { engine.now() }))
}
