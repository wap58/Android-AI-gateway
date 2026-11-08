package dev.aigw.core.provider.qoder

import java.security.MessageDigest

/**
 * 真实机器身份池（UMID）。
 *
 * ## 为什么需要它
 *
 * Qoder 的服务端按「机器身份」分桶下发活动：携带**真实** UMID 才返回
 * `actionType=CLAIM_BENEFIT` 的「每日领取 100 Credits」，假身份只给
 * `VIEW_DETAILS`，于是表现为「当前没有可领取的活动」。
 *
 * 真实 UMID 由官方桌面客户端的 `runtime-info` 二进制生成。该二进制链接
 * **glibc**（`/lib/ld-linux-aarch64.so.1`），Android 是 bionic，直接跑必然
 * 失败（`open:: Permission denied` / 找不到解释器）。
 *
 * ## 方案
 *
 * 在能跑 glibc 的环境里（沙箱 aarch64 Linux）预生成一批真实身份，作为
 * 资源打包进 APK，运行时按 uid 哈希**固定分配**——同一账号每次拿到同一个
 * 身份，不会来回跳导致服务端判风控。
 *
 * 生成命令：`runtime-info <env>`，env 国内 0、国际桌面端 3。
 */
internal object QoderUmidPool {

    /** 官方 `runtime-info` 的原始输出（只有这三个字段）。 */
    private data class Raw(val machineToken: String, val machineType: String, val machineCode: String)

    /** 拼装后交给请求头的完整身份。 */
    internal data class Identity(
        val machineId: String,
        val machineToken: String,
        val machineType: String,
        val machineCode: String,
        val machineOs: String,
        val hostname: String,
    )

    private val pool: Map<String, List<Raw>> by lazy { load() }

    /**
     * 取该账号所属区域的固定身份。
     *
     * 池为空（资源缺失）时返回 null，调用方回退到「不发机器头」——那是已知
     * 可接受的行为（活动列表可能为空，但不会被判成伪造客户端）。
     */
    fun forUid(realm: String, uid: String): Identity? {
        val list = pool[normalizeRealm(realm)]?.takeIf { it.isNotEmpty() } ?: return null
        val h = md5(uid).take(8).toLongOrNull(16) ?: return null
        val raw = list[(h % list.size).toInt()]
        return Identity(
            // machineId 官方组件不下发，按 uid 稳定派生。
            // 形状必须是**带连字符的 UUID（版本位 4、变体位 a）**，
            // 真实客户端的 machineId 就是这个形态；32 位纯 hex 会被判非法。
            machineId = uuidFromUid(uid),
            machineToken = raw.machineToken,
            machineType = raw.machineType,
            machineCode = raw.machineCode,
            machineOs = "x86_64_linux",
            hostname = hostnameOf(uid),
        )
    }

    /** 稳定派生 UUID 形状的 machineId（版本位 4、变体位 a）。 */
    private fun uuidFromUid(uid: String): String {
        val h = md5("machine:$uid")
        return buildString(36) {
            append(h, 0, 8).append('-')
            append(h, 8, 12).append('-')
            append('4').append(h, 13, 16).append('-')
            append('a').append(h, 17, 20).append('-')
            append(h, 20, 32)
        }
    }

    /** 桌面式主机名：官方客户端是 `DESKTOP-XXXXXXX`，这里稳定派生一个同形值。 */
    private fun hostnameOf(uid: String): String {
        val h = md5("host:$uid").uppercase()
        return "DESKTOP-" + h.take(7)
    }

    private fun normalizeRealm(realm: String): String =
        if (realm.equals("intl", true) || realm.equals("global", true)) "intl" else "cn"

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun load(): Map<String, List<Raw>> {
        val text = runCatching {
            QoderUmidPool::class.java.getResourceAsStream("/qoder_umid_pool.json")
                ?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return emptyMap()
        val root = runCatching {
            com.google.gson.JsonParser.parseString(text).asJsonObject
        }.getOrNull() ?: return emptyMap()

        fun parse(key: String): List<Raw> = runCatching {
            (root.get(key) as? com.google.gson.JsonArray)?.mapNotNull { el ->
                val o = el.asJsonObject
                val token = o.str("machineToken")
                if (token.isEmpty()) return@mapNotNull null
                Raw(
                    machineToken = token,
                    machineType = o.str("machineType"),
                    machineCode = o.str("machineCode"),
                )
            }.orEmpty()
        }.getOrDefault(emptyList())

        return mapOf("intl" to parse("intl"), "cn" to parse("cn"))
    }
}
