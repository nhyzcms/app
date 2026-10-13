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
 *
 * ⚠️ 2026-10-13 实测到的坑：**冷启动刚开始时 `getCookie()` 可能返回空**
 * （Cookie 库异步加载尚未完成）。当时只在 `onActivityStarted` 取一次快照，
 * 拿到空就整条链路静默放弃、之后再不重试 → 真机上"完全没反应"
 * （见 docs/FIX-20261013-PUSH-NOT-FIRING.md）。
 * 现在的做法：主线程每 15 秒重新取一次快照（见 [PushHost] 的看门狗），
 * 从"无会话"变成"有会话"就自动把推送拉起来。
 */
object SessionCookies {

    /** 取本站 cookie 串（形如 sessionid=...; csrftoken=...）；没有 cookie 时返回 null。 */
    fun snapshot(origin: String): String? {
        val raw = runCatching { CookieManager.getInstance().getCookie(origin) }.getOrNull()
        return raw?.takeIf { it.isNotBlank() }
    }

    /** 便捷判定（纯逻辑实现在 [PushSession] 里，这里只是转发，便于调用方少一个 import）。 */
    fun isLoggedIn(cookie: String?): Boolean = PushSession.isLoggedIn(cookie)
}
