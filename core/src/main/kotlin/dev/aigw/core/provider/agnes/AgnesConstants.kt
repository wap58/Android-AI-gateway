package dev.aigw.core.provider.agnes

/**
 * Agnes AI（双区域，OpenAI 兼容）。
 *
 * ## 两个站点
 *
 * | 区域 | 接口 | 申请 Key |
 * |---|---|---|
 * | 国内 `cn` | https://api.agnes-ai.cn/v1 | https://platform.agnes-ai.cn/ |
 * | 国际 `global` | https://apihub.agnes-ai.com/v1 | https://platform.agnes-ai.com/ |
 *
 * 两站都提供免费额度，**不需要签到、没有可查的余额**，
 * 限制是每分钟 10 次请求 —— 所以这个供应商：
 * - 无 `CREDIT_REFRESH`（没有可查的余额）
 * - 无 `CHECKIN`（没有签到制度）
 *
 * ## 鉴权
 *
 * 标准 `Authorization: Bearer <apiKey>`。两个区域各用一个 Key，不通用。
 */
object AgnesConstants {

    const val REGION_CN = "cn"
    const val REGION_GLOBAL = "global"

    const val BASE_CN = "https://api.agnes-ai.cn/v1"
    const val BASE_GLOBAL = "https://apihub.agnes-ai.com/v1"

    const val PLATFORM_CN = "https://platform.agnes-ai.cn/"
    const val PLATFORM_GLOBAL = "https://platform.agnes-ai.com/"

    /** 默认对话模型（两个站点相同）。 */
    const val DEFAULT_MODEL = "agnes-3.0-flash"

    /**
     * 可用的对话模型白名单。
     *
     * 上游 `/v1/models` 会返回 9 个，但**只有一个能真正对话**：
     * ```
     * agnes-3.0-flash        ✓ 对话（免费层可用）
     * agnes-2.5-flash        ✗ 即将下架，不再保留
     * agnes-2.5-pro(-alpha/-beta)  ✗ 付费，免费层用不了
     * agnes-image-2.1/2.5-flash    ✗ 文生图，走别的接口
     * agnes-video-2.5(-flash)      ✗ 文生视频，走别的接口
     * ```
     * 文生图/视频不是 `/chat/completions` 能调的，pro 系要付费，
     * 所以实时拉取后按这份白名单过滤，界面上只暴露能用的。
     */
    val ALLOWED_MODELS = setOf("agnes-3.0-flash")

    /** 内置兜底清单（拉取失败时用）。 */
    val MODELS = ALLOWED_MODELS.toList()

    /** 每分钟 10 次的软限流，用于提示文案。 */
    const val RATE_LIMIT_PER_MINUTE = 10

    fun baseOf(region: String): String =
        if (region == REGION_GLOBAL) BASE_GLOBAL else BASE_CN

    fun platformOf(region: String): String =
        if (region == REGION_GLOBAL) PLATFORM_GLOBAL else PLATFORM_CN

    fun labelOf(region: String): String =
        if (region == REGION_GLOBAL) "国际版" else "国内版"
}

/**
 * 凭证：一个 API Key + 所属区域。
 *
 * 存成 JSON：`{"apiKey":"sk-...","region":"cn"}`
 */
internal data class AgnesCredential(
    val apiKey: String,
    val region: String,
) {
    companion object {
        fun parse(raw: String): AgnesCredential? {
            val text = raw.trim()
            if (text.isEmpty()) return null

            runCatching {
                val o = com.google.gson.JsonParser.parseString(text).asJsonObject
                val key = o.get("apiKey")?.asString.orEmpty()
                val region = o.get("region")?.asString.orEmpty()
                if (key.isNotEmpty()) {
                    return AgnesCredential(
                        apiKey = key,
                        region = region.ifEmpty { AgnesConstants.REGION_CN },
                    )
                }
            }

            // 兜底：把裸 key 当国内版（用户直接在输入框粘贴的情况）
            if (!text.contains("{") && !text.contains("\n")) {
                return AgnesCredential(apiKey = text, region = AgnesConstants.REGION_CN)
            }
            return null
        }

        fun of(apiKey: String, region: String): AgnesCredential =
            AgnesCredential(apiKey = apiKey.trim(), region = region)
    }
}
