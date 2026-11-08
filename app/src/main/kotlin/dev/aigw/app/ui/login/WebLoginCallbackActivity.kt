package dev.aigw.app.ui.login

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 内置 WebView 版「授权码回调」登录页。
 *
 * ## 为什么需要它
 *
 * 系统浏览器登录依赖上游把 `authorization_code` 回推到一个**本机回环地址**
 * （`http://127.0.0.1:<port>/auth/callback`）。实测（2026-11-06）：
 * - Alook：完全禁止访问 127.0.0.1（连 `127.0.0.1 的 8888 端口` 都打不开），必然空白页
 * - 系统自带浏览器：同样跳不过去
 * - Edge / 360 极速：允许，所以只有它们能用
 *
 * 本页面把整条链路搬进 App 内部：WebView 自己加载授权页，登录成功后
 * 上游执行 `window.location.assign("http://127.0.0.1:.../auth/callback?code=…")`，
 * 这个请求由 [WebViewClient.shouldOverrideUrlLoading] 直接截下，**不真的发出**
 * （手机上没有监听端口，发出去只会失败），从 URL 里取到 code 交给调用方换 token。
 *
 * 因此这条路不依赖任何浏览器的回环访问策略，也不需要临时监听本地端口。
 *
 * ## 与浏览器方式的取舍
 *
 * 两种入口都保留：多账号场景下，用不同浏览器可以各自隔离 Cookie；
 * 而遇到浏览器拦截回环地址时，内置 WebView 是唯一能走通的路径。
 */
class WebLoginCallbackActivity : androidx.activity.ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var hintView: TextView

    /** 回调地址前缀（scheme + host + 端口 + 路径），用于识别拦截。 */
    private var callbackPrefix: String = ""

    /** 是否已经拿到结果（避免重复回调）。 */
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startUrl = intent.getStringExtra(EXTRA_START_URL).orEmpty()
        callbackPrefix = intent.getStringExtra(EXTRA_CALLBACK_PREFIX).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "登录" }
        val passive = intent.getBooleanExtra(EXTRA_PASSIVE, false)

        if (startUrl.isEmpty() || callbackPrefix.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // targetSdk 35+ 在 Android 15 上强制 edge-to-edge，内容会画到状态栏下面，
        // 标题栏会被状态栏图标压住。这里按系统栏真实高度补内边距，
        // 而不是写死 dp——不同机型的刘海高度不一样。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(40, 28, 24, 16)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(0xFF222222.toInt())
        }
        bar.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val closeView = TextView(this).apply {
            text = "关闭"
            textSize = 15f
            setTextColor(0xFF1A73E8.toInt())
            setPadding(20, 10, 10, 10)
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        bar.addView(closeView)
        root.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        hintView = TextView(this).apply {
            text = if (passive) {
                "完成授权后点右上角「关闭」，返回后会自动检查登录结果"
            } else {
                "登录成功后会自动完成，无需手动操作"
            }
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(40, 0, 40, 16)
        }
        root.addView(
            hintView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // 桌面 UA：授权页的「客户端授权」流程按桌面客户端设计，
            // 手机 UA 会被导向通用版页面，拿不到客户端授权入口。
            settings.userAgentString = DESKTOP_UA
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean = intercept(request?.url?.toString())

            @Deprecated("兼容旧 WebView 实现")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                intercept(url)

            /**
             * 未知 scheme 的回调兜底。
             *
             * WebView 对 `qoder-work-cn://` 这类它不认识的 scheme，
             * 可能**不**回调 shouldOverrideUrlLoading，而是直接丢给系统，
             * 结果是页面显示「网页无法打开 · ERR_UNKNOWN_URL_SCHEME」，
             * 登录看起来失败了。这里在页面开始加载时也检查一次。
             */
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                intercept(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 有些实现不用 location.assign，而是把回调地址渲染成链接让人点；
                // 这里兜底再检查一次当前 URL。
                intercept(url)
            }
        }

        // 自定义 scheme 还可能通过 window.open / intent 触发，ChromeClient 也会收到
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                // 授权页有时用 alert 报错，把它转成可读提示而不是系统弹窗
                if (!message.isNullOrBlank()) hintView.text = message
                result?.confirm()
                return true
            }
        }

        root.addView(
            webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(
            FrameLayout(this).apply {
                addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            },
        )

        webView.loadUrl(startUrl)
    }

    /**
     * 命中回调地址就截下并结束；返回 true 表示已消费该导航。
     *
     * 不真的让请求发出去：本机没有监听端口，真发只会得到「无法连接」白页，
     * 而且我们需要的只是 URL 里的 query。
     */
    private fun intercept(url: String?): Boolean {
        val target = url ?: return false
        if (finished) return true
        if (!matchesCallback(target)) return false

        finished = true
        hintView.text = "登录成功，正在添加账号…"
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_RESULT_URL, target),
        )
        finish()
        return true
    }

    /**
     * 回调地址匹配。
     *
     * 除了标准前缀，还要容忍两处真实差异：
     * - Trae 的回调参数可能带尾斜杠或多余分隔符
     * - 部分实现把参数拼在 URL 哈希段（`#...?code=...`）而不是查询串
     * 所以按「scheme + host + 端口 + 路径」的实质内容比较，而不是死磕字符串前缀。
     */
    private fun matchesCallback(target: String): Boolean {
        val prefix = callbackPrefix
        if (target.startsWith(prefix)) return true
        // 允许尾斜杠差异
        if (target.startsWith(prefix.trimEnd('/') + "/")) return true
        // 参数被塞进哈希段：比较 '#' 之前的部分
        val head = target.substringBefore('#')
        if (head.startsWith(prefix)) return true
        // 兜底：同主机同端口同路径（忽略协议细节），且带回调关键字，才算回推。
        // 这条不能放宽到「只要含 code=」——正常页面自己也会带 code 参数，会误拦导致登录中断。
        val prefixHost = prefix.substringAfter("://").substringBefore('/')
        val prefixPath = prefix.substringAfter("://").substringAfter('/', "").substringBefore('?')
        if (prefixHost.isNotEmpty() && prefixPath.isNotEmpty() &&
            head.contains(prefixHost) && head.contains("/$prefixPath")
        ) {
            return true
        }
        return false
    }

    override fun onDestroy() {
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_START_URL = "start_url"
        const val EXTRA_CALLBACK_PREFIX = "callback_prefix"
        const val EXTRA_TITLE = "title"

        /** 被动模式：上游没有本地回调（设备授权型），只能靠用户关闭窗口触发取结果。 */
        const val EXTRA_PASSIVE = "passive"
        const val EXTRA_RESULT_URL = "result_url"

        /** 与浏览器方式一致：桌面 UA 才会进「客户端授权」流程。 */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
