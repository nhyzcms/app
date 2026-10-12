package com.nhyzcms.clubapp.shell.feel

import com.nhyzcms.clubapp.shell.update.MiniJson

/**
 * 网页“到底有没有画出内容”的探测（**纯逻辑**，不 import 任何 android.*，可在 JVM 上单测）。
 *
 * ## 为什么需要它（2026-10-12 的黑屏事故）
 * 冷启动有个骨架屏浮层，原先只在 `onPageFinished` 时揭开。但 `onPageFinished` 要等网页的
 * `load` 事件——**所有子资源都加载完**才算数。本站首页会拉大图（还有 live2d 模型），
 * 在 3 M 上行 + 手机网络下这个事件迟迟不来 → 骨架屏一直盖在网页上 → 用户看到的就是“一片黑”
 * （骨架屏用的是资源色，深色模式下就是 #080F17），并且 `pageLoaded` 一直是 false，
 * 下拉刷新被判定为“不可用”→“下拉后没反应”。
 *
 * 正确做法：**别等 load 事件**，直接问网页“你画出东西了吗”——本文件就是这个问题与它的判定。
 *
 * ## 安全边界
 * 脚本是固定常量（[SCRIPT] 里没有任何拼接），只读 DOM 的结构性数字，
 * 不读页面内容、不写回页面（与 [PageStateProbe] 同一套约束）。
 */
object ContentProbe {

    /** body 内 HTML 长度小于它、且 #root 没有子节点 → 认为“没画出来”。 */
    const val MIN_BODY_CHARS = 40

    /**
     * 一次探测的快照。
     *
     * @param rootChildren 站点 React 挂载点 `#root` 的子节点数（-1 = 页面里根本没有 #root）
     * @param bodyChars    `document.body.innerHTML` 的长度（-1 = 取不到，说明探测结果不可信）
     * @param scriptTags   页面里的 `script` 元素数量（用来区分“网页根本没脚本”和“脚本跑了但挂了”）
     * @param hasBundleGlobal 站点 webpack 的全局对象是否已建立（能区分“脚本没下载”与“脚本下载了但执行失败”）
     */
    data class Snapshot(
        val rootChildren: Int,
        val bodyChars: Int,
        val scriptTags: Int,
        val hasBundleGlobal: Boolean,
    )

    /**
     * 固定脚本：只返回四个数字，避免把页面内容带出来。
     *
     * 注意 `evaluateJavascript` 会把返回值再 JSON 化一次，所以 Kotlin 侧要解两层（见 [parse]）。
     */
    const val SCRIPT: String =
        "(function(){var r=document.getElementById('root');var b=document.body;" +
            "return JSON.stringify([r?r.childElementCount:-1,b?b.innerHTML.length:-1," +
            "document.scripts.length,(typeof window.webpackChunkfrontend==='undefined')?0:1]);})()"

    /** 解析 `evaluateJavascript` 的返回值；任何一层解不出来都返回 null（绝不抛异常）。 */
    fun parse(rawJsValue: String?): Snapshot? {
        val inner = MiniJson.parse(rawJsValue) as? String ?: return null
        val values = MiniJson.parse(inner) as? List<*> ?: return null
        val root = (values.getOrNull(0) as? Double)?.toInt() ?: return null
        val body = (values.getOrNull(1) as? Double)?.toInt() ?: return null
        val scripts = (values.getOrNull(2) as? Double)?.toInt() ?: 0
        val bundle = ((values.getOrNull(3) as? Double)?.toInt() ?: 0) != 0
        return Snapshot(root, body, scripts, bundle)
    }

    /**
     * 页面是不是“什么也没画出来”。
     *
     * 探测不到（bodyChars < 0）时**不下结论**：那一刻页面可能正在导航，误判会让骨架屏被提前揭开。
     */
    fun looksUnrendered(snapshot: Snapshot): Boolean {
        if (snapshot.bodyChars < 0) return false
        return snapshot.bodyChars < MIN_BODY_CHARS && snapshot.rootChildren <= 0
    }

    /**
     * 组装给用户看的诊断文本：用户点一下就能复制、发回来，我们据此定位（不必再依赖 adb）。
     */
    fun diagnose(
        snapshot: Snapshot?,
        url: String,
        javaScriptEnabled: Boolean,
        webViewVersion: String,
    ): String = buildString {
        appendLine("地址：").append(url.ifBlank { "(空)" })
        appendLine("网页脚本：").append(if (javaScriptEnabled) "已开启" else "已关闭")
        appendLine("WebView 版本：").append(webViewVersion.ifBlank { "(未知)" })
        if (snapshot == null) {
            append("内容探测：拿不到结果（脚本没执行，或页面已经跳走）")
        } else {
            append("内容探测：#root 子节点 ").append(snapshot.rootChildren)
            append("，body 长度 ").append(snapshot.bodyChars)
            append("，script 标签 ").append(snapshot.scriptTags)
            append("，站点打包全局 ").append(if (snapshot.hasBundleGlobal) "有" else "无")
        }
    }
}
