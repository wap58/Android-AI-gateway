package dev.aigw.core.provider.qoder

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Qoder 的请求体编码与 COSY 签名（逆向自官方客户端，与社区实现逐字节对齐）。
 *
 * ## 为什么不能简单用标准 HTTP
 *
 * 推理端点不接受裸 JSON：body 必须是「自定义 Base64 变体」，
 * 且必须携带一组 `cosy-*` 头，其中 `authorization` 是链式 MD5 签名。
 * 少一处或顺序错一处，上游一律 401/403。
 *
 * ## 三块内容
 *
 * 1. **自定义 Base64**：先标准编码，再把结果做「尾/中/首」三段轮转，
 *    最后按私有字母表映射，`=` 换成 `$`。
 * 2. **RSA PKCS#1 v1.5**：用官方客户端硬编码的公钥包裹 16 字节会话 key。
 * 3. **AES-128-CBC**：key 与 iv 相同（都是那 16 字节），PKCS#7 填充。
 *
 * 这些都是标准密码学原语，JVM 自带实现即可，不需要额外依赖；
 * 只有「三段轮转 + 私有字母表」是 Qoder 特有，见 [encodeBody]。
 */
internal object QoderCodec {

    /** Qoder 私有 Base64 字母表（与标准表逐位对应）。 */
    private const val CUSTOM_ALPHABET =
        "_doRTgHZBKcGVjlvpC,@aFSx#DPuNJme&i*MzLOEn)sUrthbf%Y^w.(kIQyXqWA!"

    private const val STD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private const val PAD = '$'

    /** 官方客户端硬编码的 RSA 公钥（1024 位，SPKI PEM）。 */
    private const val SERVER_PUB_PEM =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDA8iMH5c02LilrsERw9t6Pv5Nc" +
            "4k6Pz1EaDicBMpdpxKduSZu5OANqUq8er4GM95omAGIOPOh+Nx0spthYA2BqGz+l" +
            "6HRkPJ7S236FZz73In/KVuLnwI8JJ2CbuJap8kvheCCZpmAWpb/cPx/3Vr/J6I17" +
            "XcW+ML9FoCI6AOvOzwIDAQAB"

    private val stdToCustom = IntArray(256) { it }.also { map ->
        for (i in STD_ALPHABET.indices) map[STD_ALPHABET[i].code] = CUSTOM_ALPHABET[i].code
        map['='.code] = PAD.code
    }

    /**
     * 把明文 JSON 编码成 Qoder 请求体格式。
     *
     * 顺序不能改：先标准 Base64，再按 [n/3] 切成三段做「尾 + 中 + 首」重排，
     * 最后映射字母表。服务端是逐字节反解的，任何一步顺序变化都会解码失败。
     */
    fun encodeBody(plain: ByteArray): String {
        val std = Base64.getEncoder().encodeToString(plain)
        val n = std.length
        val a = n / 3
        val rearranged = std.substring(n - a) + std.substring(a, n - a) + std.substring(0, a)
        val sb = StringBuilder(rearranged.length)
        for (ch in rearranged) {
            val idx = ch.code
            sb.append(if (idx in 0..255) stdToCustom[idx].toChar() else ch)
        }
        return sb.toString()
    }

    /**
     * RSA PKCS#1 v1.5（type 2）加密。
     *
     * 直接用 JVM 的 `RSA/ECB/PKCS1Padding`：它与协议要求的
     * EME-PKCS1-v1_5 编码逐字节一致，不需要手写模幂。
     */
    fun rsaEncrypt(plain: ByteArray): ByteArray {
        val der = Base64.getDecoder().decode(SERVER_PUB_PEM)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(plain)
    }

    /** AES-128-CBC，key 与 iv 是同一串 16 字节（协议如此，不是笔误）。 */
    fun aesCbcEncrypt(plain: ByteArray, key16: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val spec = SecretKeySpec(key16, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, spec, IvParameterSpec(key16))
        return cipher.doFinal(plain)
    }

    /**
     * 键排序 + 无空白的紧凑 JSON。
     *
     * 签名与加密都直接吃这些字节，所以**必须**与协议完全一致：
     * 键按字典序、分隔符不带空格、值按 JSON 规则转义。
     */
    fun jsonSortedCompact(pairs: Map<String, String>): ByteArray {
        val sb = StringBuilder("{")
        var first = true
        for (k in pairs.keys.sorted()) {
            if (!first) sb.append(',')
            first = false
            sb.append(quote(k)).append(':').append(quote(pairs[k].orEmpty()))
        }
        sb.append('}')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** 最小 JSON 字符串转义（协议只用到字符串与空值，够用且可控）。 */
    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun md5Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
