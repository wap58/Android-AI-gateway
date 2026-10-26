package dev.aigw.core.provider.lingxi

/**
 * WPS 灵犀（Lingxi / 灵犀 Claw）的上游端点与默认值。
 *
 * 协议参考自 Magpie 插件 `@kevinfx/magpie-wps-lingxi`（MIT）。
 * 与 Qoder 那套 COSY 签名不同，这里**只需要一个 `wps_sid` cookie**，
 * 没有设备指纹、没有请求签名、没有 UMID 池 —— 复杂度低一个数量级。
 */
internal object LingxiConstants {

    const val BASE = "https://lingxi.kdocs.cn"

    /** 新建会话。返回 `data.session_id`。 */
    const val SESSIONS_URL = "$BASE/api/aigc/v3/assistant/sessions"

    /** 会话内发起补全，SSE 流。 */
    fun completionsUrl(sessionId: String) = "$BASE/api/aigc/v3/assistant/sessions/$sessionId/completions"

    /** 智点余额。 */
    const val CREDITS_URL = "$BASE/api/public/v1/credits/balance"

    /** 网页端模型清单（plans + models 两段）。 */
    const val PLANS_URL = "$BASE/api/aioffice/v1/sessions/plans"

    /** 任务中心列表（含每日签到条目）。 */
    const val TASKS_URL = "$BASE/api/public/v1/tasks"

    /** 每日签到领取。 */
    fun claimUrl(date: String) = "$BASE/api/public/v1/tasks/daily_check_in/claim?date=$date"

    const val REFERER = "$BASE/"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    /** 上游要求的客户端标识头，缺了会被判成非网页端。 */
    const val X_CC_VERSION = "1"
    const val X_CLIENT_TYPE = "lingxi-web"
    const val X_CC_ENTRANCE = "default"


    /** 请求没带模型时用它。 */
    const val DEFAULT_MODEL = "deepseek-v4.1-flash"

    /** 单轮拼接历史的最大字符数，超出截断防止把上下文撑爆。 */
    const val MAX_PROMPT_CHARS = 60_000
}

/**
 * 凭证：灵犀整个登录态就是一个 cookie 串。
 *
 * 用户在网页 F12 里复制 `wps_sid` 的值即可，不需要别的东西。
 */
internal data class LingxiCredential(
    /** 完整 Cookie 头（形如 `wps_sid=xxx`，也允许用户粘整串 cookie）。 */
    val cookie: String,
    /** 展示用的账号名（默认取 sid 前 8 位）。 */
    val nickname: String,
    /**
     * WPS 账号的稳定 ID（cookie 里的 `uid` 字段，如 `321544567`）。
     *
     * 用作账号池的 uid：sid 每次登录都会变，只有这个数字不变，
     * 否则重复登录会不断新增账号条目。
     */
    val accountId: String = "",
) {
    /**
     * 备用 sid：同一账号在另一个域上的 `wps_sid`。
     *
     * 实测两个域各有一份、且只有其中一份能通过鉴权。用户可能先登录到失效那份，
     * 所以保存两份，401 时切换重试。没有则在 [cookie] 里找 `wps_sid_alt`。
     */
    /**
     * 业务接口用的 cookie（wps.cn 域）。
     *
     * 实测：wps.cn 域的会话能正常拉模型列表 / 签到 / 查额度，
     * 而 kdocs 域的那份在这些接口上会被拒（401）。
     */
    val bizCookie: String by lazy { split().first.ifEmpty { cookie } }

    /**
     * 对话用的 cookie（kdocs 域）。
     *
     * 实测：kdocs 域的会话对话走的是**专业版模型**；
     * 用 wps.cn 那份对话会退化成通用 AI（回 "我是 WPS AI"）。
     */
    val chatCookie: String by lazy { split().second.ifEmpty { cookie } }

    private fun split(): Pair<String, String> {
        // 存储格式：`<biz>\u0001<chat>`（App 侧拼接），也兼容旧的单份格式
        if (!cookie.contains('\u0001')) return "" to ""
        val parts = cookie.split('\u0001', limit = 2)
        return parts[0] to parts.getOrElse(1) { "" }
    }

    /** 所有可尝试的 cookie 变体：业务 + 对话 + 原始。 */
    fun cookieVariants(): List<String> =
        listOf(bizCookie, chatCookie, cookie)
            .filter { it.isNotBlank() }
            .distinct()

    companion object {
        private fun extractAlt(cookie: String): String? {
            val m = Regex("""(?:^|;\s*)wps_sid_alt=([^;\s]+)""").find(cookie) ?: return null
            val alt = m.groupValues[1]
            if (alt.isEmpty()) return null
            // 把主 sid 换掉，其余 cookie 保持
            return Regex("""(?:^|;\s*)wps_sid=[^;\s]*""").replace(cookie) { " wps_sid=$alt" }.trim()
        }

        /**
         * 解析粘贴进来的凭证。
         *
         * 宽容处理三种常见粘贴形态：
         * - 只粘 `wps_sid` 的值
         * - `wps_sid=xxxx`
         * - 整串 cookie（含多个 `k=v;`）
         */
        fun parse(raw: String): LingxiCredential? {
            val text = raw.trim()
            if (text.isEmpty()) return null

            val cookie = when {
                // 整串 cookie 里已经带了 wps_sid
                text.contains("wps_sid=") -> text
                // 只有 key=value 形态但不是 wps_sid
                text.contains("=") && !text.contains(";") -> "wps_sid=$text"
                // 裸 sid 值
                else -> "wps_sid=$text"
            }

            val sid = Regex("""wps_sid=([^;\s]+)""").find(cookie)?.groupValues?.get(1)
                ?: return null
            if (sid.length < 8) return null

            // 昵称取对话用的那份 sid（kdocs 域），便于区分不同会话等级
            val chatPart = cookie.substringAfterLast('\u0001', cookie)
            val chatSid = Regex("""wps_sid=([^;\s]+)""").find(chatPart)?.groupValues?.get(1)
                ?: sid
            // uid cookie 才是稳定标识（形如 321544567），优先用它；
            // 取不到时退回 sid 前缀（至少能用，只是重复登录会新增条目）。
            val accountId = Regex("""(?:^|;\s*)uid=(\d+)""").find(cookie)
                ?.groupValues?.get(1)
                .orEmpty()

            return LingxiCredential(
                cookie = cookie,
                nickname = chatSid.take(8),
                accountId = accountId,
            )
        }
    }
}
