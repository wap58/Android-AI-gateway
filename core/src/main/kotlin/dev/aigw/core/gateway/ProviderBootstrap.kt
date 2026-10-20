package dev.aigw.core.gateway

import dev.aigw.core.provider.ProviderRegistry
import dev.aigw.core.provider.codebuddy.CodeBuddyProvider
import dev.aigw.core.provider.loomy.LoomyProvider
import dev.aigw.core.provider.opencode.OpenCodeProvider
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
        ),
    )

    registry.register(
        RaccoonProvider(
            hooks = engine.hooks,
            nowMillis = { engine.now() },
        ),
    )

    // OpenCode 免费通道：凭据是公开哨兵 Bearer public，不需要真实账号。
    //
    // 作为默认集成的供应商之一，默认启用。
    // 刻意**不**在池里预建账号：账号池的 size 被「用户是否配置过供应商」的判断依赖
    // （见 resolveRoute 的 activeProviders.size == 1 分支），塞占位账号会让
    // 用户明明只配了 A、却因为这里多出一个而被判成「配了两家」。
    // 账号由 pickAccount 对 [NoAccountSupport] 现场合成。
    registry.register(OpenCodeProvider(nowMillis = { engine.now() }))
}
