package dev.aigw.core.provider.qoder

/**
 * Qoder 的区域常量：国内（qoder.com.cn）与国际（qoder.sh）是两套完全独立的
 * 账号体系与端点，登录、轮询、推理都不能跨区混用。
 */
internal object QoderRealm {
    const val CN = "cn"
    const val INTL = "intl"

    val website = mapOf(
        CN to "https://qoder.com.cn",
        INTL to "https://qoder.com",
    )

    /** 业务/登录接口基址。 */
    val openapi = mapOf(
        CN to "https://openapi.qoder.com.cn",
        INTL to "https://openapi.qoder.sh",
    )

    /**
     * 业务接口基址候选（按顺序试）。
     *
     * 国际版只有 `openapi.qoder.sh` 不够：该域名在部分网络/时期不可达，
     * 且活动平台另有一个入口 `center.qoder.sh`（官方 0.4.3 客户端仍引用，
     * 实测国际版 200、国内 401）。只用一个基址的表现是「活动列表拿不到 → 签到无效」，
     * 以及额度一直取不到数。
     */
    fun openapiCandidates(realm: String): List<String> {
        val r = of(realm).ifEmpty { CN }
        val primary = openapi.getValue(r)
        // 只列**接口**域名，不要把官网（qoder.com / qoder.com.cn）放进来：
        // 官网只提供网页，没有 /sash/ 与 /api/ 业务接口，试它只会
        // 拿到 200 的 HTML 让 JSON 解析白跑一轮。
        return if (r == INTL) {
            listOf(primary, "https://center.qoder.sh")
        } else {
            listOf(primary)
        }
    }

    /**
     * 推理网关：签名只覆盖 path，因此同一请求换主机后签名依旧有效。
     *
     * 国际版**不是** gateway.qoder.sh：官方 0.4.3 客户端把推理拆成
     * inference=api3 / securityInference=api2，api1 已不在候选首位。
     * 用错主机表现为连不上或 404，而不是签名错误。
     */
    val gateway = mapOf(
        CN to "https://gateway.qoder.com.cn",
        INTL to "https://api3.qoder.sh",
    )

    val gatewayFallback = mapOf(
        CN to listOf("https://gateway.qoder.com.cn"),
        INTL to listOf("https://api3.qoder.sh", "https://api2.qoder.sh", "https://api1.qoder.sh"),
    )

    /**
     * 设备授权的 URL 参数差异（官方逆向）。
     *
     * 国内版带 redirect_uri 与带横线的 nonce；国际版不带 redirect_uri，
     * nonce 是 32 位无横线 hex。参数错一个，授权页会直接报错或授权后拿不到 token。
     */
    val sendRedirectUri = mapOf(CN to true, INTL to false)
    val sendClientId = mapOf(CN to true, INTL to true)
    val nonceDashed = mapOf(CN to true, INTL to false)

    /**
     * client_id 必须是官方客户端的真实 UUID。
     *
     * 填个像 "qoder" 这样的自造串时，授权页会渲染成功但点授权回报
     * 「参数无效」——因为它拿 client_id 去查上游注册表查不到。
     */
    val clientId = mapOf(
        CN to "1c5e33e1-364d-4ce6-b02c-acaa81274a5c",
        INTL to "e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb",
    )

    /** 只有国内版发 redirect_uri；国际版发了反而会被判非法。 */
    val redirectUri = mapOf(
        CN to "qoder-work-cn://",
        INTL to "",
    )

    /** 各区域官方 User-Agent。 */
    val userAgent = mapOf(
        CN to "QoderWork/1.1.75",
        INTL to "Qoder/1.1.75",
    )

    fun of(value: String): String = when (value.lowercase()) {
        INTL, "global", "intl" -> INTL
        CN -> CN
        // 空串表示「未知」，不能默认成国内：老凭证没有 realm 字段，
        // 一刀切默认会把国际版账号错归到国内版列表。
        else -> ""
    }

    /** 已知区域才为 true（用于判断凭证是否带区域信息）。 */
    fun isKnown(value: String): Boolean = value == CN || value == INTL

    /**
     * 解析成**一定可用**的区域：未知时退回国内。
     *
     * 取端点时必须走这个而不是 [of]——`gateway.getValue("")` 会直接抛
     * NoSuchElementException，把一次请求炸成崩溃。
     */
    fun safe(value: String): String = of(value).ifEmpty { CN }
}

