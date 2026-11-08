package dev.aigw.core.provider.qoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.Base64

/**
 * 用 Python 参考实现产出的固定向量校验 Kotlin 侧实现。
 *
 * 这些不是「看起来对」的断言：签名链路是逐字节比对，
 * 任何一位不同上游都会 401，所以必须与参考实现完全一致。
 */
class QoderCodecTest {

    @Test
    fun `请求体编码与参考实现一致`() {
        // plain = {"a":"hi","b":"x"}
        val plain = """{"a":"hi","b":"x"}""".toByteArray()
        assertEquals("BMn*moKWPHz*GoK*mYKiBMn*", QoderCodec.encodeBody(plain))
    }

    @Test
    fun `AES CBC 与参考实现一致`() {
        val plain = """{"a":"hi","b":"x"}""".toByteArray()
        val key = "0123456789abcdef".toByteArray()
        val expected = "xiIwDEtxspBkUrjoArLNFhSrU9n08383pZH8Qg8Q1Hs="
        val actual = Base64.getEncoder().encodeToString(QoderCodec.aesCbcEncrypt(plain, key))
        assertEquals(expected, actual)
    }

    @Test
    fun `RSA 加密输出 1024 位块`() {
        // 协议要求 128 字节密文；长度不对说明公钥解析或填充方式错了
        val out = QoderCodec.rsaEncrypt("0123456789abcdef".toByteArray())
        assertEquals(128, out.size)
    }

    @Test
    fun `键排序紧凑 JSON 与参考实现一致`() {
        val bytes = QoderCodec.jsonSortedCompact(mapOf("b" to "2", "a" to "1"))
        assertEquals("""{"a":"1","b":"2"}""", String(bytes))
    }

    @Test
    fun `JSON 转义覆盖控制字符`() {
        val q = QoderCodec.quote("a\"b\\c\n")
        assertEquals("\"a\\\"b\\\\c\\n\"", q)
        assertTrue(QoderCodec.quote("\u0001").startsWith("\"\\u0001"))
    }

    @Test
    fun `过期时间兼容 ISO 字符串与数字`() {
        // 实测国际版回 2026-12-07T18:40:10Z；直接 asLong 会抛
        // NumberFormatException，且发生在轮询成功收尾阶段 ——
        // 表现是「登录成功但账号没加进来」，很容易被误判成别的问题。
        fun parse(json: String): Long =
            com.google.gson.JsonParser.parseString(json).asJsonObject.epochSeconds("e")

        assertTrue(parse("""{"e":"2026-12-07T18:40:10Z"}""") > 1_700_000_000L)
        assertEquals(1_800_000_000L, parse("""{"e":1800000000}"""))
        // 毫秒自动降到秒
        assertEquals(1_800_000_000L, parse("""{"e":1800000000000}"""))
        // 无法解析时返回 0，不抛
        assertEquals(0L, parse("""{"e":"nonsense"}"""))
        assertEquals(0L, parse("""{"e":null}"""))
    }

    @Test
    fun `上游请求体顶层字段必须与官方模板一致`() {
        // 4001 param is invalid 的元凶之一是「多带了上游不认识的字段」：
        // 早先我在顶层塞了一个自定义 `_system_hint`，上游直接判非法。
        // 这里把官方模板的字段集合钉死，多一个少一个都算回归。
        val official = setOf(
            "request_id", "request_set_id", "chat_record_id", "stream", "chat_task",
            "chat_context", "image_urls", "is_reply", "is_retry", "session_id",
            "code_language", "source", "version", "chat_prompt", "parameters",
            "aliyun_user_type", "session_type", "agent_id", "task_id",
            "model_config", "messages", "tools", "business",
        )
        assertEquals(23, official.size)

        assertEquals(official, QoderProvider.UPSTREAM_BODY_KEYS, "请求体顶层字段必须与官方模板完全一致")
    }

