package com.nhyzcms.clubapp.shell.push

import android.webkit.CookieManager

/**
 * 会话 Cookie 快照。
 *
 * 为什么需要它：WebSocket 与未读接口都要**登录态**，而登录态在 WebView 的 Cookie 库里
 * （HttpOnly 的 `sessionid` 只在 CookieManager 里，网页 JS 读不到）。原生连接必须自己带上它。
 *
 * 线程约定（重要）：CookieManager 属于 WebView，只能在**主线程**访问——
 * 与 2026-10-12 那次启动闪退同源（后台线程读 `webView.settings` 会直接抛 RuntimeException，
 * 见 docs/FIX-20261012-STARTUP-CRASH.md）。所以本类只在主线程取**不可变快照**，
 * 再把字符串交给后台线程使用，绝不在 I/O 线程里回调这里。
 */
object SessionCookies {

    private const val SESSION_KEY = "sessionid="

    /** 取本站 cookie 串（形如 sessionid=...; csrftoken=...）；没有 cookie 时返回 null。 */
    fun snapshot(origin: String): String? {
        val raw = runCatching { CookieManager.getInstance().getCookie(origin) }.getOrNull()
        return raw?.takeIf { it.isNotBlank() }
    }

    /** 是否已登录：以 sessionid 是否存在为准（未登录就不该连 WS、也不该显示角标）。 */
    fun isLoggedIn(cookie: String?): Boolean = cookie != null && cookie.contains(SESSION_KEY)
}
