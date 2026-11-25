package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 活跃地图 / 连登点亮：对话活跃上报（`chat_request_send`）。
 *
 * ## 移植来源
 *
 * 照抄反代模块 buddy2api 的 `internal/upstream/growth.go` → `ReportChatActivity`。
 * 那边是抓包实测过的完整 36 字段形状，注释明确写着「勿精简，防上游加严校验」。
 *
 * ## 为什么要单独一套
 *
 * 任务中心的事件链（[CodeBuddyAutoTask.desktopChatSequence]）是**桌面指纹 6 事件**，
 * 它点亮的是「任务中心里的 chat_5」；而**活跃地图/连登**的判据是同一端点
 * `POST {copilot}/v2/report` 上的**单条 `chat_request_send` + userId 必填**。
 * 两者形状不同、判据不同，混用会两头都点不亮。
 *
 * 关键约束（反代实测结论）：
 *  - body 是**数组单元素** `[{...}]`，不是裸对象；
 *  - `userId` 必填（= token 里的 UID）：缺失时服务端回 200 但**静默丢弃**，连登不动；
 *  - 同一天多条上报**共用一个 conversationId**，各条 requestId 独立（模拟同会话多轮）；
 *  - 条与条之间留 [gapMillis]（默认 1.5s），秒发会被判定人机。
 *
 * 一条上报同时做两件事：点亮当日活跃格 + 累加 chat_5 进度（领养前置需 5 次对话）。
 */
internal class CodeBuddyActiveReport(
    private val uid: String,
    private val nickname: String,
    /** 走任务域：`POST {chatBase}/v2/report`，头用 billingHeaders。 */
    private val post: (path: String, body: String) -> Pair<Int, String>,
    private val gapMillis: Long = 1500,
    private val now: () -> Long = System::currentTimeMillis,
) {

    data class Result(val sent: Int, val total: Int, val error: String = "") {
        val full: Boolean get() = sent == total && total > 0
        fun describe(): String = when {
            error.isNotEmpty() -> "活跃上报中断（$sent/$total）：$error"
            else -> "活跃上报 $sent 条（同会话）"
        }
    }

    /**
     * 上报 [count] 条活跃事件。
     *
     * 中途失败立即停（连发失败再重试只会加重风控），把已发条数带回去交给调用方判断。
     */
    fun report(count: Int, conversationId: String = defaultConversationId()): Result {
        val total = count.coerceIn(1, MAX_REPORTS)
        var sent = 0
        for (i in 1..total) {
            val requestId = "$conversationId-r$i"
            val (status, text) = runCatching { post(PATH_REPORT, body(conversationId, requestId)) }
                .getOrElse { e -> return Result(sent, total, e.message ?: e.javaClass.simpleName) }
            if (status !in 200..299) {
                return Result(sent, total, "HTTP $status ${text.take(80)}")
            }
            sent++
            if (i < total) runCatching { Thread.sleep(gapMillis) }
        }
        return Result(sent, total)
    }

    /** 单条事件的完整 36 字段形状（字段名与官方客户端一致，camelCase）。 */
    private fun body(conversationId: String, requestId: String): String {
        val ts = now()
        val ev = JsonObject().apply {
            addProperty("eventCode", "chat_request_send")
            addProperty("timestamp", ts)
            addProperty("reportDelay", 0)
            addProperty("mode", "craft")
            addProperty("conversationId", conversationId)
            addProperty("requestId", requestId)
            addProperty("inputLength", 12)
            addProperty("requestModelId", "deepseek-v4-flash")
            addProperty("requestModelName", "DeepSeek V4 Flash")
            addProperty("isPlan", false)
            addProperty("isAutoExecuteTerminal", false)
            addProperty("isAutoModify", false)
            addProperty("codebaseEnable", false)
            addProperty("maxToken", 0)
            addProperty("maxSteps", 0)
            addProperty("temperature", 0)
            addProperty("maxRetries", 0)
            add("mentionContexts", JsonArray())
            add("knowledgeId", JsonArray())
            add("knowledgeName", JsonArray())
            addProperty("codebaseId", "")
            addProperty("mentionContextCount", 0)
            addProperty("command", "")
            addProperty("expertId", "")
            addProperty("recommendId", "")
            addProperty("skillId", "")
            addProperty("skillCount", 0)
            addProperty("totalCount", 0)
            addProperty("fileUri", "")
            addProperty("presentAt", ts)
            addProperty("traceId", "")
            addProperty("rootRequestId", conversationId)
            addProperty("parentConversationId", conversationId)
            addProperty("agentName", "default")
            addProperty("agentType", "conversation")
            // ★ 必填：缺失时上游回 200 但静默丢弃，连登不动
            addProperty("userId", uid)
        }
        // 顺带把昵称带上（部分上游版本用它做归属校验，缺了不至于失败但更贴近真实客户端）
        if (nickname.isNotEmpty()) ev.addProperty("userNickname", nickname)
        return JsonArray().apply { add(ev) }.toString()
    }

    companion object {
        const val PATH_REPORT = "/v2/report"

        /**
         * 每日上报条数上限。
         *
         * 反代默认 10（1~10 可配），因为一条上报同时累加 chat_5 进度（门槛 5 次）。
         * 这里取 10 与反代对齐：多出的几条用于把活跃格 score 拉高，不至于被判为「敷衍签到」。
         */
        const val MAX_REPORTS = 10

        /**
         * 默认会话 ID：按**上海日期**确定性派生。
         *
         * 用日期而不是毫秒时间戳的原因：App 重启后内存日锁会丢，同一天可能再跑一轮。
         * 若每次都用新会话，上游会看到「同一天多个新会话各连发 10 条」——这是典型
         * 脚本特征；按日期派生则重跑时仍是同一会话，只是多轮对话，形态自然得多。
         */
        fun defaultConversationId(now: Long = System.currentTimeMillis()): String {
            val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            return "tyd-" + fmt.format(java.util.Date(now))
        }
    }
}
