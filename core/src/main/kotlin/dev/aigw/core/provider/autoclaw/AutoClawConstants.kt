package dev.aigw.core.provider.autoclaw

import java.security.MessageDigest
import java.util.UUID

/**
 * AutoClaw（智谱 AutoGLM 客户端）的常量与签名。
 *
 * ## 协议来源
 * 从官方客户端 AutoClaw 1.17.8 逆向，交叉验证自开源反代 `hirotomaso/autoclawpi`。
 * 和 Qoder 相比简单得多：签名是**纯 MD5 拼接**，没有 RSA/AES、没有自定义 Base64、
 * 响应也是标准 OpenAI SSE（不用解双层信封）。
 *
 * ## 签名
 * ```
 * X-Auth-Sign = md5("100003" + "&" + 秒级时间戳 + "&" + APP_KEY)
 * ```
 * 其中 APP_ID / APP_KEY 是客户端内置的固定值，`X-Auth-TimeStamp` 必须与参与签名的
 * 时间戳完全一致，否则服务端验签失败。
 */
internal object AutoClawConstants {
    const val ID = "autoclaw"
    const val DISPLAY_NAME = "AutoClaw"

    /** 用户侧 API（OAuth、刷新、签到、奖励）——国际站。 */
    const val USER_API = "https://autoglm-api.autoglm.ai"

    /**
     * 国内 userapi 宿主（沙箱管理、积分、登录）。
     *
     * 与 [CN_ACCELERATION] 的区别：后者带 `/autoclaw-proxy/proxy/autoclaw` 前缀，
     * 是客户端用的推理代理；这里是**不带路径前缀**的 userapi 根，
     * 沙箱列表/申请/积分都挂在它下面（`/agentdr/...`、`/agent-assetmgr/...`）。
     */
    const val CN_USER_API = "https://autoglm-acceleration-api.zhipuai.cn"

    /** 按区域选 userapi 根（沙箱、积分、签到都挂在这下面）。 */
    fun userApiHost(realm: String): String =
        if (realm == REALM_INTL) USER_API else CN_USER_API

    /**
     * 国内站（官方客户端配置里的 `zai.baseUrl` 前缀）。
     *
     * 来自澳龙 `~/.openclaw-autoclaw/openclaw.json` 的 models.providers.zai.baseUrl。
     * 和官网 autoclaw.zhipuai.cn 是两个不同的宿主：官网只发安装包，API 在这里。
     */
    const val CN_ACCELERATION = "https://autoglm-acceleration-api.zhipuai.cn/autoclaw-proxy/proxy/autoclaw"

    /** 国际站推理前缀。 */
    const val INFERENCE_BASE = "$USER_API/autoclaw-proxy/proxy/autoclaw"

    /**
     * 按区域选推理基址。
     *
     * **国内站的 token 只能在加速域名用**：国内登录走 `autoglm.zhipuai.cn`，
     * 拿它去 `autoglm-api.autoglm.ai` 推理会 401 Invalid token。
     * 目前登录只实现了国内站，所以一律走加速域名。
     */
    fun inferenceBaseFor(realm: String): String =
        if (realm == REALM_INTL) INFERENCE_BASE else CN_ACCELERATION

    const val REALM_CN = "cn"
    const val REALM_INTL = "intl"

    /** 客户端固定值，出现在签名与 X-Auth-Appid 里。 */
    const val APP_ID = "100003"
    const val APP_KEY = "38d2391985e2369a5fb8227d8e6cd5e5"

    /** 客户端版本号，服务端会校验；跟随官方客户端。 */
    const val CLIENT_VERSION = "1.18.1"

    /** 来源标识，登录与刷新都要带。 */
    const val SOURCE_ID = "autoclaw"

    // ------------------------------------------------------------------ 路径
    const val PATH_OAUTH_URL = "/userapi/overseasv1/oauth-url"
    const val PATH_REFRESH = "/userapi/v1/refresh"

    /** 旧刷新端点：`refresh` 返回 400002（签名校验失败）时降级重试。 */
    const val PATH_REFRESH_LEGACY = "/userapi/v1/agent-refresh"
    const val PATH_TASK_COMPLETE = "$USER_API/autoclaw-proxy/proxy/autoclaw-task-complete"
    const val PATH_NEWBIE_TOKEN = "$USER_API/autoclaw-proxy/proxy/autoclaw-newbie-guide/token"
    const val PATH_PROMOTION_REWARD = "$USER_API/autoclaw-proxy/proxy/autoclaw-promotion-reward"

