package com.nhyzcms.clubapp.shell.push

/**
 * 登录态判定（纯逻辑，不碰 Android）。
 *
 * 为什么单独抽出来：**冷启动时 `CookieManager.getCookie()` 可能在 Cookie 库就绪前返回空**，
 * 于是"有没有登录"这件事要在主线程**反复**判定（见 [PushHost] 的看门狗），
 * 而判定本身不该依赖 Android 框架——把它留成纯函数，[PushSocket] 也就跟着变成纯逻辑文件，
 * 能进本地编译链（本机没有 Android SDK，能本地编译的越多越好）。
 */
object PushSession {

    private const val SESSION_KEY = "sessionid="

    /** 是否已登录：以 `sessionid` 是否存在为准（未登录就不该连 WS、也不该显示角标）。 */
    fun isLoggedIn(cookie: String?): Boolean = cookie != null && cookie.contains(SESSION_KEY)

    /** 仅供日志/诊断：不打印 cookie 内容，只说有没有。 */
    fun describe(cookie: String?): String = if (isLoggedIn(cookie)) "有会话" else "无会话"
}
