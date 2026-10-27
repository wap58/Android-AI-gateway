package dev.aigw.app.ui.login

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.setPadding

/**
 * 通用的「WebView 内登录 → 抓 cookie」页面。
 *
 * 有些上游（WPS 灵犀）没有 OAuth 回调，登录态就是一枚 `wps_sid` cookie，
 * 手机上也拿不到 F12。所以只能让用户在 App 内嵌网页里正常登录，
 * 登录完成后从 [CookieManager] 里把目标 cookie 读出来。
 *
 * ## 触发完成的时机
 *
 * 登录成功没有明确的 URL 跳转信号，靠 [WebViewClient.onPageFinished] 在每次
 * 页面加载结束后检查 cookie。只要目标 cookie 存在且长度合理，就当作登录成功。
 * 用户也可点右上角「完成」手动触发一次检查。
 */
class WebCookieLoginActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var hintView: TextView

    /** 是否已经跳到 wps.cn 触发过 SSO。 */
    private var ssOSeeded = false

    /** 是否需要验证。 */
    private var requiresVerification = false

    /** 是否在打开前清空 cookie（添加另一个账号时为 true）。 */
    private var clearFirst = false

    /** 后台验证进行中：避免每轮轮询都新起一个验证线程。 */
    private var verifying = false

    /** 跳转用的 URL（让 SSO 在 wps.cn 域种 cookie）。 */
    private var ssOSeedUrl: String = "https://www.wps.cn/"

    /** 验证登录态的接口地址；为空表示不验证。 */
    private var verifyUrl: String = ""
    private var targetCookie: String = ""
    private var cookieHost: String = ""
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        targetCookie = intent.getStringExtra(EXTRA_COOKIE_NAME).orEmpty()
        cookieHost = intent.getStringExtra(EXTRA_COOKIE_HOST).orEmpty()
        intent.getStringExtra(EXTRA_SSO_URL)?.takeIf { it.isNotEmpty() }?.let { ssOSeedUrl = it }
        intent.getStringExtra(EXTRA_VERIFY_URL)?.takeIf { it.isNotEmpty() }?.let { verifyUrl = it }
        requiresVerification = verifyUrl.isNotEmpty()
        clearFirst = intent.getBooleanExtra(EXTRA_CLEAR_FIRST, false)
        val startUrl = intent.getStringExtra(EXTRA_START_URL).orEmpty()

        if (targetCookie.isEmpty() || startUrl.isEmpty()) {
            finishWith(null)
            return
        }

        // 根容器底色用提示条同色：状态栏区域内边距露出的就是这块颜色，
        // 用白色会在顶部留一条突兀的白边。
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F4F4.toInt())
        }

        // 底部操作区：提示 + 按钮。
        // 放底部是因为顶部要留给网页本身（登录页顶部有自己的标题栏），
        // 而且底部离拇指近，操作更方便。
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
            setBackgroundColor(0xFFF4F4F4.toInt())
        }
        hintView = TextView(this).apply {
            text = "在下面的页面完成登录，成功后会自动添加账号"
            textSize = 13f
            setTextColor(0xFF444444.toInt())
            setLineSpacing(4f, 1f)
        }
        val hint = hintView
        val doneButton = TextView(this).apply {
            text = "完成"
            textSize = 16f
            setTextColor(0xFF1A73E8.toInt())
            setPadding(24, 8, 8, 8)
            setOnClickListener {
                // ★ 必须校验：没拿到会话 cookie 时点了「完成」，
                //   手上只有 csrf / ks_local_token 这类登录页残留，
                //   存进去就是一个用不了的账号。宁可拒绝并提示。
                val (kdocs, wps) = collectByDomain()
                val hasSession = kdocs.containsKey(targetCookie) || wps.containsKey(targetCookie)
                if (!hasSession) {
                    hintView.textSize = 11f
                    hintView.text = "还没检测到登录态（没有 $targetCookie）。\n" +
                        "请先在页面里完成登录，登录成功会自动添加。"
                } else {
                    done = true
                    finishWithBoth(toCookieHeader(kdocs), toCookieHeader(wps))
                }
            }
        }
        bar.addView(
            hint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 按钮行：靠右，与提示分开
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        actionRow.addView(doneButton)
        bar.addView(
            actionRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 12 },
        )
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // ★ 必须伪装成电脑浏览器。
            //   手机版灵犀是「通用聊天 AI」，没有模型选择；只有电脑网页版/客户端
            //   才是「灵犀专业版」，才有 plan_pro/plan_max 和那 8 个内置模型。
            //   用手机 UA 登录，服务端就把会话识别成手机版，模型清单接口也就拿不到内容。
            settings.userAgentString = DESKTOP_UA
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                probeCount = 0
                // 首页的登录入口在侧边栏底部，手机上要滑很久才能点到。
                // 这里自动找一下「登录」按钮并点它，省掉手动找的步骤。
                // 找不到就什么都不做（用户仍可手动登录）。
                if (!hasTarget(collectCookies())) {
                    view?.evaluateJavascript(AUTO_LOGIN_JS, null)
                }
                tryFinish()
            }
        }

        // WebView 占据上方全部剩余空间
        root.addView(
            webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        // 底栏最后添加 → 位于 WebView 下方
        root.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        setContentView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        // ★ 让内容避开状态栏（顶部）和导航栏（底部）。
        //   没有这层处理时，顶部提示条会顶到屏幕最上沿，和状态栏/通知栏重叠。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // 想添加**另一个**账号时必须先清掉已登录状态：
        // WebView 的 CookieManager 是全局共享的，WPS 已登录时会直接复用，
        // 不清空就永远只能读到当前这个账号。
        if (clearFirst) {
            CookieManager.getInstance().removeAllCookies { }
            CookieManager.getInstance().flush()
        }

        webView.loadUrl(startUrl)
        startPolling()
    }

    /**
     * 三段式登录流程。
     *
     * 1. 用户在 [cookieHost] 登录（灵犀页面）
     * 2. 跳一次 [ssOSeedUrl] 让 SSO 把最新会话的 cookie 种进去
     *    —— 关键：登录页所在的域常留有旧会话的 cookie，那份已经失效；
     *       最新有效的那份在 wps.cn 域。不主动访问就不会出现。
     * 3. 收集全部 cookie，**实测一次接口**确认真的能用，才交回去
     */
    private fun startPolling() {
        val maxTicks = 240          // 240 × 1.5s ≈ 6 分钟
        var tick = 0
        val task = object : Runnable {
            override fun run() {
                if (done || isFinishing) return
                tick++

                val cookies = collectCookies()
                val loginDone = hasTarget(cookies)

                // 阶段 1：等用户登录
                if (!loginDone) {
                    if (tick < maxTicks) {
                        webView.postDelayed(this, 1500)
                    } else {
                        showDiagnostics(manual = false)
                    }
                    return
                }

                // 阶段 2：登录成功，跳 wps.cn 触发 SSO（只做一次）
                if (!ssOSeeded) {
                    ssOSeeded = true
                    hintView.text = "正在同步登录态…"
                    webView.loadUrl(ssOSeedUrl)
                    webView.postDelayed(this, 3000)
                    return
                }

                // 阶段 3：收集完毕，实测可用性后再交回。
                // ★ 验证要打网络，必须放到后台线程 —— 这里是 UI 线程，
                //   同步做 HttpURLConnection 会卡住界面甚至 ANR。
                if (!verifying) {
                    verifying = true
                    hintView.text = "正在验证登录态…"
                    val header = toCookieHeader(cookies)
                    Thread {
                        val ok = requiresVerification == false || verifyCookie(header)
                        runOnUiThread {
                            verifying = false
                            if (ok) {
                                done = true
                                finishWith(header)
                            } else if (tick < maxTicks - 1) {
                                // 验证失败：可能仍在写入，交给下一轮重试
                                webView.postDelayed(this, 1500)
                            } else {
                                hintView.textSize = 11f
                                hintView.text = "cookie 已读取但接口验证未通过，请重新登录"
                            }
                        }
                    }.start()
                }
            }
        }
        webView.postDelayed(task, 1500)
    }

    /**
     * 用拿到的 cookie 打一次真实接口，确认可用。
     *
     * 只判断"有没有 cookie"是不够的：登录页所在的域常留有旧会话，
     * 读得到但已失效，交回去只会让用户看到"模型列表拉取失败"。
     */
    private fun verifyCookie(cookieHeader: String): Boolean = try {
        val url = java.net.URL(verifyUrl)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Cookie", cookieHeader)
        conn.setRequestProperty("User-Agent", DESKTOP_UA)
        conn.setRequestProperty("Referer", "https://lingxi.kdocs.cn/")
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            ""
        }
        conn.disconnect()
        code in 200..299 && !body.contains("UserUnauthorized")
    } catch (_: Throwable) {
        false
    }

    /**
     * 分别收集两个域的完整 cookie 串。
     *
     * ★ 实测结论（用户验证）：
     * - **kdocs 域**的会话 → 对话是专业版（模型能力正常）
     * - **wps.cn 域**的会话 → 模型列表/签到/额度正常，但对话退化成通用 AI
     *
     * 两个域各有一份 wps_sid，代表**不同等级的会话**。所以不能只保留其中一份，
     * 而是分开存：对话用 kdocs 那份，业务接口用 wps.cn 那份。
     */
    private fun collectByDomain(): Pair<Map<String, String>, Map<String, String>> {
        val manager = CookieManager.getInstance()
        runCatching { manager.flush() }

        fun readHosts(hosts: List<String>): Map<String, String> {
            val merged = LinkedHashMap<String, String>()
            hosts.forEach { host ->
                val url = "https://${host.removePrefix(".")}/"
                val raw = runCatching { manager.getCookie(url) }.getOrNull().orEmpty()
                if (raw.isEmpty()) return@forEach
                raw.split(";").forEach { piece ->
                    val kv = piece.trim()
                    if (kv.isEmpty()) return@forEach
                    val name = kv.substringBefore('=')
                    val value = kv.substringAfter('=', "")
                    if (name.isNotEmpty() && value.isNotEmpty()) merged[name] = value
                }
            }
            return merged
        }

        val kdocsHosts = listOf(
            "lingxi.kdocs.cn", ".lingxi.kdocs.cn",
            "kdocs.cn", ".kdocs.cn",
            "account.kdocs.cn", ".account.kdocs.cn",
        )
        val wpsHosts = listOf(
            "wps.cn", ".wps.cn",
            "account.wps.cn", ".account.wps.cn",
            "www.wps.cn",
        )

        return readHosts(kdocsHosts) to readHosts(wpsHosts)
    }

    /** 候选 host：cookie 可能挂在子域、父域或 SSO 域上。 */
    private fun candidateHosts(): List<String> {
        val base = cookieHost.trim().removePrefix(".")
        val parts = base.split(".")
        // 逐级上卷：lingxi.kdocs.cn → kdocs.cn
        val rolled = mutableListOf<String>()
        for (i in 0 until parts.size - 1) {
            rolled += parts.drop(i).joinToString(".")
        }
        val hosts = mutableListOf<String>()
        // ★ wps.cn 必须排在前面。
        //   实测：同一账号会在两个域上各留一个 wps_sid，代表不同的登录会话；
        //   旧会话（kdocs 域残留）已失效，请求一律 401；最新的那份在 wps.cn 域。
        //   与 UA、与会话类型都无关，纯粹是"哪个会话还活着"。
        hosts += listOf("wps.cn", ".wps.cn", "account.wps.cn", "www.wps.cn")
        hosts += listOf(base, ".$base")
        rolled.forEach { hosts += listOf(it, ".$it") }
        hosts += listOf("account.kdocs.cn", ".kdocs.cn")
        return hosts.filter { it.isNotEmpty() && it != "." }.distinct()
    }

    /**
     * 检查 cookie，命中就返回结果并关闭。
     *
     * CookieManager 的写入是异步的，onPageFinished 触发时 cookie 可能还没落地，
     * 所以连续重试几次再放弃。
     */
    private fun tryFinish(manual: Boolean = false) {
        if (done) return
        val attempt = ++probeCount

        val cookies = collectCookies()
        if (hasTarget(cookies)) {
            done = true
            // 带上全部 cookie，让 provider 拿到完整登录态
            finishWith(toCookieHeader(cookies))
            return
        }

        if (attempt < MAX_PROBES) {
            // 200ms / 500ms / 1000ms … 逐步拉长
            val delay = 200L * attempt
            webView.postDelayed({ tryFinish(manual) }, delay)
        } else {
            showDiagnostics(manual)
        }
    }

    private var probeCount = 0


    /**
     * 读取**全部**可见 cookie 并合并成一条 Cookie 头。
     *
     * 不能只挑 `wps_sid`：灵犀的登录态由多枚 cookie 共同构成
     * （参考 lingxi-checkin 的做法——它保存整个 context 的 cookies）。
     * 而且 wps_sid 可能是 HttpOnly，只有 CookieManager 读得到。
     */
    /**
     * 收集全部可见 cookie。
     *
     * ★ flush() 必须无条件调用：CookieManager 的写入是异步的，
     *   getCookie() 在 flush 之前读不到刚种下的 wps_sid。
     *   vc1 一直是对的，中间为了省 I/O 去掉过一次，直接导致登录成功后读不到 cookie。
     */
    private fun collectCookies(): Map<String, String> {
        val manager = CookieManager.getInstance()
        runCatching { manager.flush() }

        val merged = LinkedHashMap<String, String>()
        candidateHosts().forEach { host ->
            // ★ getCookie 的参数语义是 URL，不是裸域名。
            //   传 "lingxi.kdocs.cn" 时内部 URI 解析可能直接失败返回 null，
            //   必须补 scheme。
            val url = if (host.startsWith("http")) host else "https://${host.removePrefix(".")}/"
            val raw = runCatching { manager.getCookie(url) }.getOrNull().orEmpty()
            if (raw.isEmpty()) return@forEach
            raw.split(";").forEach { piece ->
                val kv = piece.trim()
                if (kv.isEmpty()) return@forEach
                val name = kv.substringBefore('=')
                val value = kv.substringAfter('=', "")
                if (name.isEmpty() || value.isEmpty()) return@forEach
                if (name == "wps_sid") {
                    // wps.cn 域那份是最新会话，优先；另一份留作 alt 兜底
                    if (host.contains("wps.cn")) {
                        merged["wps_sid"] = value
                    } else if (merged["wps_sid"] == null) {
                        merged["wps_sid"] = value
                    } else {
                        merged["wps_sid_alt"] = value
                    }
                } else {
                    merged[name] = value
                }
            }
        }
        return merged
    }

    /** 是否已经拿到目标 cookie（登录完成的判定）。 */
    private fun hasTarget(cookies: Map<String, String>): Boolean {
        val v = cookies[targetCookie] ?: return false
        return v.length >= 8
    }


    /** 拼成 `a=1; b=2` 形式，作为凭证交给 provider。 */
    private fun toCookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }


    /** 拿不到目标 cookie 时，把所有可见 cookie 的名字列出来，便于定位域名/时机问题。 */
    private fun showDiagnostics(manual: Boolean) {
        val cookies = collectCookies()
        val names = cookies.keys.joinToString(",").ifEmpty { "无" }
        val summary = "没找到 $targetCookie。可见 cookie(${cookies.size})：$names"

        hintView.textSize = 11f
        hintView.text = summary + "\n请确认已登录成功；若仍有问题可点右上角「完成」强制保存"
    }

    private fun finishWith(cookieValue: String?) {
        val (kdocs, wps) = collectByDomain()
        finishWithBoth(toCookieHeader(kdocs), toCookieHeader(wps))
    }

    /** 把两份凭证都交回去：主用 kdocs（对话专业版），备用 wps.cn（业务接口）。 */
    private fun finishWithBoth(primary: String, secondary: String) {
        // 交付前刷一次盘，保证 CookieManager 的改动已落到底层存储
        runCatching { CookieManager.getInstance().flush() }
        val data = android.content.Intent().apply {
            putExtra(EXTRA_RESULT_COOKIE, primary)
            putExtra(EXTRA_RESULT_COOKIE_ALT, secondary)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun finishWithLegacy(cookieValue: String?) {
        val data = android.content.Intent().apply {
            putExtra(EXTRA_RESULT_COOKIE, cookieValue.orEmpty())
        }
        setResult(if (cookieValue.isNullOrEmpty()) RESULT_CANCELED else RESULT_OK, data)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            setResult(RESULT_CANCELED)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            runCatching { webView.destroy() }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_START_URL = "start_url"
        const val EXTRA_COOKIE_HOST = "cookie_host"
        const val EXTRA_COOKIE_NAME = "cookie_name"
        const val EXTRA_RESULT_COOKIE = "result_cookie"
        const val EXTRA_RESULT_COOKIE_ALT = "result_cookie_alt"
        const val EXTRA_CLEAR_FIRST = "clear_first"
        const val EXTRA_SSO_URL = "sso_url"
        const val EXTRA_VERIFY_URL = "verify_url"

        private const val MAX_PROBES = 8

        /** 桌面 Chrome UA：拿到的是灵犀专业版界面。 */
        /**
         * 自动点「登录」按钮。
         *
         * 只在有明确意图的文本上点，且要求元素可见；
         * 找不到就返回 no-login-found，绝不影响页面本身。
         */
        private val AUTO_LOGIN_JS = """
        (function(){
          try{
            var els = Array.from(document.querySelectorAll('button,a,div,span'));
            for (var i=0;i<els.length;i++){
              var e = els[i];
              var t = (e.textContent||'').trim();
              if (t !== '登录' && t !== '立即登录' && t !== '登录/注册') continue;
              var r = e.getBoundingClientRect();
              if (r.width <= 0 || r.height <= 0) continue;
              e.click();
              return 'clicked';
            }
          }catch(err){}
          return 'no-login-found';
        })();
        """.trimIndent()

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
