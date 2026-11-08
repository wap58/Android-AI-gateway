package dev.aigw.core.provider.qoder

/**
 * 官方 Qoder CLI 的 system 提示词（约 12KB）。
 *
 * ## 为什么要内置这份提示词
 *
 * 上游对 `messages[0]`（system）做了校验：**content 不能为空**。
 * 客户端（如 Minis）发来的 OpenAI 请求大多不带 system 消息，
 * 直接构造就会得到 `{"role":"system","content":""}`，
 * 上游回 `4001 param is invalid`。
 *
 * 参考实现的做法是：从官方模板 `baseprompt.json` 取 system 消息作为骨架，
 * 客户端没给 system 时就用模板里这份。这里同样内置一份（内容取自模板）。
 *
 * 加载失败时退回一句最小提示词——宁可内容单薄，也不要发空 content。
 */
internal object QoderSystemPrompt {

    private const val RESOURCE = "/qoder_system_prompt.txt"

    /** 最小兜底：资源读不到时用，保证 content 非空。 */
    private const val FALLBACK =
        "You are Qoder, an interactive CLI tool that helps users with software engineering tasks. " +
            "If the user did not specify, respond in the language the user used."

    @Volatile
    private var cached: String? = null

    fun text(): String {
        cached?.let { return it }
        val loaded = runCatching {
            QoderSystemPrompt::class.java.getResourceAsStream(RESOURCE)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val value = loaded ?: FALLBACK
        cached = value
        return value
    }
}
