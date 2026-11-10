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
    }.toString()

    companion object {
        fun parse(raw: String): AutoClawCredential? {
            if (raw.isBlank()) return null
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
            val token = obj.str("accessToken")
            if (token.isEmpty()) return null
            return AutoClawCredential(
                accessToken = token,
                refreshToken = obj.str("refreshToken"),
                userId = obj.str("userId"),
                userName = obj.str("userName"),
                // 老凭证没有 realm：默认国内（登录只实现过国内站）
                realm = obj.str("realm").ifEmpty { AutoClawConstants.REALM_CN },
                sandboxId = obj.str("sandboxId"),
                sandboxEndpoint = obj.str("sandboxEndpoint"),
                sandboxEnd = obj.get("sandboxEnd")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            )
        }

        private fun JsonObject.str(key: String): String =
            get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
    }
}
