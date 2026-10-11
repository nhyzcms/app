package com.nhyzcms.clubapp.shell

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * 目前只做两件事：回传加载进度、转发网页控制台日志。
 *
 * 区块 2 会在这里实现 onShowFileChooser（文件选择 / 拍照上传），
 * 区块 4 会在这里实现全屏视频与沉浸式适配。
 */
class WebShellChromeClient(
    private val onProgress: (Int) -> Unit,
    private val log: (String) -> Unit,
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
}
