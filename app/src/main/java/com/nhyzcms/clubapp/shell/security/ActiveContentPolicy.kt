package com.nhyzcms.clubapp.shell.security

import java.net.URI
import java.util.Locale

/**
 * 顶层导航的「活跃内容」闸门（纯函数，可 JVM 单测）。
 *
 * 为什么需要：网站把用户上传的文件统一放在**同源**的 `/media/` 下。网站侧 2026-10 的安全审计
 * 已经让服务端按真实内容判定类型、并给这些响应加 `nosniff`（网站仓库 commit `a092a26`，审计结论
 * 是「堵住 /media 同源存储型 XSS」）。本类是**纵深防御**：即使服务端那道闸门被绕过或被降级，
 * 壳里也不会把一份上传文件当成网页来跑。
 *
 * 为什么壳里更值得防：壳没有 JS 桥（SECURITY §4），但同源脚本仍能读 localStorage、发本站请求、
 * 伪造界面骗密码。挡住"顶层打开"就够了，成本几乎为零。
 *
 * 只拦**顶层导航**（[com.nhyzcms.clubapp.shell.WebShellClient.shouldOverrideUrlLoading] 里
 * `isForMainFrame` 的分支）：页面里 `<img src="/media/x.svg">`、`<video>` 这类子资源完全不经过这里，
 * 所以正常的图片/视频展示、长按保存都不受影响。
 */
object ActiveContentPolicy {

    /** 拦截原因（日志与提示用；不含 URL，避免把用户内容写进日志）。 */
    const val REASON = "media-active-content"

    /** 会被浏览器当"网页/脚本"执行的扩展名。图片位图（png/jpg/gif/webp）不在其中。 */
    private val ACTIVE_EXTENSIONS = setOf(
        "html", "htm", "xhtml", "shtml",
        "svg", "svgz",
        "xml", "xsl", "xslt",
        "mhtml", "mht",
        "js", "mjs",
    )

    /**
     * 该 URL 是否属于「同源 /media/ 下的活跃内容」。
     *
     * @return 命中时返回 [REASON]，否则 null（放行）。
     */
    fun blockReason(url: String): String? {
        // URI 解析失败（畸形地址）不在这里下结论：交给 NavPolicy 判"畸形并拦截"。
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        // 用解码后的 path：`/media/a%2Ehtml` 也要命中。
        val path = uri.path ?: return null
        if (!path.startsWith("/media/", ignoreCase = true)) return null
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (ext in ACTIVE_EXTENSIONS) REASON else null
    }
}
