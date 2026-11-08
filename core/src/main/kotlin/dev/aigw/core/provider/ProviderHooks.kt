package dev.aigw.core.provider

/**
 * 网关注入给 provider 的回调。
 *
 * provider 在解析上游流时用它上报计费回报与账号级错误，避免 provider 直接依赖账号池。
 */
class ProviderHooks(
    /** 流内计费回报（如 Trae 的 `ide_credits`）。 */
    val onBilling: (providerId: String, uid: String, credits: Long) -> Unit = { _, _, _ -> },
    /** 流内业务错误，网关据此禁用凭证失效的账号。 */
    val onAccountError: (providerId: String, uid: String, error: UpstreamError) -> Unit = { _, _, _ -> },
    /** provider 内部刷新了账号凭证时（如 Antigravity 轮换 Token），通知账号池持久化。 */
    val onAccountUpdated: (account: ProviderAccount) -> Unit = {},
    val onLog: (String) -> Unit = {},
    /** 详细日志：provider 上报真实发给上游的内容（如 Antigravity 的 envelope），仅开启详细日志时生效。 */
    val onVerbose: (tag: String, text: String) -> Unit = { _, _ -> },
    /**
     * 把原始请求/响应落到磁盘，供离线比对。
     *
     * 排查「上游拒收但字段看起来全对」这类问题时，唯一可靠的依据是
     * 真实发出的字节；应用内日志有环形缓冲、logcat 抓不到 stderr，
     * 所以需要一个由宿主（app 层）提供可写目录的落盘出口。
     */
)
