package dev.aigw.core.provider.autoclaw

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.SmsLoginSupport
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * AutoClaw 国内站（`autoglm.zhipuai.cn`）的手机号 + 短信验证码登录。
 *
 * ## 为什么国内站要单独一套
 * 实测：
 * - `autoclaw.zhipuai.cn` 只是官网，**没有 API**（任何 userapi 路径都 404）
 * - `autoglm.zhipuai.cn` 才是国内 API 宿主，网页版登录是**手机号 + 短信码**
 * - `autoglm-api.autoglm.ai` 是国际站，OAuth 带阿里云验证码（WebView 过不去）
 *
 * 网页版请求头用 `X-Product: rumination`（不是 `autoclaw`），
 * 并且每个请求都必须带 `device_id` + `source_id: "web"`，缺了会被判「请求数据有问题」。
 */
internal class AutoClawSmsLogin(
    private val hooks: (String) -> Unit = {},
) : SmsLoginSupport {

    override fun sendSmsCode(phone: String, ccode: String): String {
        val body = JsonObject().apply {
            addProperty("device_id", deviceId())
            addProperty("source_id", SOURCE_ID_WEB)
            addProperty("phone", aesEncrypt(normalizePhone(phone, ccode)))
        }.toString()
        val (status, text) = post(PATH_SEND_CODE, body)
        if (status !in 200..299) throw IllegalStateException("发送验证码失败（HTTP $status）")

        val root = JsonParser.parseString(text).asJsonObject
        val code = root.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
        if (code != 0) {
            val msg = root.get("msg")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            throw IllegalStateException(msg.ifEmpty { "发送验证码失败（code=$code）" })
        }
        // 上游把 msgid 放在 data 里；个别版本直接给在顶层，两处都取
        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        // 实测响应：{"code":0,"data":{"result":true,"code":"<traceId>"}}——
        // 上游把 msgid 叫 `code`，而且嵌在 data 里。取不到就留空，登录时不必带。
        val msgid = data?.get("msgid")?.takeIf { it.isJsonPrimitive }?.asString
            ?: data?.get("code")?.takeIf { it.isJsonPrimitive }?.asString
            ?: root.get("msgid")?.takeIf { it.isJsonPrimitive }?.asString
            ?: ""
        hooks("AutoClaw 验证码已发送（msgid=${msgid.take(12)}）")
        return msgid
    }

    override fun loginBySmsCode(
        phone: String,
        code: String,
        msgid: String,
        ccode: String,
    ): ProviderAccount {
        val codeNum = code.trim().toIntOrNull()
        fun body(sourceId: String, product: String?): String = JsonObject().apply {
            addProperty("device_id", deviceId())
            addProperty("source_id", sourceId)
            addProperty("phone", aesEncrypt(normalizePhone(phone, ccode)))
            // 网页版发的是 Number(code)，不是字符串
            if (codeNum != null) addProperty("code", codeNum) else addProperty("code", code.trim())
            if (msgid.isNotEmpty()) addProperty("msgid", msgid)
            if (product != null) addProperty("product", product)
        }.toString()

        // ★ 优先走移动端登录：官方 App 用 /userapi/v1/app-login 并带 product=autoclaw-app。
        // 服务端按它判定客户端形态，「每周限时激励」这类 event_type=autoclaw_user_active
        // 的活动奖只在识别为 App 时发放（见积分流水 metadata）。
        // 失败再回退网页版，保证登录本身不因这个改动挂掉。
        var status = 0
        var text = ""
        var usedApp = false
        run {
            val (s, t) = post(
                PATH_APP_LOGIN,
                body(SOURCE_ID_APP, PRODUCT_APP),
                productHeader = PRODUCT_APP,
            )
            val ok = s in 200..299 &&
                runCatching { JsonParser.parseString(t).asJsonObject.get("code")?.asInt == 0 }
                    .getOrDefault(false)
            if (ok) {
                status = s
                text = t
                usedApp = true
                hooks("AutoClaw 移动端登录成功（app-login）")
            } else {
                // 把上游原始返回显示出来：只有看到 code/msg 才能判断是参数问题还是验证码已被消耗
                val errMsg = runCatching {
                    JsonParser.parseString(t).asJsonObject.let { o ->
                        "code=${o.get("code")} msg=${o.str("msg")}"
                    }
                }.getOrDefault(t.take(120))
                hooks("AutoClaw app-login 未通过 HTTP $s $errMsg，回退网页版登录")
            }
        }
        if (!usedApp) {
            val (s, t) = post(PATH_WEB_LOGIN, body(SOURCE_ID_WEB, null), productHeader = PRODUCT_WEB)
            status = s
            text = t
        }
        if (status !in 200..299) throw IllegalStateException("登录失败（HTTP $status）")

        val root = JsonParser.parseString(text).asJsonObject
        val code0 = root.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
        if (code0 != 0) {
            val msg = root.get("msg")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            throw IllegalStateException(msg.ifEmpty { "登录失败（code=$code0）" })
        }

        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalStateException("登录响应缺少 data")
        val token = data.str("access_token").ifEmpty { data.str("accessToken") }
        if (token.isEmpty()) throw IllegalStateException("登录响应里没有 access_token")

        val credential = AutoClawCredential(
            accessToken = token,
            refreshToken = data.str("refresh_token").ifEmpty { data.str("refreshToken") },
            userId = data.str("user_id").ifEmpty { data.str("userId") },
            userName = data.str("user_name").ifEmpty { data.str("userName") }
                .ifEmpty { phone.takeLast(4) },
            // 手机号登录走的是国内站，token 只能在国内加速域名推理
            realm = AutoClawConstants.REALM_CN,
        )
        val uid = credential.userId.ifEmpty {
            val digest = MessageDigest.getInstance("MD5")
                .digest(credential.accessToken.toByteArray())
            digest.joinToString("") { "%02x".format(it) }.take(16)
        }
        hooks("AutoClaw 登录成功 uid=$uid")

        // 登录后立刻准备沙箱：推理走 {relay}/proxy/{sandboxId}，没有沙箱无法对话。
        // 失败不阻断登录（首次申请可能较慢），对话时会再 ensure 一次。
        val box = runCatching { AutoClawSandbox { hooks(it) }.ensure(credential) }.getOrNull()
        val finalCred = if (box != null) {
            hooks("AutoClaw 沙箱就绪 ${box.id}")
            credential.copy(
                sandboxId = box.id,
                sandboxEndpoint = box.endpoint,
                sandboxEnd = box.endTimestamp,
            )
        } else {
            hooks("AutoClaw 沙箱暂未就绪，对话时会重试")
            credential
        }

        return ProviderAccount(
            providerId = AutoClawConstants.ID,
            uid = uid,
            nickname = credential.userName,
            secret = finalCred.toSecret(),
        )
    }

    // ------------------------------------------------------------------ 内部

    /** 上游要纯号码（带国家码时不带 +）。 */
    private fun normalizePhone(phone: String, ccode: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (ccode == "86" || digits.startsWith(ccode)) digits else ccode + digits
    }

    private fun post(path: String, body: String, productHeader: String = PRODUCT_WEB): Pair<Int, String> {
        return try {
        val ts = System.currentTimeMillis() / 1000
        val conn = (URL(API_HOST + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 25_000
            // 上游对带尾斜杠的路径会 307；HttpURLConnection 默认不跟随重定向，
            // 表现就是「登录失败（HTTP 307）」。开了也保险。
            instanceFollowRedirects = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "*" + "/" + "*")
            setRequestProperty("X-Product", productHeader)
            setRequestProperty("X-Version", AutoClawConstants.CLIENT_VERSION)
            setRequestProperty("X-Tm", "linux")
            setRequestProperty("X-Auth-Appid", AutoClawConstants.APP_ID)
            setRequestProperty("X-Auth-TimeStamp", ts.toString())
            setRequestProperty("X-Auth-Sign", AutoClawConstants.sign(ts))
            setRequestProperty("X-Lang", "zh")
            setRequestProperty("X-Client-Type", "pc")
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
        hooks("AutoClaw 短信请求异常：${e.javaClass.simpleName} ${e.message}")
            0 to ""
        }
    }

    private fun deviceId(): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("autoclaw-web-device".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()

    companion object {
        /**
         * 手机号加密（从网页版 JS 逆出：`aesEncrypt = CryptoJS.AES.encrypt(phone, key, {iv})`）。
         *
         * AES-256-CBC + Pkcs7，输出 Base64。**不加密直接传明文号码，上游回
         * `code=630005 无效的手机号码`** —— 这就是之前一直发不出验证码的原因。
         */
        private const val AES_KEY = "df658c3b2fc5b818961441c07f7ac4ce"
        private const val AES_IV = "4bd91d734a724ee9"

        private fun aesEncrypt(plain: String): String {
            val keySpec = javax.crypto.spec.SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = javax.crypto.spec.IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return java.util.Base64.getEncoder().encodeToString(encrypted)
        }

        /** 国内 API 宿主（官网 autoclaw.zhipuai.cn 没有 API）。 */
        const val API_HOST = "https://autoglm.zhipuai.cn"

        /** 网页版产品标识，与客户端的 `autoclaw` 不同。 */
        const val PRODUCT_WEB = "rumination"

        /**
         * 移动端产品标识（反编译 com.zai.autoclaw v1.5.0 得到）。
         *
         * 官方 App 登录走 `/userapi/v1/app-login`，请求体多一个 `product` 字段
         * （VerifyCodeLoginReq: phone/code 必填，product/device_id/source_id 可选）。
         * 服务端按它判定客户端形态——这直接决定「每周限时激励」这类
         * `event_type=autoclaw_user_active` 的活动奖是否发放。
         */
        const val PRODUCT_APP = "autoclaw-app"

        const val SOURCE_ID_WEB = "web"
        const val SOURCE_ID_APP = "app"

        const val PATH_SEND_CODE = "/userapi/v1/send-code"
        const val PATH_WEB_LOGIN = "/userapi/v1/web-login"
        const val PATH_APP_LOGIN = "/userapi/v1/app-login"
    }
}