    /** 签到任务 id（官方「每日签到」）。 */
    const val TASK_DAILY_SIGNIN = "daily_signin"

    // ------------------------------------------------------------------ 签名

    /** `md5("100003&<ts>&<APP_KEY>")`，小写十六进制。 */
    fun sign(epochSeconds: Long): String {
        val raw = "$APP_ID&$epochSeconds&$APP_KEY"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 普通用户侧请求头（非推理）。
     *
     * 注意 `X-Auth-TimeStamp` 与签名用的时间戳必须是同一个值，
     * 所以这里统一在函数内取一次 now，不要外部再生成。
     */
    fun userApiHeaders(nowSeconds: Long = System.currentTimeMillis() / 1000): MutableMap<String, String> =
        mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "*/*",
            "X-Product" to "autoclaw",
            "X-Version" to CLIENT_VERSION,
            "X-Tm" to "linux",
            "X-Auth-Appid" to APP_ID,
            "X-Auth-TimeStamp" to nowSeconds.toString(),
            "X-Auth-Sign" to sign(nowSeconds),
            "X-Trace-Id" to UUID.randomUUID().toString(),
            "X-Lang" to "en",
            "X-Client-Type" to "pc",
        )

    /**
     * 带凭证的 userapi 请求头。
     *
     * ★ 必须带 authorization：刷新、余额、签到、任务列表这些 userapi 接口都是
     *   Bearer 鉴权的，漏了会返回 `400000 User not logged in`（看起来像 refresh_token
     *   失效，实际是根本没带 token —— 2026-11-11 踩过这个坑）。
     *
     * 剥一次 `Bearer ` 再拼：登录拿到的 access_token 本身可能已带前缀，
     * 直接拼会得到 `Bearer Bearer eyJ…`，上游按空格切分出非法 JWT → 恒定 401。
     */
    fun userApiHeaders(
        accessToken: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): MutableMap<String, String> {
        val headers = userApiHeaders(nowSeconds)
        val raw = accessToken.removePrefix("Bearer ").removePrefix("bearer ").trim()
        if (raw.isNotEmpty()) headers["authorization"] = "Bearer $raw"
        return headers
    }

    /**
     * 推理请求头。
     *
     * 逐项对照官方客户端配置（澳龙 `openclaw.json` 的 `models.providers.zai.models[].headers`）：
     * ```
     * X-Authorization: <token>
     * X-Request-Model: <route id>
     * X-Tm: linux
     * X-Version: 1.18.1
     * X-Product: autoclaw
     * X-Channel: official
     * X-Lang: zh-CN
     * X-Client-Type: web
     * ```
     * 官方**没有** `X-Harness-Type` / `X-Request-Id` / 任何 `X-Auth-*` 签名头——
     * 多带会被上游判成别的 API 调用，实测表现为 406 Not Acceptable。
     */
    fun inferenceHeaders(
        accessToken: String,
        routeModelId: String,
    ): MutableMap<String, String> {
        val bearer = if (accessToken.startsWith("Bearer ")) accessToken else "Bearer $accessToken"
        return mutableMapOf(
            "Content-Type" to "application/json",
            "X-Authorization" to bearer,
            "X-Request-Model" to routeModelId,
            "X-Tm" to "linux",
            "X-Version" to CLIENT_VERSION,
            "X-Product" to "autoclaw",
            "X-Channel" to "official",
            "X-Lang" to "zh-CN",
            "X-Client-Type" to "web",
        )
    }

    // ------------------------------------------------------------------ 模型映射

    /**
     * OpenAI 风格模型名 → AutoClaw route id。
     *
     * 规则来自参考实现，实测坑点：DeepSeek 系列必须带**版本日期**后缀，
     * 否则 upstream 报模型不存在；glm-5.3 / glm-5.2 走 `zaicoding_`，
     * 其余走 `zai_`。
     */
    fun routeId(model: String): String {
        val m = model.trim()
        if (m.isEmpty()) return "zai_auto"
        if (m.contains('_')) return m // 已经是 route id
        return when (m) {
            "deepseek-v4-pro" -> "tdpsk_deepseek-v4-pro-202606"
            "deepseek-v4-flash" -> "tdpsk_deepseek-v4-flash-202605"
            "glm-5.3", "glm-5.2" -> "zaicoding_$m"
            else -> "zai_$m"
        }
    }

    /** route id → 裸模型名（`zaicoding_glm-5.3` → `glm-5.3`）。 */
    fun bodyModel(routeId: String): String {
        val i = routeId.indexOf('_')
        return if (i >= 0) routeId.substring(i + 1) else routeId
    }
}
