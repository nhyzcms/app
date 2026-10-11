package com.nhyzcms.clubapp.shell

import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * 进度回传 + 控制台日志 + 文件选择（区块 2）。
 *
 * 区块 4 会在这里实现全屏视频与沉浸式适配。
 */
class WebShellChromeClient(
    private val onProgress: (Int) -> Unit,
    private val log: (String) -> Unit,
    /** 文件选择：返回 true 表示壳接管（回调恰好一次的保证在 [FileChooserHost]）。 */
    private val onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        onProgress(newProgress)
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        log(
            "[" + consoleMessage.messageLevel() + "] " + consoleMessage.message() +
                " (" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + ")",
        )
        return true // 已处理，避免系统再打一份
    }

    /**
     * 网页里的 input type=file 被点击时回调。
     *
     * ⚠️ 只要返回 true，就**必须**保证 filePathCallback 最终被回调一次；否则网页那个 input
     * 会永久卡死（用户再点毫无反应，只能杀 App）。本类只做转发，生命周期由 FileChooserHost 管理。
     */
    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?,
    ): Boolean {
        if (filePathCallback == null || fileChooserParams == null) return false
        return onShowFileChooser(filePathCallback, fileChooserParams)
    }
}
