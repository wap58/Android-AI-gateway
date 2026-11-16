package dev.aigw.core.provider.autoclaw

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * AutoClaw 凭证。
 *
 * 只有三样东西：access token、refresh token、昵称。没有区域、没有机器身份，
 * 比 Qoder 简单一个数量级。
 */
internal data class AutoClawCredential(
    val accessToken: String,
    val refreshToken: String = "",
    val userId: String = "",
    val userName: String = "",
    /** 登录来源区域：cn（国内站）/ intl（国际站）。决定 userapi 打哪个 host。 */
    val realm: String = AutoClawConstants.REALM_CN,
    /** 沙箱 id：推理必须走 relay 的 proxy 子路径，没有它无法对话。 */
    val sandboxId: String = "",
    /** 沙箱端点（上游给的 wss 形式，用时归一化）。 */
    val sandboxEndpoint: String = "",
    /** 沙箱到期时间（秒）；0 表示未知。 */
    val sandboxEnd: Long = 0L,
    /**
     * access token 到期时间（**秒**）；0 表示未知。
     *
     * ★ 必须落盘保存，不能每次重新解析 JWT（上游参考实现同样存 `expiresAt`）。
     *   没有它就无法判断「刷新后 token 没变，到底是服务端没轮换、还是
     *   refresh_token 已废、上游宽容地回了旧值」—— 那种情况不报错，
     *   账号会静默失效（2026-11-15 对齐上游 3.4.1 的修复）。
     */
    val expiresAt: Long = 0L,
    /**
     * 登录时用的 device_id。
     *
     * ★ 刷新接口要带**同一个** device_id：登录时上游会把它签进 JWT，
     *   刷新时若换成别的值（本地派生 / JWT 里另一个），上游会认为设备变了
     *   并回 `400000 用户未登录`。登录时就存下来，之后一律沿用。
     */
    val deviceId: String = "",
) {
    fun toSecret(): String = JsonObject().apply {
        addProperty("accessToken", accessToken)
        addProperty("refreshToken", refreshToken)
        addProperty("userId", userId)
        addProperty("userName", userName)
        addProperty("realm", realm)
        addProperty("sandboxId", sandboxId)
        addProperty("sandboxEndpoint", sandboxEndpoint)
        addProperty("sandboxEnd", sandboxEnd)
        addProperty("expiresAt", expiresAt)
        if (deviceId.isNotEmpty()) addProperty("deviceId", deviceId)
        // 兼容旧口径：参考实现同时写两套键，避免只认其中一套的读取方读空
        addProperty("tokenExpiresAt", expiresAt)
    }.toString()

    /** 是否已经过期（expiresAt 未知时返回 false，不误判）。 */
    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        expiresAt > 0L && nowSeconds >= expiresAt

    companion object {
        fun parse(raw: String): AutoClawCredential? {
            if (raw.isBlank()) return null
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
            val token = obj.str("accessToken")
            if (token.isEmpty()) return null
            return AutoClawCredential(
                accessToken = token,
                // ★ 统一剥掉 `Bearer ` 前缀：上游登录响应偶尔会带，存进去后再发给刷新接口
                //   会得到 `400000 用户未登录`（把 "Bearer eyJ…" 整体当 refresh_token 校验）。
                //   在解析处收口，存量账号也会自动修正。
                refreshToken = obj.str("refreshToken")
                    .let { AutoClawConstants.stripBearer(it) },
                userId = obj.str("userId"),
                userName = obj.str("userName"),
                // 登录时存的 device_id：刷新必须沿用同一个
                deviceId = obj.str("deviceId"),
                // 老凭证没有 realm：默认国内（登录只实现过国内站）
                realm = obj.str("realm").ifEmpty { AutoClawConstants.REALM_CN },
                sandboxId = obj.str("sandboxId"),
                sandboxEndpoint = obj.str("sandboxEndpoint"),
                sandboxEnd = obj.get("sandboxEnd")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                // 两套键都认，取较新的（与上游 3.4.1 一致）
                expiresAt = maxOf(
                    obj.get("expiresAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                    obj.get("tokenExpiresAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                ),
            )
        }

        private fun JsonObject.str(key: String): String =
            get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
    }
}
