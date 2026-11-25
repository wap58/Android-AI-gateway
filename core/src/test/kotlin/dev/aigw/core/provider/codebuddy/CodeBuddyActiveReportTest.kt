package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 活跃上报的事件体形状。
 *
 * 这几条是**抓包实测得出的硬约束**，改坏了活跃地图就不亮、而且不会有任何报错
 * （上游回 200 然后静默丢弃）——所以必须锁住。
 * 参照：反代 buddy2api `internal/upstream/growth.go` 的 `chatRequestEvent`
 * （公开上游 https://github.com/EchoPing07/Buddy-2API-Go，MIT）。
 */
class CodeBuddyActiveReportTest {

    /** 捕获实际发出的 body，供断言。 */
    private class Capture {
        val bodies = ArrayList<String>()
        var status = 200
        var reply = """{"code":0}"""
        fun post(path: String, body: String): Pair<Int, String> {
            if (path != CodeBuddyActiveReport.PATH_REPORT) return 404 to ""
            bodies.add(body)
            return status to reply
        }
    }

    private fun reporter(capture: Capture, uid: String = "u-123") = CodeBuddyActiveReport(
        uid = uid,
        nickname = "tester",
        post = capture::post,
        gapMillis = 0,
        now = { 1_700_000_000_000L },
    )

    @Test
    fun `body 是数组单元素且 userId 必填`() {
        val cap = Capture()
        reporter(cap).report(1)

        val arr = JsonParser.parseString(cap.bodies.single()).asJsonArray
        assertEquals(1, arr.size(), "body 必须是数组单元素，裸对象会被上游拒绝")
        val ev = arr[0].asJsonObject
        // userId 缺失时上游回 200 但静默丢弃，连登不动——这是最容易踩的坑
        assertEquals("u-123", ev.get("userId").asString)
        assertEquals("chat_request_send", ev.get("eventCode").asString)
    }

    @Test
    fun `同一天多条上报共用同一个 conversationId`() {
        val cap = Capture()
        reporter(cap).report(3)

        val convIds = cap.bodies.map {
            JsonParser.parseString(it).asJsonArray[0].asJsonObject.get("conversationId").asString
        }.toSet()
        assertEquals(1, convIds.size, "同会话多轮才像真实对话，多个会话连发是脚本特征")

        val reqIds = cap.bodies.map {
            JsonParser.parseString(it).asJsonArray[0].asJsonObject.get("requestId").asString
        }.toSet()
        assertEquals(3, reqIds.size, "每轮 requestId 必须不同")
    }

    @Test
    fun `中途失败立即停止并带回已发条数`() {
        val cap = Capture()
        cap.status = 500
        val r = reporter(cap).report(5)

        assertEquals(0, r.sent)
        assertTrue(!r.full)
        assertTrue(r.error.contains("500"))
    }

    @Test
    fun `发满时为 full`() {
        val cap = Capture()
        val r = reporter(cap).report(2)
        assertEquals(2, r.sent)
        assertTrue(r.full)
    }

    @Test
    fun `默认会话 ID 按日期派生可复现`() {
        val a = CodeBuddyActiveReport.defaultConversationId(1_700_000_000_000L)
        val b = CodeBuddyActiveReport.defaultConversationId(1_700_000_000_000L + 3_600_000L)
        assertEquals(a, b, "同一天内重启不应换会话，否则会被看成多个新会话各连发")
        assertTrue(a.startsWith("tyd-"))
    }
}
