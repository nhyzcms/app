package com.nhyzcms.clubapp.shell

/** 主文档加载失败的分类，决定错误面板的文案与是否需要告警。 */
enum class ShellErrorKind {
    /** 断网 / DNS / 服务器不可达。 */
    NETWORK,

    /** TLS 证书校验失败（可能是公共 WiFi 劫持，也可能是站点证书到期）。 */
    TLS,

    /** 明文 http 链接被拦截。 */
    BLOCKED_CLEARTEXT,

    /** 其它未知错误。 */
    GENERIC,
    ;

    /** 安全类错误不允许静默忽略，必须明确告知用户（安全文档 §2）。 */
    val isSecurityRelated: Boolean
        get() = this == TLS || this == BLOCKED_CLEARTEXT
}