/** Qoder 的一个模型条目（来自官方 model_catalog）。 */
internal data class QoderModel(
    val key: String,
    val displayName: String,
    val isVl: Boolean = false,
    val isReasoning: Boolean = false,
    val factor: Double = 0.0,
    val maxInput: Int = 180000,
)

/**
 * 官方模型清单（快照）。上游 `/algo/api/v2/model/list` 需要 COSY 签名才能拉，
 * 所以这里内置一份，作为无网络/未登录时的兜底；登录后仍会尝试实拉覆盖。
 */
internal object QoderCatalog {

    /**
     * 国内版官方清单（来自网页版「选择模型」面板实拍 + 参考实现的元数据）。
     *
     * `is_reasoning` / `max_input_tokens` 不是随便填的：它们会写进上游请求体的
     * `model_config`，填错会影响上游对请求的校验与路由（例如 dfmodel 实际
     * 不是推理模型，标成 true 就可能被判参数非法）。
     */
    val CN = listOf(
        QoderModel("qmodel_38max", "Qwen3.8-Max", isVl = true, isReasoning = true, factor = 0.2),
        QoderModel("qfmodel", "Qwen3.8-Flash", isVl = true, isReasoning = true, factor = 0.04),
        QoderModel("dmodel", "DeepSeek-V4-Pro", isVl = true, isReasoning = true, factor = 0.5, maxInput = 96000),
        QoderModel("dfmodel", "DeepSeek-Flash", isVl = true, isReasoning = false, factor = 0.1),
        QoderModel("gmodel", "GLM-5.3", isVl = true, isReasoning = true, factor = 0.8),
        QoderModel("gfmodel", "GLM-5.3-Flash", isVl = true, isReasoning = true, factor = 0.1, maxInput = 1000000),
        QoderModel("kmodel_latest", "Kimi-K3.1-Pro", isVl = true, isReasoning = false, factor = 1.0),
        QoderModel("kmodel", "Kimi-K2.8-Preview", isVl = true, isReasoning = true, factor = 0.8),
        // key 是 jmodel（实测自 /algo/api/v2/model/list 的 chat 列表）
        QoderModel("jmodel", "Step-5-Preview", isVl = true, isReasoning = true, factor = 0.3),
        QoderModel("mmodel", "MiniMax-M3.1-Flash", factor = 0.1),
    )

    /**
     * 国际版官方清单（网页版「选择模型」面板实拍 + 参考实现的元数据）。
     *
     * 两区差异明显：国际版有 Sonus/Cantus，没有 QoDuck；
     * 且同 key 的 `is_reasoning` / `max_input_tokens` 与国内不同
     * （如 kmodel 国际是 False、dmodel 国际上限 1M）。
     * 这些值会写进请求体的 model_config，填错会影响上游校验。
     */
    val INTL = listOf(
        QoderModel("qmodel_38max", "Qwen3.8-Max", isVl = true, isReasoning = true, factor = 0.2),
        QoderModel("qfmodel", "Qwen3.8-Flash", isVl = true, isReasoning = true, factor = 0.04),
        QoderModel("kmodel_latest", "Kimi-K3.1-Pro", isVl = true, isReasoning = false, factor = 1.0),
        QoderModel("kmodel", "Kimi-K2.8-Preview", isVl = true, isReasoning = false, factor = 0.8),
        QoderModel("gmodel", "GLM-5.3", isVl = true, isReasoning = true, factor = 0.8),
        QoderModel("gfmodel", "GLM-5.3-Flash", isVl = true, isReasoning = true, factor = 0.1, maxInput = 1000000),
        QoderModel("dmodel", "DeepSeek-V4-Pro", isVl = true, isReasoning = true, factor = 0.5, maxInput = 1000000),
        QoderModel("dfmodel", "DeepSeek-Flash", isVl = true, isReasoning = true, factor = 0.1, maxInput = 1000000),
        QoderModel("mmodel", "MiniMax-M3.1-Flash", isVl = true, isReasoning = false, factor = 0.1),
    )

    fun of(realm: String): List<QoderModel> =
        if (QoderRealm.of(realm) == QoderRealm.INTL) INTL else CN

    fun find(key: String, realm: String): QoderModel? =
        of(realm).firstOrNull { it.key == key }

    /**
     * 默认模型。
     *
     * 面板清单里没有 `auto` 这一项（Auto 是网页版的自动路由，不在可选列表），
     * 所以默认用清单首项，避免上层拿 `auto` 去请求时上游判非法。
     */
    fun defaultKey(realm: String): String = of(realm).firstOrNull()?.key ?: "qmodel_38max"
}
