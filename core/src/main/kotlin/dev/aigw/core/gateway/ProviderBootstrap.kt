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

    // OpenCode 免费通道：不需要真实账号（凭据是公开哨兵 Bearer public），
    // 但账号池按「provider + 账号」调度，所以补一个占位账号让它能被选中。
    // 已经存在时不覆盖，避免每次启动都写盘。
    val openCode = OpenCodeProvider(nowMillis = { engine.now() })
    registry.register(openCode)
    if (engine.pool.account(OpenCodeProvider.ID, OpenCodeProvider.PLACEHOLDER_UID) == null) {
        engine.pool.upsert(
            dev.aigw.core.provider.ProviderAccount(
                providerId = OpenCodeProvider.ID,
                uid = OpenCodeProvider.PLACEHOLDER_UID,
                nickname = "公共免费通道",
                secret = "{}",
            ),
        )
    }
}
