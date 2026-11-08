package dev.aigw.core.provider.qoder

import java.util.Base64
import java.util.UUID

/**
 * 单个账号的 COSY 签名会话（对齐官方 qodercli 1.1.75）。
 *
 * ## 一次请求要凑齐哪些东西
 *
 * ```
 * tempKey = 16 字节随机会话密钥
 * cosyKey = base64(RSA_PKCS1v15(tempKey))
 * info    = base64(AES128CBC(identity, key = iv = tempKey))
 * payload = base64(sortedCompact{cosyVersion,ideVersion,info,requestId,version})
 * sig     = md5(payload \n cosyKey \n date \n body \n path)
 * Bearer  = "COSY." + payload + "." + sig
 * ```
 *
 * 注意 `tempKey` 是 **16 个十六进制字符的 ASCII 字节**（不是 16 字节随机数据）——
 * 服务端用私钥解出 cosyKey 后，拿同一串字节解 info，长度或编码变一位就解不开。
 * 这一点不能"顺手加固"，属于协议硬约束。
 *
 * ## 设备指纹为什么按 uid 派生
 *
 * 官方风控真正敏感的是「同一台设备上关联多个账号」。按 uid 稳定派生
 * （同一账号永远同一台"设备"、不同账号不同设备）比每次随机更安全：
 * 随机机器码会让每个请求看起来都来自新设备，反而更容易被判异常。
 */
