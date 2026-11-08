package dev.aigw.core.provider.qoder

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.DeviceCodeSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.WebLoginTicket
import dev.aigw.core.provider.isStreamingBody
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.UUID

/**
 * Qoder 供应商（国内 qoder.com.cn / 国际 qoder.sh）。
 *
 * ## 接入方式
 *
 * 与 WorkBuddy 同构：**OAuth 设备授权 + PKCE 轮询**，没有本地回调。
 * 因此可以直接复用内置 WebView（打开授权页 → 用户确认 → App 轮询取 token）。
 *
 * ## 对话为什么难
 *
 * 推理端点不接受裸 JSON，三项硬要求缺一不可：
 * 1. body 要过 Qoder 自定义 Base64 变体（[QoderCodec.encodeBody]）
 * 2. 一串 `cosy-*` 头 + `authorization: Bearer COSY.<payload>.<sig>`（[QoderCosySession]）
 * 3. 响应是**双层信封**（外层 Qoder，内层 OpenAI chunk），要解包（[QoderSseUnwrapper]）
 *
 * 这三块都已按参考实现逐字节对齐，并有单测锁定。
 */
class QoderProvider(
    private val hooks: ProviderHooks = ProviderHooks(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : Provider, DeviceCodeSupport, dev.aigw.core.provider.WebLoginSupport,
    dev.aigw.core.provider.RegionAwareSupport {

    override val id: String = ID
    override val displayName: String = "Qoder"
    override val authKind: AuthKind = AuthKind.DEVICE_CODE
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.CREDIT_REFRESH,
        ProviderCapability.CHECKIN,
    )

    /** 每天 10:00 起算一个签到周期，与官方一致。 */
    override val checkinCycleStartHour: Int = 10

    private val sessions = QoderSessionCache()

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        if (account == null) {
            // 未登录不报错：这是正常状态，把内置目录拿出来让用户先看到有哪些模型
            return ProviderModelCatalogView(
                models = catalogToModels(QoderCatalog.of(currentRealm())),
                fromFallback = true,
                error = "",
            )
        }
        val realm = realmOf(account)
        val models = runCatching { fetchModels(account) }.getOrNull()
        return if (models.isNullOrEmpty()) {
            ProviderModelCatalogView(catalogToModels(QoderCatalog.of(realm)), fromFallback = true, error = "")
        } else {
            ProviderModelCatalogView(models, fromFallback = false, error = "")
        }
    }

    /**
     * 模型名归一化。
     *
     * 只剥 `qoder/` 这一个前缀（成品 QoderProvider.resolveModel 就是
     * `substringAfter(raw, "qoder/", raw)`）。
     * ★ 不要用 substringAfterLast('/')：那会把 `qoder-cn/qfmodel` 剥成
     *   `qfmodel`，而上游要的是带区域前缀的整串，签名里的 x-model-key 一变
     *   上游就判 4001 param is invalid。
     */
    override fun resolveModel(requested: String): String {
        val raw = requested.trim()
        if (raw.isEmpty()) return QoderCatalog.defaultKey(currentRealm())
        return raw.removePrefix("qoder/")
    }

    override fun isInternalModel(id: String): Boolean = id == "auto"

    private fun catalogToModels(catalog: List<QoderModel>): List<ProviderModel> =
        catalog.map { m ->
            ProviderModel(
                id = m.key,
                name = m.displayName,
                contextWindow = m.maxInput.toLong(),
                extra = mapOf(
                    "rate" to m.factor.toString(),
                    "multiplier" to m.factor.toString(),
                    "vl" to m.isVl.toString(),
                    "reasoning" to m.isReasoning.toString(),
                ),
            )
        }

    // ------------------------------------------------------------------ 登录（设备授权 + PKCE）

    private data class PendingLogin(
        val verifier: String,
        val nonce: String,
        val realm: String,
        val createdAt: Long,
    )

    private val pending = java.util.concurrent.ConcurrentHashMap<String, PendingLogin>()

    override fun startDeviceAuth(region: String): DeviceAuthTicket {
        val realm = QoderRealm.of(region.ifEmpty { currentRealm() })
        val verifier = pkceVerifier()
        val challenge = pkceChallenge(verifier)
        // 国内版 nonce 带横线（UUID），国际版是 32 位无横线 hex
        val nonce = if (QoderRealm.nonceDashed[realm] == true) {
            UUID.randomUUID().toString()
        } else {
            UUID.randomUUID().toString().replace("-", "")
        }

        val params = linkedMapOf(
            "challenge" to challenge,
            "challenge_method" to "S256",
            "nonce" to nonce,
        )
        if (QoderRealm.sendRedirectUri[realm] == true) {
            params["redirect_uri"] = QoderRealm.redirectUri.getValue(QoderRealm.safe(realm))
        }
        if (QoderRealm.sendClientId[realm] == true) {
            // client_id 必须是官方注册的 UUID：自造串会让授权页报「参数无效」
            params["client_id"] = QoderRealm.clientId.getValue(QoderRealm.safe(realm))
            // machine_id 用标准 UUID（带连字符）。参考实现优先读本机官方客户端的
            // machine_id 以保持设备一致，取不到才随机生成——这里没有本机客户端，
            // 随机一个标准 UUID 即可，形态必须与官方一致，否则授权页判「参数无效」。
            params["machine_id"] = UUID.randomUUID().toString()
        }
        val query = params.entries.joinToString("&") { (k, v) -> "$k=" + urlEnc(v) }
        val authUrl = QoderRealm.website.getValue(QoderRealm.safe(realm)) + "/device/selectAccounts?" + query

        val state = "qd-" + nowMillis()
        pending[state] = PendingLogin(verifier, nonce, realm, nowMillis())
        // 记下完整 URL：授权页报「参数无效」时，只有看到实际发出去的参数
        // 才能判断是缺字段、形态不对还是 client_id 不被认可。
        hooks.onLog("Qoder：设备授权 URL($realm) = $authUrl")
        return DeviceAuthTicket(loginUrl = authUrl, state = state)
    }

    override fun pollDeviceAuth(state: String, region: String): DeviceAuthPoll {
        val info = pending[state] ?: return DeviceAuthPoll.Failed("登录已过期，请重新发起")
        if (nowMillis() - info.createdAt > LOGIN_TTL_MS) {
            pending.remove(state)
            return DeviceAuthPoll.Failed("登录超时，请重新发起")
        }
        val base = QoderRealm.openapi.getValue(QoderRealm.safe(info.realm))
        val query = listOf(
            "nonce" to info.nonce,
            "verifier" to info.verifier,
            "challenge_method" to "S256",
        ).joinToString("&") { (k, v) -> "$k=" + urlEnc(v) }
        val (status, text) = httpGet("$base$PATH_DEVICE_POLL?$query", mapOf(
            "Accept" to "application/json",
            "User-Agent" to "QoderWork",
        ))
        // 404 / 202 = 用户还没完成授权，继续等
        if (status == 404 || status == 202) return DeviceAuthPoll.Pending
        if (status !in 200..299) return DeviceAuthPoll.Failed("轮询失败（HTTP $status）")

        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return DeviceAuthPoll.Pending
        val token = obj.str("token").ifEmpty { obj.str("device_token") }
        if (token.isEmpty()) return DeviceAuthPoll.Pending

        // 上游有时把字段包在 data 里，两处都取，否则 refresh_token 会丢，
        // 表现就是几小时后 401 TOKEN_EXPIRE 且无法自续。
        val data = obj.obj("data") ?: obj
        val uid = data.str("user_id").ifEmpty { data.str("uid") }.ifEmpty { obj.str("user_id") }
        val credential = QoderCredential(
            accessToken = token,
            refreshToken = data.str("refresh_token").ifEmpty { data.str("refreshToken") }
                .ifEmpty { obj.str("refresh_token") },
            uid = uid,
            nickname = data.str("nickname").ifEmpty { data.str("name") },
            realm = info.realm,
            expiresAt = data.epochSeconds("expires_at")
                .takeIf { it > 0L } ?: obj.epochSeconds("expires_at"),
        )
        pending.remove(state)
        // 记录拿到了哪些凭证字段：refresh_token 缺失会导致「登录没多久就 token 失效」
        // 且永远刷不回来，而 Token 本身是能用的，症状很迷惑。
        hooks.onLog(
            "Qoder 设备授权字段：token=${if (token.isNotEmpty()) "有(${token.take(6)}...)" else "无"}" +
                " refresh=${if (credential.refreshToken.isNotEmpty()) "有(${credential.refreshToken.take(5)}...)" else "无"}" +
                " expiresAt=${credential.expiresAt} uid=$uid",
        )
        // 补全昵称/用户类型：失败不影响入库
        val enriched = runCatching { fetchUserInfo(credential) }.getOrDefault(credential)
        hooks.onLog("Qoder：设备授权成功（${enriched.realm}）")
        return DeviceAuthPoll.Success(toAccount(enriched))
    }

    // ------------------------------------------------------------------ 网页登录（自定义 scheme）

    /**
     * 给内置 WebView 用：生成授权 URL，回调地址是自定义 scheme。
     *
     * 为什么不用本地回环：手机浏览器普遍禁止访问 127.0.0.1（Alook 连
     * 127.0.0.1:8888 都打不开），而 Qoder 官方的回调本来就是自定义 scheme
     * `qoder-work-cn://`，由客户端接走——内置 WebView 直接拦下即可。
     */
    override fun beginWebLogin(callbackUrl: String, region: String): WebLoginTicket {
        val realm = QoderRealm.of(region.ifEmpty { currentRealm() })
        val verifier = pkceVerifier()
        val challenge = pkceChallenge(verifier)
        val nonce = if (QoderRealm.nonceDashed[realm] == true) {
            UUID.randomUUID().toString()
        } else {
            UUID.randomUUID().toString().replace("-", "")
        }
        val params = linkedMapOf(
            "challenge" to challenge,
            "challenge_method" to "S256",
            "nonce" to nonce,
            "client_id" to QoderRealm.clientId.getValue(QoderRealm.safe(realm)),
            "machine_id" to UUID.randomUUID().toString(),
        )
        if (QoderRealm.sendRedirectUri[realm] == true) {
            params["redirect_uri"] = callbackUrl
        }
        val query = params.entries.joinToString("&") { (k, v) -> "$k=" + urlEnc(v) }
        val loginUrl = QoderRealm.website.getValue(QoderRealm.safe(realm)) + "/device/selectAccounts?" + query

        val state = "qd-" + nowMillis()
        pending[state] = PendingLogin(verifier, nonce, realm, nowMillis())
        hooks.onLog("Qoder：设备授权 URL($realm) = $loginUrl")
        return WebLoginTicket(state, loginUrl, callbackUrl)
    }

    /**
     * 从自定义 scheme 回调里取 token。
     *
     * 上游回推形态：`qoder-work-cn://auth=<双层编码>&tokenString=<access token>`。
     * 拿到 token 后按正常流程拉 userinfo 补身份；拉不到就用 token 兜底入库，
     * 避免因为一次无关的接口失败把登录结果丢掉。
     */
    override fun completeWebLogin(callbackUrl: String): ProviderAccount {
        val body = callbackUrl.substringAfter("://", "")
        val params = parseSchemeParams(body)
        val token = params["tokenString"].orEmpty()
            .ifEmpty { params["token"].orEmpty() }
            .ifEmpty { params["access_token"].orEmpty() }
        if (token.isEmpty()) {
            throw IllegalStateException("回调里没有 tokenString，无法完成登录")
        }
        val realm = if (callbackUrl.contains("qoder-work-cn")) QoderRealm.CN else currentRealm()
        val base = QoderCredential(accessToken = token, realm = realm)
        val enriched = runCatching { fetchUserInfo(base) }.getOrDefault(base)
        hooks.onLog("Qoder：网页登录成功（${enriched.realm}，uid=${enriched.uid.take(8)}）")
        return toAccount(enriched)
    }

    /** 解析 `k=v&k=v` 形态的参数（不经过 URL 解析器：scheme 后面不是标准 authority）。 */
    private fun parseSchemeParams(raw: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (pair in raw.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx)
            val value = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            out[key] = value
        }
        return out
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val stored = credentialOf(account) ?: return FailedChatCall(
            401,
            """{"error":{"message":"Qoder 账号凭证损坏，请重新登录"}}""",
        )
        // ★ 对话前必须检查过期并刷新：额度和签到早就有这层，对话漏了，
        //   于是 token 一过期就报 {"code":"105","message":"Login expired"}。
        val credential = ensureFresh(stored)
        val body = runCatching { JsonParser.parseString(openAiBody).asJsonObject }
            .getOrNull() ?: return FailedChatCall(400, """{"error":{"message":"请求体不是合法 JSON"}}""")

        val stream = body.get("stream")?.let { !it.isJsonNull && it.asBoolean } ?: false
        val modelKey = resolveModelKey(body.str("model"), credential.realm)
        val upstreamBody = buildUpstreamBody(body, modelKey, credential, stream)
        val bodyJson = upstreamBody.toString()
        val encoded = QoderCodec.encodeBody(bodyJson.toByteArray(Charsets.UTF_8))
        // 记下完整请求体：4001 只能靠真实字节定位。
        // 上层日志有环形缓冲上限，这里按 3000 字截断，足够看出结构性问题。
        hooks.onLog("Qoder 对话请求 model=$modelKey stream=$stream bodyLen=${bodyJson.length}")
        val session = sessions.get(credential)
        val url = QoderRealm.gateway.getValue(QoderRealm.safe(credential.realm)) + CHAT_PATH
        val headers = session.headers(encoded, url, modelKey, sse = stream)
        // 详细日志：只记请求体长度与签名头名。
        // 不落盘、不打全文 —— body 含用户对话内容，headers 含 token，
        // 一旦写进日志或文件，等于把用户隐私和凭证抄了一份在磁盘上。
        hooks.onVerbose("Qoder 请求", "bodyLen=${bodyJson.length} hdrs=${headers.size}")

        val conn = try {
            openPost(url, encoded, headers)
        } catch (e: Exception) {
            return FailedChatCall(0, """{"error":{"message":"连接上游失败：${e.message}"}}""")
        }

        val status = runCatching { conn.responseCode }.getOrDefault(0)
        if (status !in 200..299) {
            val err = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }
                .getOrNull().orEmpty()
            runCatching { conn.disconnect() }
            // 记上游原文：4001/418 这类是请求形态问题，只看到被包装后的
            // message 无法判断缺哪个字段。
            hooks.onLog("Qoder 对话失败 HTTP $status：${err.take(400)}")
            // ★ 上游排队：403 + (10605 | isQueued | RESOURCE_LIMIT)。
            //   这不是失败，是临时资源限制，等 retryAfterSeconds 后重试即可。
            //   直接把这个 JSON 丢给客户端的话，客户端会当成「请求被拒」结束本轮。
            if (err.contains("10605") || err.contains("isQueued") || err.contains("RESOURCE_LIMIT")) {
                // 从错误体里取 retryAfterSeconds；取不到就等 30 秒（上游常见值）
                val waitSec = Regex("retryAfterSeconds\\D+(\\d+)")
                    .find(err)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 30L
                hooks.onLog("Qoder 上游排队，${waitSec}s 后重试：${err.take(160)}")
                runCatching { Thread.sleep((waitSec.coerceIn(1, 60)) * 1000) }
                return openChat(account, openAiBody)
            }
            // 上游用 code=105 表示登录过期（HTTP 200/400 都可能）。这里刷新一次
            // 凭证后重试，避免用户看到「Login expired」还得手动重新登录。
            if (err.contains("\"105\"") || err.contains("Login expired")) {
                val refreshed = runCatching { refreshAccount(account, 0) }.getOrNull()
                if (refreshed != null) {
                    // 必须写回账号池：只把新凭证用于本次重试的话，
                    // 下次请求又会拿到旧的过期 token，永远循环。
                    hooks.onAccountUpdated(refreshed)
                    hooks.onLog("Qoder 凭证已过期，已刷新并重试")
                    return openChat(refreshed, openAiBody)
                }
                hooks.onLog("Qoder 凭证过期且刷新失败，需要重新登录")
            }
            // 4001 排查期把请求体头部拼到**最终 message** 上。
            // 不能拼进 raw 再交给 mapUpstreamError：那个函数会重新解析 JSON
            // 只取 message 字段，拼在 raw 尾部的诊断会被整段丢弃。
            val diag = if (status == 400 || err.contains("4001")) {
                "【model=" + modelKey + " realm=" + credential.realm +
                    " bodyLen=" + bodyJson.length + "】 " + bodyJson.take(900) + " || "
            } else {
                ""
            }
            val mapped = runCatching { JsonParser.parseString(mapUpstreamError(status, err)).asJsonObject }
                .getOrNull()
            if (mapped != null && diag.isNotEmpty()) {
                val errObj = mapped.obj("error")
                if (errObj != null) {
                    // 诊断放前面：客户端可能只显示 message 开头一段，
                    // 拼在末尾等于没拼。
                    errObj.addProperty("message", diag + errObj.str("message"))
                }
                return FailedChatCall(status, mapped.toString())
            }
            return FailedChatCall(status, mapUpstreamError(status, err))
        }

        val raw = conn.inputStream

        // ★ 排队会以 **HTTP 200 + SSE 里带错误** 的形式返回，走不到上面的非 2xx
        //   分支。若直接返回 StreamingChatCall，这段错误 JSON 会被当正常流透传，
        //   客户端判「请求被拒」结束本轮——用户看到的就是 403 IsQueued。
        //   所以流式请求先嗅一帧：命中排队就等 retryAfterSeconds 后整请求重试。
        if (stream) {
            val sseReader = runCatching { raw.bufferedReader(Charsets.UTF_8) }.getOrNull()
            val firstLine = runCatching { sseReader?.readLine() }.getOrNull().orEmpty()
            if (firstLine.contains("10605") || firstLine.contains("isQueued") ||
                firstLine.contains("RESOURCE_LIMIT")
            ) {
                val waitSec = Regex("retryAfterSeconds\\D+(\\d+)")
                    .find(firstLine)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 30L
                // 不在这里干等：等 30s 期间对话毫无输出，用户体感就是「卡死」。
                // 交给网关按 429 处理 —— 它会立刻换账号重试（continue 分支），
                // 所有账号都排队时才由客户端层重试，比单账号硬等快得多。
                hooks.onLog("Qoder 上游排队 ${waitSec}s，交回网关换号：${firstLine.take(140)}")
                runCatching { raw.close() }
                runCatching { conn.disconnect() }
                return FailedChatCall(
                    429,
                    """{"code":"10605","message":"上游排队，已切换账号重试","subCode":"RESOURCE_LIMIT"}""",
                )
            }
            // ★ 登录过期也是同样的坑：HTTP 200 + 首帧 {"code":"105"}。
            //   不在这里处理的话，刷新永远不会触发（走不到非 2xx 分支），
            //   日志里一条 `Qoder 刷新` 都没有，用户只看到「掉线」。
            if (firstLine.contains("\"105\"") || firstLine.contains("Login expired")) {
                hooks.onLog("Qoder 对话遇到 105（流内），刷新凭证后重试")
                runCatching { raw.close() }
                runCatching { conn.disconnect() }
                val refreshed = runCatching { refreshAccount(account, 0) }.getOrNull()
                if (refreshed != null) {
                    hooks.onAccountUpdated(refreshed)
                    return openChat(refreshed, openAiBody)
                }
                hooks.onLog("Qoder 凭证刷新失败，需要重新登录")
                return FailedChatCall(401, """{"error":{"message":"Qoder 登录已过期，请重新登录"}}""")
            }
            // 不是排队也不是过期：把已读的首行连同剩余流交给解包器，不能丢内容。
            val replay = java.io.ByteArrayInputStream(
                ((firstLine + "\n").toByteArray(Charsets.UTF_8)),
            )
            val merged = java.io.SequenceInputStream(replay, raw)
            return streamOut(merged, conn, modelKey)
        }

        if (!stream) {
            // 非流式：把信封流聚合完再交差
            val text = runCatching {
                val sb = StringBuilder()
                val unwrapper = QoderSseUnwrapper(raw)
                var sawDone = false
                val content = StringBuilder()
                for (frame in unwrapper.frames()) {
                    val payload = frame.removePrefix("data:").trim()
                    if (payload == "[DONE]") { sawDone = true; break }
                    val obj = runCatching {
                        JsonParser.parseString(payload).asJsonObject
                    }.getOrNull() ?: continue
                    val first = obj.arr("choices")?.firstOrNull()
                    val delta = runCatching { first?.asJsonObject }.getOrNull()?.obj("delta")
                    content.append(delta?.str("content").orEmpty())
                }
                runCatching { raw.close() }
                if (content.isEmpty() && !sawDone) "" else aggregate(modelKey, content.toString())
            }.getOrDefault("")
            runCatching { conn.disconnect() }
            if (text.isEmpty()) {
                return FailedChatCall(502, """{"error":{"message":"上游未返回内容"}}""")
            }
            return dev.aigw.core.provider.AggregatedChatCall(200, text)
        }

        return streamOut(raw, conn, modelKey)
    }

    /** 把解包后的标准 SSE 通过管道转出去（openChat 需立刻返回，网关才好并行）。 */
    private fun streamOut(
        raw: java.io.InputStream,
        conn: HttpURLConnection,
        modelKey: String,
    ): ChatCall {
        val pipeIn = java.io.PipedInputStream(PIPE_SIZE)
        val pipeOut = java.io.PipedOutputStream(pipeIn)
        val unwrapper = QoderSseUnwrapper(raw)
        val worker = Thread {
            try {
                pipeOut.bufferedWriter(Charsets.UTF_8).use { w ->
                    for (frame in unwrapper.frames()) {
                        w.write(frame)
                        w.flush()
                    }
                    if (unwrapper.envelopeError.isNotEmpty()) {
                        val msg = JsonObject().apply {
                            add("error", JsonObject().apply {
                                addProperty("message", unwrapper.envelopeError.take(300))
                            })
                        }
                        w.write("data: $msg\n\n")
                        w.flush()
                    }
                }
            } catch (_: Exception) {
                // 客户端断开或上游中断都走这里，属正常路径
            } finally {
                runCatching { raw.close() }
                runCatching { pipeOut.close() }
            }
        }.apply {
            isDaemon = true
            name = "qoder-sse"
            start()
        }

        return StreamingChatCall(200, pipeIn) {
            worker.interrupt()
            runCatching { raw.close() }
            runCatching { conn.disconnect() }
        }
    }

    // ------------------------------------------------------------------ 区域

    /**
     * 账号属于哪个区域。
     *
     * 必须由凭证里记录的真实区域回答：国内与国际是两套账号体系，
     * 同一个邮箱在两边是不同账号、额度也分开。按登录时选的区域落库，
     * 界面才能把两类账号分开显示，也才不会拿国内 token 去打国际端点。
     */
    override fun regionOf(account: ProviderAccount): String? =
        credentialOf(account)?.realm?.ifEmpty { null }

    /**
     * 老凭证补区域。
     *
     * 区域区分是后加的：在那之前登录的账号凭证里没有 realm 字段，
     * 于是既不属于国内也不属于国际，界面上两边都不显示。
     * 这里用它的 token 分别试国内/国际的 userinfo，哪个区认就补哪个区。
     *
     * 只在确认是旧凭证（realm 为空）时才介入，拿不准就返回 null 不动用户凭证。
     */
    override fun migrateAccount(account: ProviderAccount): ProviderAccount? {
        val credential = credentialOf(account) ?: return null
        if (credential.realm.isNotEmpty()) return null
        for (realm in listOf(QoderRealm.CN, QoderRealm.INTL)) {
            // 必须看 HTTP 状态，不能看返回对象是否非空：
            // fetchUserInfo 失败时原样返回入参，那样两个区都会「成功」，
            // 结果是国际账号被错补成国内。
            val base = QoderRealm.openapi.getValue(realm)
            val (status, _) = httpGet(
                "$base$PATH_USERINFO",
                mapOf(
                    "Accept" to "application/json",
                    "Authorization" to "Bearer ${credential.accessToken}",
                ),
            )
            if (status !in 200..299) continue
            val probe = fetchUserInfo(credential.copy(realm = realm))
            return toAccount(probe.copy(realm = realm))
        }
        return null
    }

    override fun regions(): List<String> = listOf(QoderRealm.CN, QoderRealm.INTL)

    // ------------------------------------------------------------------ 额度与签到

    /**
     * 查额度：官方 `/api/v2/quota/usage`。
     *
     * 返回的是「已用/总量」而不是单一余额，所以这里换算成剩余 credits。
     * 字段缺失时返回 known=false，界面显示「—」而不是误导性的 0。
     */
    /**
     * 查额度。
     *
     * 上游结构不是「一个 remaining 字段」，而是一组额度容器：
     * `planQuota` / `sotaCredits` / `addonQuota` / `dedicatedResourcePackages` /
     * `orgResourcePackage`，每个容器内部才是 `remaining` / `remain` / `balance`。
     * 之前只按 `remaining` 顶层找，必然拿不到数（界面一直是「—」）。
     *
     * 这里把能识别的容器全加起来：
     * - 套餐内、SOTA、资源包都算账号可用额度
     * - 共享池单独算（cap=-1 表示不限量，不并入合计）
     */
    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val raw = credentialOf(account) ?: return null
        val credential = ensureFresh(raw)
        val base = QoderRealm.openapi.getValue(QoderRealm.safe(credential.realm))
        // 额度端点按区域不同，而且官方改过好几次：
        // 国际版 /api/v2/quota/usage 经常 404/403，真正的数据在官网会话侧的
        // sash 的 me 系列接口下。这里按候选顺序试，第一个能解析出数字的胜出。
        // 基址 × 端点：国际版 openapi.qoder.sh 常不可达，center.qoder.sh 才是
        // 官网会话侧入口；只试单一基址时额度永远拿不到。
        var root: JsonObject? = null
        var usedPath = ""
        val bases = QoderRealm.openapiCandidates(credential.realm)
        val attempts = bases.flatMap { b -> QUOTA_PATHS.map { b to it } }
        for ((base, path) in attempts) {
            // sash 系列属于官网会话侧接口，和活动平台一样要求桌面端头；
            // 只带 Accept + Authorization 时会拿到 401/403 或空结构。
            val headers = if (path.startsWith("/sash/")) {
                campaignHeaders(credential)
            } else {
                mapOf(
                    "Accept" to "application/json",
                    "Authorization" to "Bearer ${credential.accessToken}",
                )
            }
            val (status, text) = httpGet("$base$path", headers)
            if (status !in 200..299) {
                hooks.onLog("Qoder 额度 $path → HTTP $status（${credential.realm}）")
                continue
            }
            val parsed = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            if (parsed == null) continue
            val buckets = collectQuotaBuckets(parsed)
            if (buckets.isEmpty()) {
                hooks.onLog("Qoder 额度 $path → 200 但无可识别字段：${text.take(200)}")
                continue
            }
            root = parsed
            usedPath = path
            break
        }
        if (root == null) return null
        hooks.onLog("Qoder 额度命中 $usedPath（${credential.realm}）")

        val buckets = collectQuotaBuckets(root)
        if (buckets.isEmpty()) return CreditInfo(balance = 0, known = false)
        val total = buckets.sumOf { it.remain }
        val detail = buckets
            .filter { it.remain > 0 || it.size > 0 }
            .joinToString(" · ") { "${it.name} ${it.remain}${if (it.size > 0) "/${it.size}" else ""}" }
        hooks.onLog("Qoder 额度解析：$detail（合计 $total）")
        return CreditInfo(balance = total, known = true, detail = detail)
    }

    /** 一个额度容器。 */
    private data class QuotaBucket(val name: String, val remain: Long, val size: Long)

    /**
     * 遍历额度响应，收集所有可识别的额度容器。
     *
     * 用广度优先而不是写死路径：上游把不同活动额度挂在不同层级
     * （有的在顶层，有的在 dedicatedResourcePackages 数组里）。
     */
    private fun collectQuotaBuckets(root: JsonObject): List<QuotaBucket> {
        val keys = mapOf(
            "planquota" to "套餐",
            "userquota" to "套餐",
            "subscriptionquota" to "套餐",
            "basequota" to "套餐",
            "sotacredits" to "SOTA",
            "sotaquota" to "SOTA",
            "sotaspecial" to "SOTA",
            "addonquota" to "资源包",
            "resourcepackages" to "资源包",
            "resourcepackage" to "资源包",
            "dedicatedresourcepackages" to "专属包",
            "exclusivepackages" to "专属包",
            "sotapackages" to "专属包",
            "orgresourcepackage" to "共享池",
            "sharedquota" to "共享池",
        )
        val out = mutableListOf<QuotaBucket>()
        val queue = ArrayDeque<com.google.gson.JsonElement>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 200) {
            val node = queue.removeFirst()
            if (node.isJsonArray) {
                node.asJsonArray.forEach { if (it.isJsonObject || it.isJsonArray) queue.add(it) }
                continue
            }
            if (!node.isJsonObject) continue
            for ((k, v) in node.asJsonObject.entrySet()) {
                val label = keys[k.lowercase()]
                if (label != null) {
                    when {
                        v.isJsonObject -> readBucket(v.asJsonObject, label)?.let { out.add(it) }
                        v.isJsonArray -> v.asJsonArray.forEach { item ->
                            if (item.isJsonObject) readBucket(item.asJsonObject, label)?.let { out.add(it) }
                        }
                        v.isJsonPrimitive -> runCatching { v.asLong }.getOrNull()?.let {
                            if (it > 0) out.add(QuotaBucket(label, it, it))
                        }
                    }
                }
                if (v.isJsonObject || v.isJsonArray) queue.add(v)
            }
        }
        // 去重：同一份额度可能被多个键名命中
        return out.distinctBy { it.name to (it.remain to it.size) }
    }

    private fun readBucket(obj: JsonObject, label: String): QuotaBucket? {
        val remain = obj.num("remaining") ?: obj.num("remain") ?: obj.num("balance")
        val size = obj.num("total") ?: obj.num("size") ?: obj.num("cap")
        if (remain == null && size == null) return null
        val r = (remain ?: (size ?: 0.0)).toLong()
        val s = (size ?: r.toDouble()).toLong()
        // 上游的展示名（如「SOTA 专属积分」）比分类标签更准确
        val name = obj.str("name").ifEmpty { obj.str("label").ifEmpty { label } }
        return QuotaBucket(name, r, s)
    }

    /**
     * 每日签到：先查状态再领，避免重复请求（一个周期只能领一次）。
     *
     * 周期是**每天 10:00 到次日 10:00**，所以不能按自然日判断。
     */
    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult {
        if (action != ACTION_CHECKIN) return ProviderActionResult.unsupported(action)
        var credential = credentialOf(account)
            ?: return ProviderActionResult.failure("凭证损坏，请重新登录")
        // 401 = token 过期：先刷新再继续，不要让用户看到一个「签到失败」就结束
        credential = ensureFresh(credential)
        return checkinViaCampaigns(credential)
    }

    /**
     * 每日签到：走官方 **campaign 活动平台**。
     *
     * ## 为什么不用 daily-check-in 接口
     *
     * 官方早就把「每日领取 100 Credits」搬到了 campaign 平台，daily-check-in
     * 那套接口在多数区域已经拿不到东西（2026-10-26 实测 + 参考实现文档都印证）。
     * 用旧接口的表现是：状态查询永远「未签到」、领取直接 404/409。
     *
     * ## 流程
     *
     * 1. `GET /sash/api/v1/me/campaigns` 列出活动
     * 2. 筛出 `claimStatus == CLAIMABLE` 且 `actionType == CLAIM_BENEFIT` 的活动
     * 3. 逐个 `POST /sash/api/v1/me/campaigns/{id}/claim`（body `{}`）
     * 4. `CLAIMED` 的计入「今日已领」，不再重复 POST
     *
     * 上游偶发返回空列表（每天大约 1/5 概率），所以空结果重试两次再放弃。
     */
    private fun checkinViaCampaigns(credential: QoderCredential): ProviderActionResult {
        // ★ 强制刷新一次再拉活动列表。
        //
        // ensureFresh() 只在 expiresAt 有效时才刷新；国际版账号的 expiresAt
        // 常为 0（上游没给），于是它原样返回旧 token，签到永远 401 TOKEN_EXPIRE。
        // 签到是低频操作，多刷一次没有代价。
        // 无条件刷新：签到是低频操作，多一次刷新没代价。
        // 靠 expiresAt 判断在 Qoder 上不可靠——上游经常不返回该字段（=0），
        // ensureFresh 于是原样放行，随后 requests 全被 401 拒掉。
        val fresh = runCatching { refreshAccount(toAccount(credential), 0) }.getOrNull()
        if (fresh != null) {
            hooks.onAccountUpdated(fresh)
            credentialOf(fresh)?.let { return checkinWith(it) }
        }
        hooks.onLog("Qoder 签到前刷新失败，用现有凭证继续（可能 401）")
        return checkinWith(credential)
    }

    private fun checkinWith(credential: QoderCredential): ProviderActionResult {
        val bases = QoderRealm.openapiCandidates(credential.realm)

        var campaigns: List<JsonObject> = emptyList()
        for (attempt in 0..2) {
            campaigns = fetchCampaigns(bases, campaignHeaders(credential), credential)
            // 有可领项就停；空列表继续重试（上游抖动）
            if (campaigns.any { isClaimable(it) }) break
            if (attempt < 2) Thread.sleep(if (attempt == 0) 800L else 2000L)
        }

        // 国际版专有兜底：列表为空时，改用「只发 os + hostname」的最小机器头再试一次。
        // 官方把「六头全发」判成非官方客户端并整条过滤 CLAIMABLE 活动，
        // 而「一个机器头都不发」在国际版可能连 showCampaign 都是 false。
        if (campaigns.none { isClaimable(it) }) {
            val minimal = fetchCampaigns(bases, campaignHeaders(credential, minimal = true), credential)
            if (minimal.any { isClaimable(it) }) {
                campaigns = minimal
            } else if (campaigns.isEmpty()) {
                campaigns = minimal
            }
        }

        if (campaigns.isEmpty()) {
            return ProviderActionResult.failure("没有拿到活动列表，稍后再试")
        }

        val already = campaigns.count { isClaimedCheckin(it) }
        val claimable = campaigns.filter { isClaimable(it) }

        if (claimable.isEmpty()) {
            return if (already > 0) {
                ProviderActionResult.success("今日已签到").copy(kind = "already")
            } else {
                ProviderActionResult.success("当前没有可领取的活动").copy(kind = "already")
            }
        }

        // 领取用主基址与常规头：能列出活动说明主基址是通的
        val claimBase = bases.first()
        val claimHeaders = campaignHeaders(credential)
        var earned = 0L
        val names = mutableListOf<String>()
        for (campaign in claimable) {
            val id = campaign.str("campaignId").ifEmpty { campaign.str("campaign_id") }
            if (id.isEmpty()) continue
            val (status, text) = httpPost(
                claimBase + String.format(PATH_CAMPAIGN_CLAIM, id),
                "{}",
                claimHeaders + mapOf("Content-Type" to "application/json"),
            )
            if (status !in 200..299) {
                hooks.onLog("Qoder 领取 $id → HTTP $status ${text.take(120)}")
                // 409/已领：换个活动继续
                continue
            }
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            val data = obj?.obj("data") ?: obj
            val replayed = data?.get("replayed")?.takeIf { !it.isJsonNull }?.asBoolean == true
            val amount = data?.num("amount")?.toLong()
                ?: campaign.obj("benefit")?.num("amount")?.toLong()
                ?: 0L
            val label = campaign.str("campaignKey")
                .ifEmpty { campaign.str("campaign_key") }
                .ifEmpty { campaign.str("name") }
            if (replayed) {
                names.add("${label.ifEmpty { "活动" }}（已领过）")
            } else {
                earned += amount
                names.add("${label.ifEmpty { "活动" }} +$amount")
            }
            // 相邻请求间隔，避免触发风控
            Thread.sleep(1200L)
        }

        return if (earned > 0 || names.isNotEmpty()) {
            ProviderActionResult.success(
                if (names.isEmpty()) "签到成功" else names.joinToString("、"),
            ).copy(kind = "claimed", amount = earned)
        } else {
            ProviderActionResult.success("今日已签到").copy(kind = "already")
        }
    }

    /**
     * 活动平台的出站请求头（`/sash/api/v1/me/campaigns` 必需）。
     *
     * 对齐官方桌面端 0.4.8：`cosy-clienttype` 用 **10**（桌面端；CLI 是 5），
     * 且必须带 `User-Agent: Qoder` 与 `cosy-version`。
     *
     * ## 踩过的两层坑
     *
     * 1. 缺 UA / cosy-clienttype / cosy-version → 服务端**不报错**，直接返回
     *    空活动列表。表现就是「没有拿到活动列表」，很难联想到是请求头问题。
     * 2. **千万别发全套 `cosy-machine*` 六头**：服务端会判成非官方客户端，
     *    把 CLAIMABLE 的「每日领取 100 Credits」整条过滤掉，只留 VIEW_DETAILS。
     *    逐头隔离实测：六头全发被过滤；去掉 machinetoken 或 machineid 就可见。
     *
     * 所以这里只发最少的身份头：不派生 machine 系列。
     */
    private fun campaignHeaders(
        credential: QoderCredential,
        minimal: Boolean = false,
    ): Map<String, String> {
        val h = mutableMapOf(
            "Accept" to "application/json",
            "User-Agent" to "Qoder",
            "Authorization" to "Bearer ${credential.accessToken}",
            // 桌面端身份（活动平台只认桌面端）：
            //   clienttype 10 = 桌面端；5 是 CLI、6 是 QoderWork
            //   version 用桌面端版本，不是 CLI 的 1.1.75
            "cosy-clienttype" to "10",
            "cosy-version" to DESKTOP_CLIENT_VERSION,
        )
        // 机器头：只有**真实** UMID 才能发全套六头。
        //
        // 服务端把任何**派生**（算出来的）cosy-machine* 判定为非官方客户端：
        // 轻则活动列表变空，重则把 CLAIMABLE 的「每日领取 100 Credits」整条过滤掉，
        // 只剩 VIEW_DETAILS ——表现就是「当前没有可领取的活动」。
        //
        // Android 跑不了官方 runtime-info（glibc 二进制），所以用预生成的真实
        // 身份池按 uid 固定分配。池缺失时退化为「一个都不发」，那是已知可接受
        // 的行为（列表可能为空，但至少不会被判成伪造客户端）。
        val identity = QoderUmidPool.forUid(credential.realm, credential.uid)
        if (minimal) {
            // 最小机器头：**只发 os + hostname**。
            // 六头全发会被判非官方、把 CLAIMABLE 活动整条隐藏；
            // 这组实测仍被当成官方客户端，能把隐藏列表恢复回来。
            h["cosy-machineos"] = "x86_64_linux"
            h["cosy-machinehostname"] = identity?.hostname ?: "DESKTOP-7F3A1C9"
            return h
        }
        if (identity != null) {
            h["cosy-machineid"] = identity.machineId
            h["cosy-machinetoken"] = identity.machineToken
            h["cosy-machinetype"] = identity.machineType
            h["cosy-machinecode"] = identity.machineCode
            h["cosy-machineos"] = identity.machineOs
            h["cosy-machinehostname"] = identity.hostname
        }
        return h
    }

    /**
     * 拉活动列表。
     *
     * 基址要挨个试：国际版 `openapi.qoder.sh` 在部分网络不可达，
     * 活动平台另有一个 `center.qoder.sh` 入口。只用一个基址的表现是
     * 「没有拿到活动列表」，用户完全看不出是域名问题。
     */
    /**
     * 拉活动列表，**照搬参考实现的三层回退**：
     *
     * 1. 桌面端头（正常路径）
     * 2. 桌面端头拿到 401/403 → 换普通头再试一次
     * 3. 请求成功但 `showCampaign=false`（服务端把列表藏了）→
     *    用「最小机器头」（只 os + hostname）再试一次，实测能恢复
     *
     * 第 3 条是国际版的关键：全套机器头会被判非官方而隐藏列表，
     * 只发 os + hostname 反而被当成官方客户端。
     */
    private fun fetchCampaigns(
        bases: List<String>,
        headers: Map<String, String>,
        credential: QoderCredential,
    ): List<JsonObject> {
        for (base in bases) {
            // ① 桌面端头
            val first = fetchCampaignsFrom(base, headers)
            if (first != null && first.isNotEmpty()) return first

            // ② 401/403 → 普通头
            val plain = headers - setOf(
                "cosy-clienttype", "cosy-version",
                "cosy-machineid", "cosy-machinetoken", "cosy-machinetype",
                "cosy-machinecode", "cosy-machineos", "cosy-machinehostname",
            )
            val second = fetchCampaignsFrom(base, plain)
            if (second != null && second.isNotEmpty()) return second

            // ③ showCampaign=false → 最小机器头（只 os + hostname）
            val minimal = campaignHeaders(credential, minimal = true)
            val third = fetchCampaignsFrom(base, minimal)
            if (third != null && third.isNotEmpty()) {
                hooks.onLog("Qoder 活动列表用最小机器头恢复（$base）")
                return third
            }
        }
        return emptyList()
    }

    /** 单个基址拉列表；连不上/非 2xx 返回 null 让调用方换基址。 */
    private fun fetchCampaignsFrom(base: String, headers: Map<String, String>): List<JsonObject>? {
        val (status, text) = httpGet("$base$PATH_CAMPAIGNS", headers)
        if (status !in 200..299) {
            hooks.onLog("Qoder 活动列表 $base → HTTP $status ${text.take(100)}")
            return null
        }
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return null
        val data = root.obj("data") ?: root
        // 服务端用 showCampaign 表示「是否给我看活动」，为 false 时 campaigns 为空，
        // 这是**身份不被认可**的信号，而不是「今天没活动」。
        val show = data.get("showCampaign")?.takeIf { !it.isJsonNull }?.asBoolean
            ?: root.get("showCampaign")?.takeIf { !it.isJsonNull }?.asBoolean
        val list = data.arr("campaigns") ?: data.arr("items").orEmpty()
        // 把每条活动的状态打出来：国际版「没有可领取的活动」到底是
        // 上游没给 CLAIMABLE，还是给了但被我们判掉，必须看原文而不是猜。
        val summary = list.mapNotNull { el ->
            runCatching { el.asJsonObject }.getOrNull()?.let { o ->
                val st = o.str("claimStatus").ifEmpty { o.str("claim_status") }
                val at = o.str("actionType").ifEmpty { o.str("action_type") }
                val kind = o.obj("benefit")?.str("kind").orEmpty()
                val amt = o.obj("benefit")?.num("amount")?.toLong() ?: 0L
                "$st/$at/${kind.ifEmpty { "-" }}/$amt"
            }
        }
        hooks.onLog("Qoder 活动列表($base)：showCampaign=$show 条数=${list.size} → $summary")
        return list.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }
    }

    /** 可领取的签到类活动：必须 CLAIMABLE，且 actionType 是可领型。 */
    private fun isClaimable(campaign: JsonObject): Boolean {
        val status = campaign.str("claimStatus").ifEmpty { campaign.str("claim_status") }
        if (!status.equals("CLAIMABLE", true)) return false
        val actionType = campaign.str("actionType").ifEmpty { campaign.str("action_type") }
        // 空串按可领处理（部分区域不下发该字段），详情类要排除
        return actionType.isEmpty() || actionType.equals("CLAIM_BENEFIT", true)
    }

    /**
     * 已领取的签到活动。
     *
     * 注意上游领取后会把 actionType **降级成 VIEW_DETAILS**（国际版实测），
     * 所以不能用 actionType 反推，只能看 claimStatus + 是否有奖励金额。
     */
    private fun isClaimedCheckin(campaign: JsonObject): Boolean {
        val status = campaign.str("claimStatus").ifEmpty { campaign.str("claim_status") }
        if (!status.equals("CLAIMED", true)) return false
        val amount = campaign.obj("benefit")?.num("amount") ?: 0.0
        val actionType = campaign.str("actionType").ifEmpty { campaign.str("action_type") }
        return amount > 0 || actionType.isEmpty() || actionType.equals("CLAIM_BENEFIT", true)
    }

    /** 代理设置按供应商开关时用：Qoder 的四个上游域。 */
    override fun hosts(): List<String> = listOf(
        "qoder.com.cn",
        "qoder.com",
        "qoder.sh",
    )

    // ------------------------------------------------------------------ 凭证刷新

    /**
     * 刷新凭证。
     *
     * 官方按 **refresh_token 前缀** 路由刷新端点：`drt-` 是设备授权族、
     * `jrt-` 是 jobToken 族。只用其中一个端点时，另一族的账号永远刷不成功，
     * 表现就是「国际版一直 401 TOKEN_EXPIRE，额度刷不出来」。
     *
     * 两个端点都试，先按前缀选优先顺序，失败再回落另一个。
     */
    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? =
        // ★ 刷新必须串行。OAuth 的 refresh_token 是一次性的：模型列表、额度、
        //   签到、对话若同时触发刷新，第一个成功后 token 即作废，其余的必然
        //   401 —— 用户看到的就是「刚登录没多久又掉线」。
        synchronized(refreshLock) { doRefresh(account) }

    private fun doRefresh(account: ProviderAccount): ProviderAccount? {
        val credential = credentialOf(account) ?: return null
        if (credential.refreshToken.isEmpty()) {
            // 这条日志很关键：没有任何 `Qoder 刷新` 日志多半就是卡在这里——
            // 凭证里根本没有 refresh_token，刷新被静默跳过，表现为「掉线」。
            hooks.onLog(
                "Qoder 无 refresh_token，无法续期（${credential.realm} " +
                    "uid=${credential.uid}）需要重新登录",
            )
            return null
        }
        val base = QoderRealm.openapi.getValue(QoderRealm.safe(credential.realm))
        val payload = JsonObject().apply {
            addProperty("refresh_token", credential.refreshToken)
        }
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
        // drt- 先试设备端点；其余先试 job 端点，都不通过再换另一个
        val paths = if (credential.refreshToken.startsWith("drt-")) {
            listOf(PATH_DEVICE_REFRESH, PATH_JOB_REFRESH)
        } else {
            listOf(PATH_JOB_REFRESH, PATH_DEVICE_REFRESH)
        }
        for (path in paths) {
            val (status, text) = httpPost("$base$path", payload.toString(), headers)
            if (status !in 200..299) {
                hooks.onLog("Qoder 刷新 $path → HTTP $status（${credential.realm}）")
                continue
            }
            hooks.onLog("Qoder 刷新 $path 返回：${text.take(300)}")
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                ?: continue
            val data = obj.obj("data") ?: obj
            // 响应可能再包一层（data.result / data.data），日志里看到过
            // 「未取到 token」但状态 200 —— 十有八九是这一层没剥。
            // 字段名逐个试：官方不同版本用过 token / access_token /
            // device_token / jobToken，漏一个就会被当成「未取到 token」→ 掉线。
            val token = data.str("token")
                .ifEmpty { data.str("access_token") }
                .ifEmpty { data.str("accessToken") }
                .ifEmpty { data.str("device_token") }
                .ifEmpty { data.str("deviceToken") }
                .ifEmpty { data.str("job_token") }
                .ifEmpty { data.str("jobToken") }
            if (token.isEmpty()) {
                // 必须带原文：只有「未取到 token」这一句时根本不知道字段叫啥。
                hooks.onLog("Qoder 刷新 $path 未取到 token，原文=${text.take(600)}")
                continue
            }
            // 记录刷新响应的**完整字段名**：refresh_token 轮换时若字段名猜错，
            // 会一直沿用已被消耗的旧值，表现就是「登录十几分钟后又掉线」。
            hooks.onLog(
                "Qoder 刷新成功（$path）token=${token.take(6)}... " +
                    "refresh=${data.str("refresh_token").ifEmpty { data.str("refreshToken") }.take(6)} " +
                    "响应字段=${obj.keySet().joinToString(",")}",
            )
            return toAccount(
                credential.copy(
                    accessToken = token,
                    refreshToken = data.str("refresh_token")
                        .ifEmpty { data.str("refreshToken") }
                        .ifEmpty { credential.refreshToken },
                    expiresAt = data.epochSeconds("expires_at").takeIf { it > 0L }
                        ?: data.epochSeconds("expiresAt").takeIf { it > 0L }
                        ?: credential.expiresAt,
                ),
            )
        }
        return null
    }

    /** 粘贴导入：接受凭证 JSON，或直接粘贴一枚 access token。 */
    override fun importCredentials(raw: String): ProviderAccount? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        QoderCredential.parse(text)?.let { return toAccount(it) }
        // 不是 JSON：当作裸 token，uid 靠 userinfo 补
        if (!text.startsWith("{")) {
            val base = QoderCredential(accessToken = text)
            val enriched = runCatching { fetchUserInfo(base) }.getOrDefault(base)
            return toAccount(enriched)
        }
        return null
    }

    // ------------------------------------------------------------------ 内部工具

    /**
     * 上游错误分类：网关据此决定换号还是直接返回。
     *
     * 401/403 一定是凭证问题（该换号）；429 是频控（冷却后重试）；
     * 5xx 视为上游故障（同号可重试）。
     */
    override fun classify(status: Int, body: String): UpstreamError {
        val message = runCatching {
            val o = JsonParser.parseString(body).asJsonObject
            o.obj("error")?.str("message")
                ?: o.str("errorMessage")
                ?: o.str("message")
        }.getOrNull().orEmpty().ifEmpty { body.take(300) }
        val kind = when {
            // 排队不算凭证失效：Qoder 的 403 里混着 10605 IsQueued / RESOURCE_LIMIT，
            // 归成 SESSION_DEAD 会被池子永久禁用账号，几次排队就把号全封了。
            body.contains("10605") || body.contains("isQueued") || body.contains("RESOURCE_LIMIT") ->
                ErrorKind.SOFT_RATE
            status == 401 -> ErrorKind.SESSION_DEAD
            status == 403 -> ErrorKind.SERVER
            status == 429 -> ErrorKind.SOFT_RATE
            status == 404 -> ErrorKind.NOT_FOUND
            status == 0 -> ErrorKind.NETWORK
            status in 400..499 -> ErrorKind.CLIENT
            else -> ErrorKind.SERVER
        }
        return UpstreamError(kind, message, status.toLong())
    }

    /**
     * 凭证快过期（或已过期）时先刷新。
     *
     * 上游的 access token 只有几小时有效期，而额度/签到是低频操作，
     * 用户点的时候大概率已经过期。不在这里兜一下，界面上就会反复出现
     * 「401 TOKEN_EXPIRE」「额度刷不出来」。
     */
    private fun ensureFresh(credential: QoderCredential): QoderCredential {
        // expiresAt=0 表示上游没给，这时无法判断，直接按有效处理
        if (credential.expiresAt <= 0L) return credential
        val now = nowMillis() / 1000
        if (credential.expiresAt - now > FRESH_SKEW_SECONDS) return credential
        val refreshed = runCatching {
            refreshAccount(toAccount(credential), 0)
        }.getOrNull() ?: return credential
        // 写回账号池：不落盘的话下一次读到的还是过期 token。
        hooks.onAccountUpdated(refreshed)
        return credentialOf(refreshed) ?: credential
    }

    private fun credentialOf(account: ProviderAccount): QoderCredential? =
        QoderCredential.parse(account.secret)

    private fun realmOf(account: ProviderAccount): String =
        credentialOf(account)?.realm ?: currentRealm()

    /** 当前区域：由供应商设置 `region` 决定，默认国内。 */
    /**
     * 没有账号时的默认区域。
     *
     * 只用于「还没登录、展示内置模型清单」这一种场合；有账号时一律以
     * 账号凭证里的 realm 为准（见 [regionOf]）。这里固定国内而不是读设置：
     * 引入设置依赖会让 provider 与引擎耦合，收益只是未登录时多列几款国际模型。
     */
    private fun currentRealm(): String = QoderRealm.CN

    private fun toAccount(credential: QoderCredential): ProviderAccount = ProviderAccount(
        providerId = ID,
        uid = credential.uid.ifEmpty { credential.accessToken.take(16) },
        nickname = credential.nickname.ifEmpty { credential.uid.take(8) },
        secret = credential.toSecret(),
    )

    /** 拉用户信息补全昵称/套餐类型；失败返回原凭证。 */
    private fun fetchUserInfo(credential: QoderCredential): QoderCredential {
        val base = QoderRealm.openapi.getValue(QoderRealm.safe(credential.realm))
        val (status, text) = httpGet(
            "$base$PATH_USERINFO",
            mapOf("Accept" to "application/json", "Authorization" to "Bearer ${credential.accessToken}"),
        )
        if (status !in 200..299) return credential
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return credential
        // 上游有时把结果包在 data 里
        val obj = root.obj("data") ?: root
        return credential.copy(
            uid = credential.uid.ifEmpty { obj.str("user_id").ifEmpty { obj.str("uid") } },
            nickname = credential.nickname.ifEmpty {
                obj.str("nickname").ifEmpty { obj.str("name") }
            },
            userType = obj.str("user_type").ifEmpty {
                obj.str("userType").ifEmpty { credential.userType }
            },
            orgId = credential.orgId.ifEmpty { obj.str("organization_id") },
            orgName = credential.orgName.ifEmpty { obj.str("organization_name") },
        )
    }

    /** 用 COSY 签名拉模型清单；需要账号，失败返回空（调用方回退内置目录）。 */
    private fun fetchModels(account: ProviderAccount): List<ProviderModel> {
        val stored = credentialOf(account) ?: return emptyList()
        // 拉清单同样要先确保凭证有效：token 过期时会静默返回空，
        // 界面就把「拉不到」显示成「缓存」，看起来像一直不刷新。
        val credential = ensureFresh(stored)
        val session = sessions.get(credential)
        val url = QoderRealm.gateway.getValue(QoderRealm.safe(credential.realm)) + MODELS_PATH
        val headers = session.headers("", url, modelKey = "", sse = false)
        // 模型清单必须用 GET：实测 POST 会返回 400 "Request method 'POST' not supported"
        val (status, text) = httpGet(url, headers)
        if (status !in 200..299) {
            hooks.onLog("Qoder 模型清单 HTTP $status（${credential.realm}）：${text.take(200)}")
            return emptyList()
        }
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return emptyList()
        // 实测响应是 {"chat":[...]}（不是 data/models）：只认 chat 会一直拿到空列表，
        // 界面上表现为模型「只有内置缓存那 15 款」。
        hooks.onLog("Qoder 模型清单 HTTP $status，顶层键=${root.keySet().joinToString(",")}")
        val list = root.arr("chat")
            ?: root.arr("data")
            ?: root.arr("models")
            ?: return emptyList()
        return list.mapNotNull { el ->
            val o = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            // enable=false 的是上游关了的功能位，列出来也不能用
            val enabled = o.get("enable")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
            if (!enabled) return@mapNotNull null
            // 上游用 is_invisible_to_user 标记「不该出现在可选列表」的条目
            val invisible = o.get("is_invisible_to_user")?.takeIf { it.isJsonPrimitive }?.asBoolean
                ?: o.get("isInvisibleToUser")?.takeIf { it.isJsonPrimitive }?.asBoolean
            if (invisible == true) return@mapNotNull null
            val key = o.str("key").ifEmpty { o.str("model") }
            if (key.isEmpty()) return@mapNotNull null
            // 本地兜底：上游对内部路由条目未必标 is_invisible_to_user。
            // `fast` 是内部快速路由（倍率 0.8），面板的「选择模型」里并不出现，
            // 列出来只会让用户选到不该选的条目。
            if (key.lowercase() in INTERNAL_MODEL_KEYS) return@mapNotNull null
            ProviderModel(
                // 裸 key：引擎与界面会自己加 provider 前缀，这里再带一次会变成
                // `qoder/qoder/auto` 这种重复形态。
                id = key,
                name = o.str("display_name").ifEmpty { o.str("name").ifEmpty { key } },
                extra = mapOf(
                    "rate" to (o.get("price_factor")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0).toString(),
                ),
            )
        }
    }

    private fun resolveModelKey(requested: String, realm: String): String {
        val key = requested.substringAfterLast('/').trim()
        if (key.isEmpty()) return QoderCatalog.defaultKey(realm)
        return key
    }

    /**
     * 构造上游请求体（`agent_chat_generation` 的 JSON）。
     *
     * 上游格式和 OpenAI 不一样：消息放在 `chat_context` 与 `messages` 两处，
     * 模型由 `model_config.key` 决定路由。字段名与官方客户端一致，
     * 少一个可能被上游当成非法请求（418/400）。
     */
    private fun buildUpstreamBody(
        body: JsonObject,
        modelKey: String,
        credential: QoderCredential,
        stream: Boolean,
    ): JsonObject {
        val meta = QoderCatalog.find(modelKey, credential.realm)
        val rawMessages = body.arr("messages").orEmpty().mapNotNull {
            runCatching { it.asJsonObject }.getOrNull()
        }

        // system 提示词：客户端给了就用，没给用最小兜底。
        // ★ 成品实现用的是 "You are a helpful assistant."（12 词），
        //   不是 12KB 的官方 CLI 提示词——后者反而可能与上游期望不符。
        val clientSystem = rawMessages.firstOrNull { it.str("role") == "system" }
            ?.let { flattenContent(it) }.orEmpty()
        val systemText = clientSystem.ifEmpty { DEFAULT_SYSTEM_PROMPT }

        // 消息压平：朴素 {role, content}，但要**保留工具调用相关字段**。
        // 上游 Rust 结构体对 role=tool 的消息强制要求 tool_call_id，assistant 的
        // tool_calls 也不能丢——只搬 role/content 会让多轮工具调用在第二轮直接 400
        // （Failed to deserialize: messages[N]: missing field `tool_call_id`）。
        val flat = rawMessages.filter { it.str("role") != "system" }.map { m ->
            JsonObject().apply {
                addProperty("role", m.str("role").ifEmpty { "user" })
                addProperty("content", flattenContent(m))
                for (key in TOOL_MESSAGE_KEYS) {
                    val v = m.get(key) ?: continue
                    if (!v.isJsonNull) add(key, v)
                }
            }
        }
        val lastUser = flat.lastOrNull { it.str("role") == "user" }?.str("content").orEmpty()

        val modelConfig = JsonObject().apply {
            addProperty("key", modelKey)
            addProperty("display_name", meta?.displayName ?: modelKey)
            addProperty("model", "")
            addProperty("format", "openai")
            addProperty("is_vl", meta?.isVl ?: false)
            addProperty("is_reasoning", meta?.isReasoning ?: false)
            addProperty("api_key", "")
            addProperty("url", "")
            addProperty("source", "system")
            addProperty("max_input_tokens", meta?.maxInput ?: 180000)
        }

        val maxTokens = body.get("max_tokens")?.takeIf { !it.isJsonNull }?.asInt
            ?: body.get("max_completion_tokens")?.takeIf { !it.isJsonNull }?.asInt
            ?: 32768

        val messages = com.google.gson.JsonArray()
        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemText)
        })
        flat.forEach { messages.add(it) }

        return JsonObject().apply {
            // 与成品实现逐字段一致（com.qoder.gateway.QoderRequestBuilder）。
            addProperty("request_id", UUID.randomUUID().toString())
            addProperty("request_set_id", UUID.randomUUID().toString())
            addProperty("chat_record_id", UUID.randomUUID().toString())
            addProperty("session_id", UUID.randomUUID().toString())
            addProperty("stream", stream)
            addProperty("chat_task", "FREE_INPUT")
            addProperty("is_reply", true)
            addProperty("is_retry", false)
            addProperty("source", 1)
            addProperty("version", "3")
            // 成品用 qodercli；改成 qoder 会变形态。
            addProperty("session_type", "qodercli")
            addProperty("agent_id", "agent_common")
            addProperty("task_id", "common")
            addProperty("code_language", "")
            addProperty("chat_prompt", "")
            addProperty(
                "aliyun_user_type",
                credential.userType.ifEmpty { QoderCosySession.DEFAULT_USER_TYPE },
            )
            // 空数组而非 null：null 与官方形态不同。
            add("image_urls", com.google.gson.JsonArray())
            add("model_config", modelConfig)
            add("parameters", JsonObject().apply { addProperty("max_tokens", maxTokens) })
            add("messages", messages)
            // chat_context
            add("chat_context", JsonObject().apply {
                addProperty("chatPrompt", "")
                add("text", JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", lastUser)
                })
                add("features", com.google.gson.JsonArray())
                add("imageUrls", com.google.gson.JsonArray())
                add("extra", JsonObject().apply {
                    add("context", com.google.gson.JsonArray())
                    add("modelConfig", JsonObject().apply {
                        addProperty("is_reasoning", meta?.isReasoning ?: false)
                        addProperty("key", modelKey)
                    })
                    add("originalContent", JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", lastUser)
                    })
                })
            })
            // tools 仅在客户端真的发了时才带；不要塞空数组或内置 14 个
            body.arr("tools")?.let { tools ->
                if (tools.isNotEmpty()) {
                    add("tools", com.google.gson.JsonArray().apply { tools.forEach { add(it) } })
                }
            }
            // 成品实现**没有** business 字段
        }
    }

    /**
     * 空 usage 的 response_meta（官方模板里每条消息都带）。
     *
     * 结构必须完整：只给 {} 或缺字段都可能被上游当成非法消息。
     */
    private fun emptyResponseMeta(): JsonObject = JsonObject().apply {
        addProperty("id", "")
        add("usage", JsonObject().apply {
            addProperty("prompt_tokens", 0)
            addProperty("completion_tokens", 0)
            addProperty("total_tokens", 0)
            add("completion_tokens_details", JsonObject().apply {
                addProperty("reasoning_tokens", 0)
            })
            add("prompt_tokens_details", JsonObject().apply {
                addProperty("cached_tokens", 0)
            })
        })
    }

    /** OpenAI 的 content 可以是字符串，也可以是 [{type,text}] 数组。 */
    private fun flattenContent(message: JsonObject): String {
        val content = message.get("content") ?: return ""
        if (content.isJsonPrimitive) return runCatching { content.asString }.getOrDefault("")
        if (content.isJsonArray) {
            val sb = StringBuilder()
            for (el in content.asJsonArray) {
                val o = runCatching { el.asJsonObject }.getOrNull() ?: continue
                val type = o.str("type")
                if (type == "text" || type.isEmpty()) sb.append(o.str("text"))
            }
            return sb.toString()
        }
        return ""
    }

    /** 非流式时把增量聚合成 OpenAI chat.completion。 */
    private fun aggregate(modelKey: String, content: String): String = JsonObject().apply {
        addProperty("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(20))
        addProperty("object", "chat.completion")
        addProperty("created", nowMillis() / 1000)
        addProperty("model", modelKey)
        add("choices", com.google.gson.JsonArray().apply {
            add(JsonObject().apply {
                addProperty("index", 0)
                add("message", JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", content)
                })
                addProperty("finish_reason", "stop")
            })
        })
    }.toString()

    /**
     * 上游错误转成 OpenAI 风格的 error 体。
     *
     * 上游的错误体结构不固定（有时是 error.message，有时是 errorMessage），
     * 统一抽出来再包一层，客户端才能看到真实原因而不是「未知错误」。
     */
    private fun mapUpstreamError(status: Int, raw: String): String {
        val text = raw.trim()
        val parsed = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        val message = parsed?.let {
            it.obj("error")?.str("message")
                ?: it.str("errorMessage")
                ?: it.str("message")
                ?: it.str("error")
        }.orEmpty().ifEmpty { text.take(300) }
        val body = JsonObject().apply {
            add("error", JsonObject().apply {
                addProperty("message", message.ifEmpty { "上游返回 HTTP $status" })
                addProperty("type", if (status == 401 || status == 403) "authentication_error" else "upstream_error")
                addProperty("code", status)
            })
        }
        return body.toString()
    }

    private fun openPost(url: String, body: String, headers: Map<String, String>): HttpURLConnection {
        val conn = java.net.URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.instanceFollowRedirects = false
        // ★ 必须用**定长**而不是 chunked：签名是对原始 body 字节做 MD5，
        //   chunked 传输会让线上字节带上分块边界，上游验签时拿到的
        //   不一定与我们签名的那串完全一致，表现为 4001 param is invalid。
        //   成品实现（HttpSupport.writeBody）用的就是 setFixedLengthStreamingMode。
        val bytes = body.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        conn.outputStream.use { it.write(bytes) }
        return conn
    }

    private fun httpGet(url: String, headers: Map<String, String>): Pair<Int, String> {
        val conn = java.net.URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        return readResponse(conn)
    }

    private fun httpPost(url: String, body: String, headers: Map<String, String>): Pair<Int, String> {
        val conn = java.net.URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return readResponse(conn)
    }

    private fun readResponse(conn: HttpURLConnection): Pair<Int, String> {
        return try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            status to text
        } catch (e: Exception) {
            0 to (e.message ?: "")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * PKCE verifier：**64 个字符**，取 RFC 7636 允许的 unreserved 字符集。
     *
     * 长度不能想当然：项目里一开始用 32 字节 base64url（43 字符）虽然合规，
     * 但上游授权页会判「参数无效」——它按官方客户端的 64 字符来校验。
     */
    private fun pkceVerifier(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val bytes = ByteArray(64).also { java.security.SecureRandom().nextBytes(it) }
        val sb = StringBuilder(64)
        for (b in bytes) {
            // and 0xFF 把有符号字节转回无符号，再取模，保证索引落在 0..65
            sb.append(alphabet[(b.toInt() and 0xFF) % alphabet.length])
        }
        return sb.toString()
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { arr ->
        java.security.SecureRandom().nextBytes(arr)
    }

    private fun urlEnc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val ID = "qoder"

        /**
         * system 兜底提示词。
         *
         * 成品实现（com.qoder.gateway.QoderConstants.DEFAULT_SYSTEM_PROMPT）用的就是这句，
         * 不是 12KB 的官方 CLI 提示词——后者是客户端自带的，不是协议要求。
         */
        private const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."

        private const val PATH_DEVICE_POLL = "/api/v1/deviceToken/poll"
        private const val PATH_DEVICE_REFRESH = "/api/v1/deviceToken/refresh"
        private const val PATH_JOB_REFRESH = "/api/v1/jobToken/refresh"

        /** 官方桌面端版本（活动平台按它校验身份）。 */
        private const val DESKTOP_CLIENT_VERSION = "0.4.8"

        /** 刷新串行锁：refresh_token 一次有效，并发刷新会互相作废。 */
        private val refreshLock = Any()

        /** 桌面端机器 OS 标识（与 runtime-info 的 linux 分支一致）。 */
        private const val MACHINE_OS = "x86_64_linux"
        private const val PATH_USERINFO = "/api/v1/userinfo"
        private const val PATH_QUOTA = "/api/v2/quota/usage"

        /**
         * 额度候选端点：官方改过多次，且国内/国际不是同一套。
         * 按顺序试，第一个能解析出数字的胜出（结果不缓存，额度查询不频繁）。
         */
        private val QUOTA_PATHS = listOf(
            "/api/v2/quota/usage",
            "/api/v1/quota/usage",
            "/api/v2/quota",
            "/api/v2/user/quota",
            "/api/v2/me/quota",
            "/api/v2/credits/usage",
            "/api/v2/credits",
            "/sash/api/v1/me/quota",
            "/sash/api/v1/me/credits",
            "/sash/api/v1/me/usage",
        )
        // 每日 100 Credits 在 campaign 平台（旧的 daily-check-in 接口多数区域已空）
        private const val PATH_CAMPAIGNS = "/sash/api/v1/me/campaigns"
        private const val PATH_CAMPAIGN_CLAIM = "/sash/api/v1/me/campaigns/%s/claim"

        private const val MODELS_PATH = "/algo/api/v2/model/list?Encode=1"

        /**
         * 内部路由条目：上游会出现在 chat 列表里，但官方面板的「选择模型」没有这些项。
         *
         * 实测对照（2026-11-08，面板 vs 上游 chat 列表）：
         * - 国内：多出 `fast`（0.8x）
         * - 国际：多出 `ultimate`（0.4x）、`efficient`（0.3x）
         * 它们属于服务分级路由，列出来会让用户选到面板里不存在的条目。
         * `performance` 是同族条目，一并排除以防以后出现。
         */
        /**
         * 内部分级路由条目：上游会下发，但官方面板的「选择模型」里不出现，
         * 列出来只会让用户选到不该选的条目。
         */
        /**
         * 消息里必须原样透传的「工具调用」字段。
         *
         * 上游是 Rust 反序列化：role=tool 缺 tool_call_id 直接 400，
         * assistant 的 tool_calls 丢了则下一轮上下文断裂。
         */
        private val TOOL_MESSAGE_KEYS = listOf("tool_call_id", "tool_calls", "name")

        private val INTERNAL_MODEL_KEYS = setOf(
            // 场景/路由位，不是真实模型
            "auto", "fast", "ultimate", "efficient", "performance",
            // Sonus / Cantus 是上游的「场景」条目，不是可选模型
            "smodel", "cmodel",
        )

        /**
         * 上游请求体的顶层字段（对齐官方 baseprompt.json，共 23 个）。
         *
         * 多带一个上游不认识的字段会被判 `4001 param is invalid`——
         * 早先我在这里塞过自定义 `_system_hint`，正是 4001 的来源之一。
         * 单测会锁定这个集合，增删必须同步官方模板。
         */
        val UPSTREAM_BODY_KEYS: Set<String> = setOf(
            "request_id", "request_set_id", "chat_record_id", "stream", "chat_task",
            "chat_context", "image_urls", "is_reply", "is_retry", "session_id",
            "code_language", "source", "version", "chat_prompt", "parameters",
            "aliyun_user_type", "session_type", "agent_id", "task_id",
            "model_config", "messages", "tools", "business",
        )
        private const val CHAT_PATH =
            "/algo/api/v2/service/pro/sse/agent_chat_generation" +
                "?FetchKeys=llm_model_result&AgentId=agent_common&Encode=1"

        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 180_000
        private const val LOGIN_TTL_MS = 10 * 60 * 1000L

        /** 提前多久算「快过期」并主动刷新。 */
        private const val FRESH_SKEW_SECONDS = 300L
        private const val PIPE_SIZE = 16 * 1024
    }
}
