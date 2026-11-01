package com.nhyzcms.clubapp.shell

/** 主文档加载失败的分类，决定错误面板的文案与是否需要告警。 */
enum class ShellErrorKind {
    /** 断网 / DNS / 服务器不可达。 */
    NETWORK,

    /** TLS 证书校验失败（可能是公共 WiFi 劫持，也可能是站点证书到期）。 */
    TLS,

    /** 明文 http 链接被拦截。 */
    BLOCKED_CLEARTEXT,

    /**
     * 服务端主动限流（HTTP 429）。
     *
     * 网站 2026-10 的安全审计给登录、找回密码、公开投票等入口加了限流，nginx 的
     * `limit_req` 也可能把主文档打成 429。这时候空白页会让人以为 App 坏了，
     * 明确说"请求太频繁，稍后重试"才是对的。
     */
    RATE_LIMITED,

    /** 服务端拒绝（HTTP 403）：多半是权限不足或会话失效，提示刷新重试即可。 */
    FORBIDDEN,

    /** 其它未知错误。 */
    GENERIC,
    ;

    /** 安全类错误不允许静默忽略，必须明确告知用户（安全文档 §2）。 */
    val isSecurityRelated: Boolean
        get() = this == TLS || this == BLOCKED_CLEARTEXT
}
