package dev.aigw.core.provider.agnes

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.RegionAwareSupport
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.HttpSupport

/**
 * Agnes AI 供应商。
 *
 * 上游是**标准 OpenAI 兼容**接口，所以这里做纯透传：
 * 请求体原样转发，响应流原样返回，不做任何协议转换 ——
 * 这类上游最省事也最不容易出问题（对比灵犀那种私有协议）。
 *
 * 区域：国内 `cn` / 国际 `global`，两站的 Key 不通用，各自独立。
 */
class AgnesProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider, RegionAwareSupport {

    override val id: String = ID
    override val displayName: String = "Agnes"
    override val authKind: AuthKind = AuthKind.NONE

    /** 无额度可查、无签到，所以能力集为空。 */
    override val capabilities: Set<dev.aigw.core.provider.ProviderCapability> = emptySet()

    override fun hosts(): List<String> =
        listOf("api.agnes-ai.cn", "apihub.agnes-ai.com", "agnes-ai.cn", "agnes-ai.com")

    // ------------------------------------------------------------------ 区域

    override fun regions(): List<String> =
        listOf(AgnesConstants.REGION_CN, AgnesConstants.REGION_GLOBAL)

    override fun regionOf(account: ProviderAccount): String? =
        AgnesCredential.parse(account.secret)?.region

    // ------------------------------------------------------------------ 凭证

    override fun importCredentials(raw: String): ProviderAccount? {
        val cred = AgnesCredential.parse(raw) ?: return null
        // uid 用「区域 + key 指纹」：同一个 key 在同一区域只会有一条账号，
        // 不同区域的两把 key 互不覆盖。
        val uid = "${cred.region}-${cred.apiKey.takeLast(6)}"
        // 用 Gson 构造 secret：手拼字符串遇到 key 里含引号/反斜杠会产出非法 JSON
        val secret = com.google.gson.JsonObject().apply {
            addProperty("apiKey", cred.apiKey)
            addProperty("region", cred.region)
        }.toString()
        return ProviderAccount(
            providerId = ID,
            uid = uid,
            nickname = "${AgnesConstants.labelOf(cred.region)} ${cred.apiKey.takeLast(4)}",
            secret = secret,
        )
    }

    private fun credentialOf(account: ProviderAccount): AgnesCredential? =
        AgnesCredential.parse(account.secret)

    // ------------------------------------------------------------------ 模型

    @Volatile
    private var cached: Map<String, List<ProviderModel>> = emptyMap()

    override fun invalidateModelCache() {
        cached = emptyMap()
    }

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        if (account == null) {
            return ProviderModelCatalogView(builtinModels(), fromFallback = true, error = "")
        }

        val cred = credentialOf(account)
            ?: return ProviderModelCatalogView(builtinModels(), fromFallback = true, error = "凭证无法解析")

        cached[cred.region]?.let {
            return ProviderModelCatalogView(it, fromFallback = false, error = "")
        }

        // 尝试实时拉一次；失败就用内置清单（不报错，因为兜底本身可用）
        val fetched = runCatching { fetchModels(cred) }.getOrNull().orEmpty()
        return if (fetched.isEmpty()) {
            ProviderModelCatalogView(builtinModels(), fromFallback = true, error = "")
        } else {
            cached = cached + (cred.region to fetched)
            ProviderModelCatalogView(fetched, fromFallback = false, error = "")
        }
    }

    private fun builtinModels(): List<ProviderModel> =
        AgnesConstants.MODELS.map { ProviderModel(id = it, name = it) }

    private fun fetchModels(cred: AgnesCredential): List<ProviderModel> {
        val conn = HttpSupport.open(
            url = "${AgnesConstants.baseOf(cred.region)}/models",
            method = "GET",
            headers = mapOf(
                "Authorization" to "Bearer ${cred.apiKey}",
                "Accept" to "application/json",
            ),
            body = null,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        if (conn.responseCode !in 200..299) {
            HttpSupport.release(conn, false)
            return emptyList()
        }
        val (text, fullyRead) = HttpSupport.readLimitedTracked(conn.inputStream, 2 * 1024 * 1024)
        HttpSupport.release(conn, fullyRead)

        val root = JsonParser.parseString(text).asJsonObject
        val data = root.getAsJsonArray("data") ?: return emptyList()

        // ★ 按白名单过滤：上游返回的 9 个里只有一个能真正对话，
        //   其余是文生图/文生视频（接口不同）或需付费的 pro 系。
        //   不过滤的话列表里全是选了也调不通的模型。
        return data.mapNotNull { el ->
            val o = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val mid = o.get("id")?.asString?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (mid !in AgnesConstants.ALLOWED_MODELS) return@mapNotNull null
            ProviderModel(id = mid, name = mid)
        }
    }

    override fun resolveModel(requested: String): String {
        val m = requested.substringAfterLast('/').trim()
        return m.ifEmpty { AgnesConstants.DEFAULT_MODEL }
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val cred = credentialOf(account) ?: return FailedChatCall(401, "API Key 无法解析")

        // ★ 纯透传：请求体是标准 OpenAI 格式，原样发即可
        val conn = try {
            HttpSupport.open(
                url = "${AgnesConstants.baseOf(cred.region)}/chat/completions",
                method = "POST",
                headers = mapOf(
                    "Authorization" to "Bearer ${cred.apiKey}",
                    "Content-Type" to "application/json",
                    "Accept" to "text/event-stream",
                ),
                body = openAiBody,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                allowCompression = false,
            )
        } catch (e: Exception) {
            return FailedChatCall(0, e.message ?: "连接失败")
        }

        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, e.message ?: "连接失败")
        }

        if (status !in 200..299) {
            val (body, fullyRead) = runCatching { HttpSupport.readLimitedTracked(conn.errorStream) }
                .getOrDefault("" to false)
            HttpSupport.release(conn, fullyRead)
            return FailedChatCall(status, body)
        }

        // 响应也是标准 OpenAI SSE，直接交给网关透传
        return StreamingChatCall(status, conn.inputStream) { conn.disconnect() }
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = runCatching {
            val o = JsonParser.parseString(body).asJsonObject
            o.getAsJsonObject("error")?.get("message")?.asString
                ?: o.get("message")?.asString
        }.getOrNull().orEmpty()

        return when {
            status == 401 || status == 403 ->
                UpstreamError(ErrorKind.SESSION_DEAD, message.ifEmpty { "API Key 无效或已失效" })
            // 免费层是每分钟 10 次，触发限流时是软失败，等一会儿就好
            status == 429 ->
                UpstreamError(
                    ErrorKind.SOFT_RATE,
                    message.ifEmpty { "触发限流（每分钟 ${AgnesConstants.RATE_LIMIT_PER_MINUTE} 次），请稍后再试" },
                )
            status in 500..599 ->
                UpstreamError(ErrorKind.SERVER, message.ifEmpty { "上游 $status" })
            else -> UpstreamError(ErrorKind.CLIENT, message.ifEmpty { "HTTP $status" })
        }
    }

    companion object {
        const val ID = "agnes"

        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 300_000
    }
}
