package com.nhyzcms.clubapp.shell

import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
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
    /**
     * 网页 `getUserMedia` 的权限请求（2026-11）：本站 `/#/v` 用它扫工牌条码。
     *
     * 本类只转发，**裁决在 [com.nhyzcms.clubapp.shell.security.WebPermissionPolicy]**：
     * 只认本站 Origin、只给视频采集、系统没给相机权限就拒绝。
     * 不接这个回调时 WebView 一律 deny，壳里那个扫码按钮永远只会报"权限被拒绝"。
     */
    private val onWebPermissionRequest: (PermissionRequest) -> Unit = {},
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

    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        onWebPermissionRequest(request)
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