internal class QoderCosySession(
    val uid: String,
    nickname: String = "",
    accessToken: String,
    refreshToken: String = "",
    userType: String = DEFAULT_USER_TYPE,
    orgId: String = "",
    orgName: String = "",
) {
    /** 稳定设备指纹（形如 UUIDv4，与官方客户端同形）。 */
    val machineId: String = deriveId(uid, "machine")
    val machineToken: String = deriveMachineToken(uid)
    val machineType: String = deriveMachineType(uid)
    val machineHostname: String = deriveHostname(uid)

    private val tempKey: ByteArray = randomHexAscii(16).toByteArray(Charsets.UTF_8)
    private val cosyKey: String = Base64.getEncoder()
        .encodeToString(QoderCodec.rsaEncrypt(tempKey))

    private val info: String = run {
        val identity = mapOf(
            "name" to nickname,
            "aid" to uid,
            "uid" to uid,
            "yx_uid" to "",
            "organization_id" to orgId,
            "organization_name" to orgName,
            "user_type" to userType,
            "security_oauth_token" to accessToken,
            "refresh_token" to refreshToken,
        )
        val plain = QoderCodec.jsonSortedCompact(identity)
        Base64.getEncoder().encodeToString(QoderCodec.aesCbcEncrypt(plain, tempKey))
    }

    /**
     * 构造一次请求的完整签名头。
     *
     * @param body 已经过 [QoderCodec.encodeBody] 编码的请求体（签名覆盖它，不能用明文）
     * @param rawUrl 含 query 的完整 URL；签名只覆盖 path，且要去掉 `/algo` 前缀
     * @param modelKey 上游模型键，决定路由
     */
    fun headers(
        body: String,
        rawUrl: String,
        modelKey: String = "",
        sse: Boolean = true,
        realm: String = REALM_CN,
    ): Map<String, String> {
        // 签名只覆盖 path（不含 query），且要去掉 /algo 前缀。
        // 不用 java.net.URI 解析：URL 里可能带未转义字符，字符串切分更稳。
        val afterHost = rawUrl.substringAfter("://", "").substringAfter('/', "")
        val rawPath = "/" + afterHost.substringBefore('?')
        val path = if (rawPath.startsWith("/algo")) rawPath.removePrefix("/algo") else rawPath

        // ★ payload 里的 cosyVersion 与请求头的 cosy-version 是**两个不同值**：
        //   payload 用 1.0.10，请求头用 1.1.69（Go 参考实现 cosy.Version / cosy.ProtocolVersion）。
        //   两处填同一个值会被上游判参数非法（4001 param is invalid）。
        val payload = mapOf(
            "cosyVersion" to COSY_PROTOCOL_VERSION,
            "ideVersion" to "",
            "info" to info,
            "requestId" to UUID.randomUUID().toString(),
            "version" to "v1",
        )
        val payloadB64 = Base64.getEncoder()
            .encodeToString(QoderCodec.jsonSortedCompact(payload))
        val date = (System.currentTimeMillis() / 1000).toString()
        val signBase = listOf(payloadB64, cosyKey, date, body, path).joinToString("\n")
        val sig = QoderCodec.md5Hex(signBase)
        val bearer = "Bearer COSY.$payloadB64.$sig"

        val h = mutableMapOf(
            "cosy-data-policy" to "agree",
            "content-type" to "application/json",
            "cosy-business-product" to "cli",
            "cosy-business-type" to "agent",
            "cosy-scene" to "assistant",
            "cosy-clienttype" to COSY_CLIENT_TYPE,
            "cosy-date" to date,
            "cosy-user" to uid,
            "cosy-key" to cosyKey,
            "accept" to if (sse) "text/event-stream" else "application/json",
            "authorization" to bearer,
            "accept-encoding" to "identity",
            "cosy-version" to COSY_PROTOCOL_VERSION,
            "cosy-machineid" to machineId,
            "cosy-machinetoken" to machineToken,
            "cosy-machinetype" to machineType,
            "cosy-clientip" to "169.254.198.161",
            "login-version" to "v2",
            "user-agent" to COSY_USER_AGENT,
            // 官方请求层对所有请求注入机器 OS；ARM 设备也报 x86_64_linux，
            // 因为存量账号的指纹是这么建立起来的，改成真机架构反而像换了设备。
            "cosy-machineos" to COSY_MACHINE_OS,
        )
        if (sse) {
            h["cosy-machinehostname"] = machineHostname
            h["cache-control"] = "no-cache"
        }
        if (modelKey.isNotEmpty()) {
            h["x-model-key"] = modelKey
            h["x-model-source"] = "system"
        }
        return h
    }

    companion object {
        /**
         * cosy 版本：payload 里的 cosyVersion 与请求头 cosy-version **共用此值**。
         *
         * 之前按 Go 参考实现拆成 1.0.10 / 1.1.69 双值是错的——
         * 成品 APK（com.qoder.gateway.QoderConstants.COSY_VERSION）取 1.1.75 单值。
         */
        const val COSY_PROTOCOL_VERSION = "1.1.75"

        /** 桌面端 10；CLI 是 5。上游按它决定下发哪套模型清单。 */
        const val COSY_CLIENT_TYPE = "5"
        const val COSY_USER_AGENT = "undici"
        const val COSY_MACHINE_OS = "x86_64_linux"
        const val DEFAULT_USER_TYPE = "personal_professional_trial"
        const val REALM_CN = "cn"
        const val REALM_INTL = "intl"

        private fun randomHexAscii(len: Int): String {
            val chars = "0123456789abcdef"
            val sb = StringBuilder(len)
            repeat(len) { sb.append(chars[(Math.random() * 16).toInt()]) }
            return sb.toString()
        }

        /** 由 uid + salt 稳定派生带连字符的 UUID 形状标识，并伪造版本/变体位。 */
        fun deriveId(uid: String, salt: String): String {
            val seed = "$salt:${uid.ifEmpty { "anonymous" }}"
            var h = QoderCodec.md5Hex(seed)
            h = h.substring(0, 12) + "4" + h.substring(13, 16) + "a" + h.substring(17)
            return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-" +
                "${h.substring(16, 20)}-${h.substring(20, 32)}"
        }

        fun deriveMachineType(uid: String): String =
            deriveId(uid, "machinetype").replace("-", "").take(18)

        fun deriveMachineToken(uid: String): String {
            val raw = java.security.MessageDigest.getInstance("SHA-512")
                .digest("machinetoken:$uid".toByteArray())
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw).take(43)
        }

        fun deriveHostname(uid: String): String {
            val h = QoderCodec.md5Hex("hostname:${uid.ifEmpty { "anonymous" }}")
            return "DESKTOP-" + h.take(7).uppercase()
        }
    }
}
