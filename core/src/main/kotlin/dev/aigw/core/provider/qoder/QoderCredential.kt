package dev.aigw.core.provider.qoder

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ProviderAccount

/** Qoder 账号凭证（序列化进 ProviderAccount.secret）。 */
internal data class QoderCredential(
    val accessToken: String,
    val refreshToken: String = "",
    val uid: String = "",
    val nickname: String = "",
    val realm: String = QoderRealm.CN,
    val userType: String = QoderCosySession.DEFAULT_USER_TYPE,
    val orgId: String = "",
    val orgName: String = "",
    val expiresAt: Long = 0L,
) {
    fun toSecret(): String = JsonObject().apply {
        addProperty("accessToken", accessToken)
        addProperty("refreshToken", refreshToken)
        addProperty("uid", uid)
        addProperty("nickname", nickname)
        addProperty("realm", realm)
        addProperty("userType", userType)
        addProperty("orgId", orgId)
        addProperty("orgName", orgName)
        addProperty("expiresAt", expiresAt)
    }.toString()

    companion object {
        fun parse(raw: String): QoderCredential? {
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return null
            val token = obj.str("accessToken")
            if (token.isEmpty()) return null
            return QoderCredential(
                accessToken = token,
                refreshToken = obj.str("refreshToken"),
                uid = obj.str("uid"),
                nickname = obj.str("nickname"),
                // 不做默认区域兜底：老版本凭证没有 realm 字段，盲目默认成 cn
                // 会把国际版账号误归到国内版列表。留空串，交给 regionOf 判未知。
                realm = QoderRealm.of(obj.str("realm")),
                userType = obj.str("userType").ifEmpty { QoderCosySession.DEFAULT_USER_TYPE },
                orgId = obj.str("orgId"),
                orgName = obj.str("orgName"),
                expiresAt = obj.get("expiresAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
            )
        }
    }
}

/**
 * 按账号缓存 COSY 会话。
 *
 * 会话里含一次性的临时密钥与派生设备指纹，每请求重建会白白多算一次 RSA；
 * 但 access token 轮换后必须重建（info 里编码了 token），所以按 token 判等失效。
 */
internal class QoderSessionCache {
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Pair<String, QoderCosySession>>()

    fun get(credential: QoderCredential): QoderCosySession {
        val key = credential.uid.ifEmpty { credential.accessToken.take(16) }
        val hit = entries[key]
        if (hit != null && hit.first == credential.accessToken) return hit.second
        val session = QoderCosySession(
            uid = credential.uid,
            nickname = credential.nickname,
            accessToken = credential.accessToken,
            refreshToken = credential.refreshToken,
            userType = credential.userType,
            orgId = credential.orgId,
            orgName = credential.orgName,
        )
        entries[key] = credential.accessToken to session
        return session
    }

    fun invalidate(uid: String) {
        entries.remove(uid)
    }
}

/** Gson 取值助手：上游字段常缺失或为 null，统一走这里避免 NPE。 */
internal fun JsonObject.str(key: String): String =
    get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

internal fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.arr(key: String): List<com.google.gson.JsonElement>? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList()

/** 数值取值：上游常见 "12" 字符串与 12 数字两种形态。 */
internal fun JsonObject.num(key: String): Double? {
    val el = get(key)?.takeIf { !it.isJsonNull } ?: return null
    return runCatching { el.asDouble }.getOrNull()
}

/**
 * 过期时间解析：上游可能给数字秒、数字毫秒或 ISO-8601 字符串
 * （实测国际版返回 `2026-12-07T18:40:10Z`）。
 *
 * 之前直接 `asLong` 会在字符串形态上抛
 * `NumberFormatException: For input string: "2026-12-07T18:40:10Z"`，
 * 而这一步发生在轮询成功的收尾阶段 —— 表现就是「登录成功但账号没进来」。
 * 解析失败一律返回 0（当作未知），绝不让它把登录流程炸掉。
 */
internal fun JsonObject.epochSeconds(key: String): Long {
    val el = get(key)?.takeIf { !it.isJsonNull } ?: return 0L
    // 数字：秒或毫秒都见得到，大于 1e12 视为毫秒
    runCatching { el.asLong }.getOrNull()?.let { n ->
        return if (n > 1_000_000_000_000L) n / 1000 else n
    }
    val text = runCatching { el.asString }.getOrNull()?.trim().orEmpty()
    if (text.isEmpty()) return 0L
    // 纯数字字符串
    text.toLongOrNull()?.let { n ->
        return if (n > 1_000_000_000_000L) n / 1000 else n
    }
    // ISO-8601，例如 2026-12-07T18:40:10Z
    return runCatching {
        java.time.Instant.parse(text).epochSecond
    }.recoverCatching {
        // 带偏移量（+08:00）或毫秒的变体
        java.time.OffsetDateTime.parse(text).toInstant().epochSecond
    }.recoverCatching {
        // 无时区信息时按本地时间理解
        java.time.LocalDateTime.parse(text)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().epochSecond
    }.getOrDefault(0L)
}
