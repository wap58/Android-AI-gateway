package dev.aigw.core.provider.autoclaw

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * AutoClaw 沙箱网关。
 *
 * ## 为什么不能直接打 `/autoclaw-proxy/proxy/autoclaw/v1/chat/completions`
 * 那个路径是**客户端**用的，第三方拿 access_token 打会 406。真正的推理入口是
 * **沙箱网关**：
 * ```
 * 1. GET  /agentdr/v2/assistant/sandbox/list   → sandbox_list[]（含 sandbox_endpoint）
 * 2. POST /agentdr/v2/assistant/sandbox/apply  {sandbox_name:"default"}  （列表为空时）
 * 3. POST {relay_base}/v1/sandboxes/{id}/wake  唤醒沙箱
 * 4. POST {relay_base}/proxy/{id}/v1/chat/completions   ← 真正对话
 * ```
 * 其中 `relay_base` 由 `sandbox_endpoint` 归一化而来（wss→https、去掉 query、
 * 路径截断到 `/autoclaw-cloud`）。
 *
 * 协议来自开源项目 `ZFXing-lite/autoclaw2api`（Go），已交叉验证。
 */
internal class AutoClawSandbox(
    private val hooks: (String) -> Unit = {},
) {

    /** 一个沙箱实例。 */
    internal data class Box(
        val id: String,
        val endpoint: String,
        val endTimestamp: Long,
    )

    // ------------------------------------------------------------------ 端点归一化

    /**
     * 把上游给的 `sandbox_endpoint` 归一化。
     *
     * 输入形如 `wss://autoglm-api.zhipuai.cn/autoclaw-cloud/ws?sandbox_id=..&port=..`，
     * 必须：ws→http、wss→https、**清掉 query/fragment**、路径截断到 `/autoclaw-cloud`。
     * 不去 query 的话 http 请求会打到 `/autoclaw-cloud/ws?...` 这种错误路径。
     */
    fun relayBase(endpoint: String): String {
        var t = endpoint.trim()
        if (t.isEmpty()) return ""
        val lower = t.lowercase()
        t = when {
            lower.startsWith("ws://") -> "http://" + t.substring(5)
            lower.startsWith("wss://") -> "https://" + t.substring(6)
            else -> t
        }
        val suffix = "/autoclaw-cloud"
        val schemeIdx = t.indexOf("://")
        if (schemeIdx < 0) {
            val trimmed = t.trimEnd('/')
            return if (trimmed.endsWith(suffix)) trimmed else trimmed + suffix
        }
        // 手动切 query/fragment，避免 URL 解析异常时整串丢掉
        val afterScheme = t.substring(schemeIdx + 3)
        val hostEnd = afterScheme.indexOf('/')
        val host = if (hostEnd < 0) afterScheme else afterScheme.substring(0, hostEnd)
        var path = if (hostEnd < 0) "/" else afterScheme.substring(hostEnd)
        path = path.substringBefore('?').substringBefore('#')
        val cut = path.indexOf(suffix)
        path = if (cut >= 0) path.substring(0, cut + suffix.length) else path.trimEnd('/') + suffix
        return "https://$host$path".replace("https://", if (lower.startsWith("ws://")) "http://" else "https://")
    }

    /** 带 `/proxy/{id}` 的完整 relay 基址。 */
    fun relayProxyBase(endpoint: String, sandboxId: String): String {
        val base = relayBase(endpoint).trimEnd('/')
        if (sandboxId.isEmpty()) return base
        return "$base/proxy/$sandboxId"
    }

    // ------------------------------------------------------------------ 沙箱生命周期

    /** 列出账号的沙箱。 */
    fun list(credential: AutoClawCredential): List<Box> {
        val (status, text) = userApiGet(
            credential,
            "/agentdr/v2/assistant/sandbox/list",
        )
        if (status !in 200..299) {
            hooks("AutoClaw 沙箱列表 HTTP $status：${text.take(200)}")
            return emptyList()
        }
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return emptyList()
        val data = root.obj("data") ?: root
        val arr = data.get("sandbox_list")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: data.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val id = o.str("sandbox_id")
            if (id.isEmpty()) return@mapNotNull null
            Box(
                id = id,
                endpoint = o.str("sandbox_endpoint"),
                endTimestamp = o.num("end_timestamp")?.toLong() ?: 0L,
            )
        }
    }

    /** 申请默认沙箱（列表为空时用）。 */
    fun apply(credential: AutoClawCredential): Boolean {
        val body = JsonObject().apply { addProperty("sandbox_name", "default") }.toString()
        val (status, text) = userApiPost(credential, "/agentdr/v2/assistant/sandbox/apply", body)
        if (status !in 200..299) {
            hooks("AutoClaw 申请沙箱 HTTP $status：${text.take(200)}")
            return false
        }
        hooks("AutoClaw 已申请沙箱")
        return true
    }

    /** 唤醒沙箱：不唤醒时推理会 503/超时。 */
    fun wake(credential: AutoClawCredential, box: Box): Boolean {
        val base = relayBase(box.endpoint).trimEnd('/')
        if (base.isEmpty()) return false
        val body = JsonObject().apply {
            addProperty("access_hold_ms", 600_000)
            addProperty("reason", "tyd chat request")
            addProperty("apply_business_busy_cooldown", true)
        }.toString()
        val url = "$base/v1/sandboxes/${box.id}/wake"
        val (status, text) = relayRequest(url, credential.accessToken, body)
        if (status !in 200..299) {
            hooks("AutoClaw 唤醒沙箱 HTTP $status：${text.take(160)}")
            return false
        }
        return true
    }

    /**
     * 确保拿到一个可用沙箱：缓存 → list → apply → 重拉。
     *
     * 优先挑未过期的；全都过期就取第一个。
     */
    fun ensure(credential: AutoClawCredential): Box? {
        // 1) 凭证里已有 sandbox 且未过期
        if (credential.sandboxId.isNotEmpty() && credential.sandboxEndpoint.isNotEmpty()) {
            val expired = credential.sandboxEnd > 0 && credential.sandboxEnd < nowSeconds()
            if (!expired) {
                return Box(credential.sandboxId, credential.sandboxEndpoint, credential.sandboxEnd)
            }
        }
        // 2) 拉列表
        var list = list(credential)
        // 3) 空则申请后重拉
        if (list.isEmpty()) {
            if (!apply(credential)) return null
            list = list(credential)
        }
        if (list.isEmpty()) return null
        val pick = list.firstOrNull { it.endTimestamp == 0L || it.endTimestamp > nowSeconds() }
            ?: list.first()
        return pick
    }

    // ------------------------------------------------------------------ 推理

    /**
     * 发一次对话。
     *
     * 只带 `Accept` + `Authorization` 两个头——这是沙箱网关的契约，
     * 多带 X-Auth-* / X-Product 之类会被判成别的 API（实测 406）。
     */
    fun chat(
        credential: AutoClawCredential,
        box: Box,
        bodyJson: String,
        stream: Boolean,
        backendModel: String = "",
    ): Triple<Int, java.io.InputStream?, String> {
        val base = relayProxyBase(box.endpoint, box.id)
        if (base.isEmpty()) return Triple(0, null, "沙箱地址为空")
        val url = "$base/v1/chat/completions"

        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 600_000
                doOutput = true
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", bearer(credential.accessToken))
                // 真模型名走这个头；body.model 固定是 openclaw。
                // 缺了它会走网关默认模型（实测请求 glm 却返回 DeepSeek）。
                if (backendModel.isNotEmpty()) setRequestProperty("x-openclaw-model", backendModel)
                val bytes = bodyJson.toByteArray(Charsets.UTF_8)
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { it.write(bytes) }
            }
        } catch (e: Exception) {
            return Triple(0, null, "连接失败：${e.message}")
        }

        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            runCatching { conn.disconnect() }
            return Triple(0, null, "读取响应失败：${e.message}")
        }

        if (status !in 200..299) {
            val err = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull().orEmpty()
            runCatching { conn.disconnect() }
            hooks("AutoClaw 沙箱对话 HTTP $status url=$url：${err.take(300)}")
            return Triple(status, null, err)
        }

        val input = runCatching { conn.inputStream }.getOrNull()
            ?: return Triple(status, null, "上游未返回内容")

        // ★ 上游 token 过期时仍回 HTTP 200，body 却是 {"code":410000,...} 这种错误 JSON。
        //   不识别它就会把错误当流数据转发，客户端只看到「上游流式响应没有任何数据」。
        //   这里先窥探开头一小段：以 {"code": 但不是 code:0 开头 → 当失败返回。
        val probe = java.io.ByteArrayOutputStream()
        val head = input.buffered()
        runCatching {
            var read = 0
            while (read < 256) {
                val b = head.read()
                if (b < 0) break
                probe.write(b)
                read++
                // 收到 {} 闭合就够判断了
                if (b == '}'.code) break
            }
        }
        val headText = probe.toString(Charsets.UTF_8.name())
        if (headText.trimStart().startsWith("{\"code\"") || headText.contains("\"code\":410000")) {
            val code = Regex(""""code"\s*:\s*(\d+)""").find(headText)?.groupValues?.get(1).orEmpty()
            if (code.isNotEmpty() && code != "0") {
                runCatching { conn.disconnect() }
                hooks("AutoClaw 沙箱对话 HTTP $status 但返回错误 JSON：${headText.take(200)}")
                return Triple(if (code == "410000") 401 else 500, null, headText)
            }
        }
        // 把窥探到的字节拼回流的开头
        val merged = java.io.SequenceInputStream(java.io.ByteArrayInputStream(probe.toByteArray()), head)
        return Triple(status, merged, "")
    }

    // ------------------------------------------------------------------ 积分

    /** 查积分：`/agent-assetmgr/api/v2/wallets?biz_app_id=autoclaw` → `total_balance`。 */
    fun points(credential: AutoClawCredential): Double? {
        val (status, text) = userApiGet(
            credential,
            "/agent-assetmgr/api/v2/wallets?biz_app_id=autoclaw",
        )
        if (status !in 200..299) {
            hooks("AutoClaw 积分 HTTP $status：${text.take(200)}")
            return null
        }
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val data = root.obj("data") ?: root
        // total_balance 可能是数字也可能是字符串
        val el = data.get("total_balance") ?: data.get("totalBalance") ?: return run {
            hooks("AutoClaw 积分字段缺失：${data.keySet().joinToString(",")}")
            null
        }
        return runCatching { el.asDouble }.getOrNull()
            ?: el.toString().trim('"').toDoubleOrNull()
    }

    // ------------------------------------------------------------------ HTTP

    private fun userApiGet(credential: AutoClawCredential, path: String): Pair<Int, String> =
        userApiRequest(credential, path, null, "GET")

    private fun userApiPost(credential: AutoClawCredential, path: String, body: String): Pair<Int, String> =
        userApiRequest(credential, path, body, "POST")

    /**
     * userapi 请求（带签名头 + Authorization）。
     *
     * 注意 region：国内账号必须打国内 host，用国际 host 会 401。
     */
    private fun userApiRequest(
        credential: AutoClawCredential,
        path: String,
        body: String?,
        method: String,
    ): Pair<Int, String> {
        val host = if (credential.realm == AutoClawConstants.REALM_INTL) {
            AutoClawConstants.USER_API
        } else {
            AutoClawConstants.CN_USER_API
        }
        val ts = nowSeconds()
        val url = host + path
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("X-Version", AutoClawConstants.CLIENT_VERSION)
                setRequestProperty("X-Tm", "web")
                setRequestProperty("X-Product", "autoclaw")
                setRequestProperty("X-Client-Type", "web")
                setRequestProperty("X-Channel", "official")
                setRequestProperty("X-Auth-Appid", AutoClawConstants.APP_ID)
                setRequestProperty("X-Auth-TimeStamp", ts.toString())
                setRequestProperty("X-Auth-Sign", AutoClawConstants.sign(ts))
                setRequestProperty("X-Trace-Id", java.util.UUID.randomUUID().toString())
                setRequestProperty("X-Lang", "zh-CN")
                setRequestProperty("Authorization", bearer(credential.accessToken))
                if (body != null) {
                    doOutput = true
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    setFixedLengthStreamingMode(bytes.size)
                    outputStream.use { it.write(bytes) }
                }
            }
            val status = conn.responseCode
            val text = runCatching {
                (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            }.getOrDefault("")
            runCatching { conn.disconnect() }
            status to text
        } catch (e: Exception) {
            hooks("AutoClaw userapi 异常 ${e.javaClass.simpleName}：${e.message}")
            0 to ""
        }
    }

    /** 裸 relay 请求（唤醒沙箱用），同样只带两个头。 */
    private fun relayRequest(url: String, token: String, body: String): Pair<Int, String> = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", bearer(token))
            val bytes = body.toByteArray(Charsets.UTF_8)
            setFixedLengthStreamingMode(bytes.size)
            outputStream.use { it.write(bytes) }
        }
        val status = conn.responseCode
        val text = runCatching {
            (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        }.getOrDefault("")
        runCatching { conn.disconnect() }
        status to text
    } catch (e: Exception) {
        0 to ""
    }

    private fun bearer(token: String): String =
        if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.num(key: String): Double? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let {
            runCatching { it.asDouble }.getOrNull()
        }
}
