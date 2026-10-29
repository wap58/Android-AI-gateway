package dev.aigw.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build

/**
 * 打开登录/授权页时要用的浏览器。
 *
 * 各厂商浏览器对 OAuth 跳转、Cookie 域、UA 的处理差别很大（WPS 灵犀这类按 UA 分流
 * 站点的尤其明显），系统默认浏览器未必是能完成登录的那个，所以允许用户指定。
 *
 * 多个同上游账号在**同一个浏览器**里登录会互相顶掉（Cookie 只有一份），
 * 每个账号换一个浏览器就能各自保留登录态。
 */
object BrowserLauncher {

    /**
     * 已知浏览器包名（小写）。用于发现**没有注册 https intent-filter** 的浏览器
     * ——实测 Edge（com.microsoft.emmx）、Alook（alook.browser.play）就是这种，
     * 用 queryIntentActivities 一律查不到，只能按包名认。
     */
    private val KNOWN_BROWSER_PACKAGES = setOf(
        "com.microsoft.emmx",              // Edge
        "com.android.chrome",
        "com.chrome.beta",
        "alook.browser.play",              // Alook
        "com.heytap.browser",              // OPPO / 一加自带
        "com.qihoo.contents",              // 360
        "com.qihoo.browser",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.brave.browser",
        "com.vivaldi.browser",
        "com.ucmobile",
        "com.UCMobile",
        "com.quark.browser",               // 夸克**浏览器**（不是网盘）
        "com.tencent.mtt",                 // QQ 浏览器
        "com.baidu.searchbox",
        "com.android.browser",
        "mark.via",
        "com.kiwibrowser.browser",
        "com.yandex.browser",
        "com.sec.android.app.sbrowser",    // 三星
        "com.miui.browser",
        "com.huawei.browser",
        "com.android.cmccbbrowser",
    )

    /** 包名/应用名里带这些词的一律不算浏览器（网盘、下载器、音乐盒都中过招）。 */
    private val NON_BROWSER_HINTS = listOf(
        "clouddrive", "netdisk", "pan.", "download", "appmanager", "sdk",
        "overlay", "framework", "carrierconfig", "proxy", "vpn",
    )

    private val BROWSER_HINTS = listOf("browser", "chrome", "firefox", "opera", "edge")

    /** 一个可选浏览器。 */
    data class Target(val packageName: String, val label: String)

    /** 取应用图标；取不到返回 null（界面回退到内置的占位图标）。 */
    fun iconOf(context: Context, packageName: String): android.graphics.drawable.Drawable? =
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()

    /**
     * 本机所有浏览器。
     *
     * 不能只靠 `queryIntentActivities(https)`：不少浏览器（Edge、Alook 等）虽然能打开网页，
     * 但只声明了 `http`/`https` 的 VIEW intent-filter 而没带 BROWSABLE，
     * 或者干脆只有 `VIEW http`，于是用 https 探针查不到。
     * 这里按优先级合并三路结果，再把明显不是浏览器的包（自身、应用商店等）排掉。
     */
    fun available(context: Context): List<Target> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, String>()

        // ① 标准「能打开网页」的应用：http + https，带 BROWSABLE
        for (scheme in listOf("https", "http")) {
            for (info in query(pm, scheme, browsable = true)) {
                add(found, pm, info)
            }
        }
        // ② 没带 BROWSABLE 的（部分国产浏览器/套壳浏览器就是这种写法）
        for (scheme in listOf("https", "http")) {
            for (info in query(pm, scheme, browsable = false)) {
                add(found, pm, info)
            }
        }
        // ③ 兜底：按包名里带 browser / 应用名带「浏览器」再找一轮，
        //    覆盖那些只声明了自家 scheme、或 ACTIVITY 入口没走 VIEW 的浏览器
        for ((pkg, label) in installedBrowserish(pm, context.packageName)) {
            found.putIfAbsent(pkg, label)
        }

        found.remove(context.packageName)
        return found.entries
            .map { Target(it.key, it.value) }
            .sortedBy { it.label.lowercase() }
    }

    private fun query(pm: PackageManager, scheme: String, browsable: Boolean): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://example.com"))
        if (browsable) intent.addCategory(Intent.CATEGORY_BROWSABLE)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 已安装应用里「看起来是浏览器」的。
     *
     * 名字匹配只是发现手段：列出来让用户自己选，选错了顶多打开失败后回退，
     * 比「明明装了却看不到」好得多。
     */
    private fun installedBrowserish(pm: PackageManager, selfPkg: String): List<Pair<String, String>> {
        val packages = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
        }.getOrDefault(emptyList())

        return packages
            .filter { it.packageName != selfPkg }
            .filter { pkg ->
                val p = pkg.packageName.lowercase()
                val label = runCatching { pkg.applicationInfo?.loadLabel(pm)?.toString().orEmpty() }
                    .getOrDefault("")
                // 先排除网盘/下载器：夸克网盘、百度网盘这类包名里也带关键字，
                // 但打开网页只会跳应用内浏览器，列出来会让用户误选。
                if (NON_BROWSER_HINTS.any { p.contains(it) || label.contains(it) }) return@filter false
                p in KNOWN_BROWSER_PACKAGES ||
                    BROWSER_HINTS.any { p.contains(it) } ||
                    label.contains("浏览器")
            }
            // 只保留有启动入口的应用（能点开才可能当浏览器用）
            .filter { runCatching { pm.getLaunchIntentForPackage(it.packageName) }.getOrNull() != null }
            .map { pkg ->
                val label = runCatching { pkg.applicationInfo?.loadLabel(pm)?.toString().orEmpty() }
                    .getOrDefault("")
                pkg.packageName to label.ifBlank { pkg.packageName }
            }
    }

    private fun add(found: MutableMap<String, String>, pm: PackageManager, info: ResolveInfo) {
        val pkg = info.activityInfo?.packageName ?: return
        val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
        found.putIfAbsent(pkg, label.ifBlank { pkg })
    }

    /**
     * 用指定浏览器打开 [url]；[packageName] 为空或该浏览器不可用时回退到系统默认。
     */
    fun open(context: Context, url: String, packageName: String): Boolean = runCatching {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName.isNotEmpty()) intent.setPackage(packageName)
        context.startActivity(intent)
        true
    }.getOrElse {
        // 指定的浏览器被卸载/被禁用：退回系统默认，别让用户点了没反应
        if (packageName.isEmpty()) return@getOrElse false
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }
}
