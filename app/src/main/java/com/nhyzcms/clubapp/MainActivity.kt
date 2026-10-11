package com.nhyzcms.clubapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.nhyzcms.clubapp.shell.AppConfig
import com.nhyzcms.clubapp.shell.NavDecision
import com.nhyzcms.clubapp.shell.NavPolicy
import com.nhyzcms.clubapp.shell.ShellErrorKind
import com.nhyzcms.clubapp.shell.UrlParts
import com.nhyzcms.clubapp.shell.WebShellChromeClient
import com.nhyzcms.clubapp.shell.WebShellClient

/**
 * 壳的入口：一个 Activity + 一个 WebView。
 *
 * 职责边界（重要）：本类**不做**任何业务判断（登录、路由、权限），
 * 只负责"把网页跑起来"与"把原生能力接上去"。详见 docs/ARCHITECTURE.md §4。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorPanel: View
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView

    /** 最后一次加载/失败的地址，供"重试"与"用浏览器打开"使用。 */
    private var lastUrl: String = AppConfig.START_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        // release 包关闭 WebView 远程调试（安全文档 §2）；debug 包保留 chrome://inspect 能力。
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        configureWebView()
        registerBackCallback()
        bindErrorPanelActions()

        // 进程被系统回收后重建：优先恢复浏览历史，避免用户回到首页。
        val restored = savedInstanceState?.let { webView.restoreState(it) }
        if (restored == null) {
            load(intentUrlOrNull(intent) ?: AppConfig.START_URL)
        }
    }

    // ── 初始化 ────────────────────────────────────────────────────────────────

    private fun bindViews() {
        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        errorPanel = findViewById(R.id.errorPanel)
        errorTitle = findViewById(R.id.errorTitle)
        errorMessage = findViewById(R.id.errorMessage)
    }

    @Suppress("SetJavaScriptEnabled", "DEPRECATION")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true // SPA 必需
            domStorageEnabled = true // localStorage（主题偏好等）
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // 壳只加载网络内容，不需要本地文件能力 → 全部关闭（安全文档 §2）
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false

            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + " " + AppConfig.uaMarker()
            safeBrowsingEnabled = true // minSdk 26 起可用
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true) // 登录会话靠它，必须开
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.webViewClient = WebShellClient(
            onError = ::showError,
            onExternalOpened = { toast(R.string.toast_external_opened) },
            onBlocked = { reason, _ ->
                Log.w(TAG, "拦截导航：$reason")
                toast(R.string.toast_link_blocked)
            },
            onPageStarted = { hideError() },
            onPageFinished = {
                progress.visibility = View.GONE
                hideError()
            },
        )

        webView.webChromeClient = WebShellChromeClient(
            onProgress = { value ->
                if (value in 1..99) {
                    progress.visibility = View.VISIBLE
                    progress.progress = value
                } else {
                    progress.visibility = View.GONE
                }
            },
            log = { message -> if (BuildConfig.DEBUG) Log.d(TAG, message) },
        )

        // TODO(区块 2)：改为 DownloadManager + 会话 Cookie + 通知栏进度。
        // 目前仅提示：下载在 WebView 里默认不生效，不要让它"静默没反应"。
        webView.setDownloadListener { url, _, _, _, _ ->
            Log.i(TAG, "下载请求（区块 2 实现）：host=" + hostOf(url))
            toast(R.string.toast_download_todo)
        }
    }

    /** 返回键：先关错误面板 → 再网页后退 → 最后才退出应用。 */
    private fun registerBackCallback() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (errorPanel.visibility == View.VISIBLE) {
                        hideError()
                        return
                    }
                    if (webView.canGoBack()) {
                        webView.goBack()
                        return
                    }
                    // 把控制权交回系统（退出）。
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            },
        )
    }

    private fun bindErrorPanelActions() {
        findViewById<Button>(R.id.retryButton).setOnClickListener {
            hideError()
            load(lastUrl)
        }
        findViewById<Button>(R.id.browserButton).setOnClickListener {
            openExternally(lastUrl)
        }
    }

    // ── 错误面板 ──────────────────────────────────────────────────────────────

    private fun showError(kind: ShellErrorKind, failingUrl: String?) {
        if (!failingUrl.isNullOrBlank() && failingUrl.startsWith("http")) {
            lastUrl = failingUrl
        }
        errorTitle.setText(R.string.error_title)
        errorMessage.setText(
            when (kind) {
                ShellErrorKind.NETWORK -> R.string.error_network
                ShellErrorKind.TLS -> R.string.error_tls
                ShellErrorKind.BLOCKED_CLEARTEXT -> R.string.error_cleartext
                ShellErrorKind.GENERIC -> R.string.error_generic
            },
        )
        errorPanel.visibility = View.VISIBLE
        progress.visibility = View.GONE

        if (kind.isSecurityRelated) {
            // 日志脱敏：只记 host，不记完整 URL 与查询串
            Log.w(TAG, "安全类加载失败：" + kind.name + " host=" + hostOf(lastUrl))
        }
    }

    private fun hideError() {
        errorPanel.visibility = View.GONE
    }

    // ── 导航与生命周期 ──────────────────────────────────────────────────────

    private fun load(url: String) {
        lastUrl = url
        hideError()
        progress.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    /** 处理 ACTION_VIEW 深链：只在目标属于本站时才交给壳内加载。 */
    private fun intentUrlOrNull(intent: Intent?): String? {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        return when (val decision = NavPolicy.decide(UrlParts.of(data))) {
            is NavDecision.InApp -> decision.url
            else -> null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentUrlOrNull(intent)?.let { load(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume() // 恢复 JS 定时器/动画，省电靠 onPause
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // WebView 不主动销毁会泄漏 Activity
        webView.stopLoading()
        webView.setWebChromeClient(null)
        webView.setWebViewClient(null)
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ── 小工具 ────────────────────────────────────────────────────────────────

    private fun openExternally(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            toast(R.string.toast_external_opened)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.toast_link_blocked)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun hostOf(url: String?): String =
        runCatching { Uri.parse(url.orEmpty()).host }.getOrNull().orEmpty().ifEmpty { "-" }

    private companion object {
        const val TAG = "ClubApp"
    }
}
