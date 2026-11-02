package com.nhyzcms.clubapp

import android.net.Uri
import android.os.Looper
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * 区块 9：壳级「启动 → 加载 → 错误页」测试（JVM 上跑真实 Activity）。
 *
 * 为什么需要它：项目之前的 29 个测试类全是**纯函数**单测，没有任何一条用例真正把
 * `MainActivity` 跑起来。于是区块 1 那次「`webView.settings` 在后台线程被读」的闪退，
 * 单测全绿、CI 全绿，只有真机能暴露。本测试把三条历史红线里**壳侧能自动验的部分**钉住：
 *
 *  1. 启动不崩，且 WebView 的安全配置真的按 docs/SECURITY.md 生效（关文件访问、开 JS）；
 *  2. 主文档失败 → 原生错误面板出现（不再是一张白页），子资源失败不打断使用；
 *     429/403 有单独文案（对齐网站 2026-10 的限流补丁）；
 *  3. 加载完成 → 骨架屏一定会被撤掉、进度条不再转（浮层不许一直盖着）；
 *  4. 错误面板盖着时按返回 = 先收起面板，而不是退出 App。
 *
 * 不覆盖的（留给 docs/REGRESSION.md 的人工清单）：真实网络、TLS 握手、Chromium 渲染、
 * 相机/文件选择器、推送连接。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityShellTest {

    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var webView: WebView

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        webView = activity.findViewById(R.id.webView)
        assertNotNull("activity_main 里必须有 webView", webView)
    }

    @After
    fun tearDown() {
        // 走完整生命周期：onDestroy 里有 WebView.destroy() 与 Handler 清理，
        // 顺带验证「销毁路径本身不崩」（泄漏与 BadTokenException 的高发区）。
        //
        // 必须先判 isInitialized：如果 setUp 里 onCreate 就崩了，这里再抛
        // UninitializedPropertyAccessException 会把真正的报错盖掉（CI 上排查过这种）。
        if (::activity.isInitialized && !activity.isFinishing) {
            controller.pause().stop().destroy()
        }
    }

    // ── 1. 启动 ──────────────────────────────────────────────────────────────

    @Test
    fun coldStart_webViewSecuritySettingsFollowsSecurityDoc() {
        val settings = webView.settings
        assertTrue("SPA 必需：JavaScript 必须开", settings.javaScriptEnabled)
        assertTrue("登录态依赖：DOM storage 必须开", settings.domStorageEnabled)
        assertFalse("壳只加载网络内容：文件访问必须关", settings.allowFileAccess)
        assertFalse("壳只加载网络内容：content 访问必须关", settings.allowContentAccess)
        assertFalse(settings.allowFileAccessFromFileURLs)
        assertFalse(settings.allowUniversalAccessFromFileURLs)
        // 区块 8 的壳版本闸门靠这个标记识别 App；丢了它网站会当成浏览器。
        assertTrue(
            "UA 里必须带 NhyzClubApp/ 标记，实际=" + settings.userAgentString,
            settings.userAgentString.contains("NhyzClubApp/"),
        )
    }

    @Test
    fun coldStart_showsSkeletonButNotErrorPanel() {
        assertEquals("冷启动要先顶骨架屏，不允许白屏", View.VISIBLE, view(R.id.skeleton).visibility)
        assertEquals("启动时不该有错误面板", View.GONE, view(R.id.errorPanel).visibility)
        // 起始地址应已下发（起始路由来自清单缓存，未拉到清单时是 AppConfig.START_URL）
        assertNotNull(shadowOf(webView).lastLoadedUrl)
    }

    // ── 2. 错误页 ────────────────────────────────────────────────────────────

    @Test
    fun mainFrameNetworkError_showsErrorPanelWithNetworkText() {
        client().onReceivedError(webView, request("https://8.153.145.175/", mainFrame = true), error())

        assertEquals("主文档失败必须出现原生错误面板，不能留白页", View.VISIBLE, view(R.id.errorPanel).visibility)
        assertEquals(
            activity.getString(R.string.error_network),
            viewAs<TextView>(R.id.errorMessage).text.toString(),
        )
        assertEquals("错误面板出现时进度条要停", View.GONE, view(R.id.progress).visibility)
    }

    @Test
    fun mainFrameHttp429_showsRateLimitedText() {
        // 网站 2026-10 加了 nginx limit_req：主文档被限流时 WebView 会回调 onReceivedHttpError。
        client().onReceivedHttpError(
            webView,
            request("https://8.153.145.175/", mainFrame = true),
            android.webkit.WebResourceResponse(
                "text/html",
                "UTF-8",
                429,
                "Too Many Requests",
                emptyMap<String, String>(),
            ),
        )

        assertEquals(View.VISIBLE, view(R.id.errorPanel).visibility)
        assertEquals(
            activity.getString(R.string.error_rate_limited),
            viewAs<TextView>(R.id.errorMessage).text.toString(),
        )
    }

    @Test
    fun subResourceError_doesNotShowPanel() {
        // 图片/脚本失败由网页自己兵来将挡；壳若弹面板，一次封面 404 就会打断整页使用。
        client().onReceivedError(webView, request("https://8.153.145.175/media/a.jpg", mainFrame = false), error())

        assertEquals(View.GONE, view(R.id.errorPanel).visibility)
    }

    @Test
    fun sslError_neverProceedsAndShowsTlsText() {
        // 安全红线：onReceivedSslError 里绝不 proceed()，必须 cancel + 给 TLS 文案。
        //
        // 为什么用 Mockito 而不是匿名子类：`SslErrorHandler` / `SslError` 的构造函数在 SDK
        // 里是 package-private（CI 实测 "Cannot access 'constructor()': it is package-private"），
        // 匿名对象写不出来；mock 还能顺手断言「proceed 一次都没调过」。
        val handler = Mockito.mock(android.webkit.SslErrorHandler::class.java)
        client().onReceivedSslError(webView, handler, sslError())

        Mockito.verify(handler).cancel()
        Mockito.verify(handler, Mockito.never()).proceed()
        assertEquals(View.VISIBLE, view(R.id.errorPanel).visibility)
        assertEquals(
            activity.getString(R.string.error_tls),
            viewAs<TextView>(R.id.errorMessage).text.toString(),
        )
    }

    // ── 3. 加载完成：浮层必须撤掉 ─────────────────────────────────────────────

    @Test
    fun pageFinished_hidesProgressAndRemovesSkeleton() {
        client().onPageFinished(webView, "https://8.153.145.175/")
        // 骨架撤除是「淡出 + 兜底 postDelayed(FADE_MS + 200)」，把主线程时间推过去。
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)

        assertEquals("加载完进度条必须收掉", View.GONE, view(R.id.progress).visibility)
        assertEquals("骨架屏必须撤掉（盖住网页就是区块 4 那次黑屏）", View.GONE, view(R.id.skeleton).visibility)
        assertEquals("正常加载不该留下错误面板", View.GONE, view(R.id.errorPanel).visibility)
    }

    // ── 4. 返回键裁决 ────────────────────────────────────────────────────────

    @Test
    fun backPress_whileErrorPanelVisible_closesPanelInsteadOfExiting() {
        client().onReceivedError(webView, request("https://8.153.145.175/", mainFrame = true), error())
        assertEquals(View.VISIBLE, view(R.id.errorPanel).visibility)

        activity.onBackPressedDispatcher.onBackPressed()

        assertEquals("返回键第一步是收起错误面板", View.GONE, view(R.id.errorPanel).visibility)
        assertFalse("刚收起面板，不能顺手把 App 退掉", activity.isFinishing)
    }

    // ── 小工具 ───────────────────────────────────────────────────────────────

    private fun view(id: Int): View = activity.findViewById(id)

    private inline fun <reified T : View> viewAs(id: Int): T = activity.findViewById(id)

    private fun client() = webView.webViewClient

    private fun request(url: String, mainFrame: Boolean): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)

            override fun isForMainFrame(): Boolean = mainFrame

            override fun isRedirect(): Boolean = false

            override fun hasGesture(): Boolean = false

            override fun getMethod(): String = "GET"

            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }

    // WebResourceError 在 compileSdk 35 里是抽象类且构造函数不可见，只能 mock。
    private fun error(): WebResourceError = Mockito.mock(WebResourceError::class.java)

    private fun sslError(): android.net.http.SslError =
        Mockito.mock(android.net.http.SslError::class.java)
}
