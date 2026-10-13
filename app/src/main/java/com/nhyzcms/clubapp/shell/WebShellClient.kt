package com.nhyzcms.clubapp.shell

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 把"导航该去哪里"交给 [NavPolicy] 决定，本类只负责执行副作用
 * （开系统浏览器、拦截提示、报错回调）。
 *
 * 安全红线：onReceivedSslError 里**绝不**调 handler.proceed()（docs/SECURITY.md §2）。
 */
class WebShellClient(
    private val onError: (kind: ShellErrorKind, failingUrl: String?) -> Unit,
    private val onExternalOpened: () -> Unit,
    private val onBlocked: (reason: String, url: String) -> Unit,
    private val onPageStarted: (String) -> Unit,
    private val onPageFinished: (String) -> Unit,
    /**
     * 外链是否交给系统浏览器（清单开关 `externalLinkInBrowser`）。
     * 关掉时外链在壳内加载；明文（http）拦截不依赖它——NavPolicy 会先拒掉。
     */
    private val externalLinkInBrowser: () -> Boolean = { true },
    /**
     * 大资源闸门（区块 6）：返回非 null 就用这个应答替掉真实请求，返回 null 走正常加载。
     * 由 [com.nhyzcms.clubapp.shell.perf.PerfHost.interceptHeavyAsset] 提供（Live2D 兜底拦截）。
     *
     * ⚠️ WebView 会在**后台线程**调用它：实现必须是纯判定 + 立即返回，不许网络 I/O、不许碰 WebView。
     */
    private val resourceGate: ((WebResourceRequest) -> WebResourceResponse?) = { null },
) : WebViewClient() {

    /**
     * 子资源闸门。**主文档一律放行**（拦主文档等于白屏），判定与安全边界都在
     * [com.nhyzcms.clubapp.shell.perf.HeavyAssetPolicy] 里（纯逻辑、可单测）。
     */
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (request.isForMainFrame) return null
        return runCatching { resourceGate(request) }.getOrNull()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // 子框架与子资源不干预，避免把页面里的内嵌内容（如图片、视频）也拦下来。
        if (!request.isForMainFrame) return false

        val parts = UrlParts.of(request.url)
        return when (val decision = NavPolicy.decide(parts)) {
            is NavDecision.InApp -> false // 交给 WebView 自己加载

            is NavDecision.External -> {
                // 清单开关要求"外链也在壳内打开"时直接放行给 WebView；
                // 明文（http）走不到这里——NavPolicy 已经把它判成 Blocked。
                if (!externalLinkInBrowser()) return false
                openExternally(view.context, decision.url)
                onExternalOpened()
                true
            }

            is NavDecision.Blocked -> {
                if (decision.reason == NavPolicy.REASON_CLEARTEXT) {
                    onError(ShellErrorKind.BLOCKED_CLEARTEXT, decision.url)
                } else {
                    onBlocked(decision.reason, decision.url)
                }
                true
            }
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        onError(ShellErrorKind.TLS, error.url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        // 只关心主文档；子资源（图/脚本）失败不打断使用，由网页自己兵来将挡。
        if (!request.isForMainFrame) return
        onError(ShellErrorKind.NETWORK, request.url.toString())
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        onPageStarted(url.orEmpty())
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onPageFinished(url.orEmpty())
    }

    private fun openExternally(context: Context, url: String) {
        try {
            // 不调 resolveActivity（Android 11+ 包可见性限制），直接 try/catch 更稳。
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // 没有能处理该 scheme 的 App，属于正常情况，静默忽略。
        }
    }
}