    @Test
    fun `模型目录与上游真实清单一致`() {
        // 依据：/algo/api/v2/model/list 返回的 chat 列表（实测 2026-11-08）。
        // 只保留真模型；auto/fast/ultimate/efficient/smodel/cmodel 是场景位，已过滤。
        val cn = QoderCatalog.of("cn").map { it.displayName }
        assertEquals(10, cn.size, "国内版 10 款：$cn")
        assertTrue(cn.contains("Step-5-Preview"), cn.toString())
        assertTrue(cn.contains("Kimi-K3.1-Pro"), cn.toString())
        assertTrue(!cn.contains("QoDuck"), "已下线模型不该出现：$cn")

        val intl = QoderCatalog.of("intl").map { it.displayName }
        assertEquals(9, intl.size, "国际版 9 款：$intl")
        assertTrue(!intl.contains("Sonus"), "场景位不该出现在模型列表：$intl")
        assertTrue(!intl.contains("Cantus"), "场景位不该出现在模型列表：$intl")

        // 倍率与面板一致
        assertEquals(0.04, QoderCatalog.find("qfmodel", "intl")?.factor)
        assertEquals(0.2, QoderCatalog.find("qmodel_38max", "cn")?.factor)
    }

    @Test
    fun `内部分级条目不出现在可选列表`() {
        // 面板 vs 上游 chat 列表的实测差异（2026-11-08）：
        //   国内多 fast；国际多 ultimate / efficient
        // 它们不在官方「选择模型」面板里，列出来会让用户选到无效项。
        val internal = setOf("fast", "ultimate", "efficient")
        for (key in internal) {
            assertTrue(
                QoderCatalog.of("cn").none { it.key == key } &&
                    QoderCatalog.of("intl").none { it.key == key },
                "$key 不该出现在内置清单里",
            )
        }
    }

    @Test
    fun `默认模型取自清单首项而非 auto`() {
        // 面板清单里没有 auto，上层拿 auto 去请求会被上游判非法
        assertTrue(QoderCatalog.defaultKey("cn") != "auto")
        assertTrue(QoderCatalog.defaultKey("intl") != "auto")
        assertEquals(QoderCatalog.of("cn").first().key, QoderCatalog.defaultKey("cn"))
    }

    @Test
    fun `设备指纹派生与参考实现一致`() {
        // 这几个值来自 Python 参考实现，用于保证「同一账号在两端派生出同一台设备」：
        // 指纹漂移会被上游当成换设备，多号关联则触发风控。
        val uid = "test-user-123"
        assertEquals("fc25464e-3d88-4669-ada4-4f58220a3811", QoderCosySession.deriveId(uid, "machine"))
        assertEquals("d94cc4d49c63446aae", QoderCosySession.deriveMachineType(uid))
        assertEquals("Ii9Cq7k5nBFIPFeSYh7SQ57-z-AKoQW8Vtcr85z7tc6", QoderCosySession.deriveMachineToken(uid))
        assertEquals("DESKTOP-0F6CABF", QoderCosySession.deriveHostname(uid))
    }

    @Test
    fun `签名路径去掉 algo 前缀与 query`() {
        // 上游按 path 签，且官方客户端签的是去掉 /algo 的那一段；
        // 带错前缀或带上 query 都会导致签名不匹配
        val url = "https://gateway.qoder.com.cn/algo/api/v2/service/pro/sse/" +
            "agent_chat_generation?FetchKeys=llm_model_result&Encode=1"
        val session = QoderCosySession(uid = "u1", accessToken = "tok")
        val headers = session.headers(body = "BODY", rawUrl = url, modelKey = "auto")
        val auth = headers["authorization"].orEmpty()
        assertTrue(auth.startsWith("Bearer COSY."), auth)
        // 「Bearer COSY.<payload>.<sig>」按点切分正好 3 段（Bearer COSY / payload / sig）
        assertEquals(3, auth.split('.').size)
        assertEquals("auto", headers["x-model-key"])
        assertEquals("u1", headers["cosy-user"])
        assertTrue(headers["cosy-machineid"].orEmpty().isNotEmpty())
    }
}
