package com.nhyzcms.clubapp.shell

import com.nhyzcms.clubapp.BuildConfig

/**
 * 壳的静态配置（唯一真源）。
 *
 * 改站点地址只改本文件；跨仓库约定见 docs/ARCHITECTURE.md §5。
 */
object AppConfig {

    /** 生产站点 Origin。仅 HTTPS：明文会被导航策略与 network-security-config 双重拦截。 */
    const val SITE_ORIGIN = "https://8.153.145.175"

    /**
     * 允许在壳内打开的主机白名单。
     * ❗ 换域名/改用域名访问时必须同步修改，否则所有站内跳转都会被判为外链交给浏览器。
     */
    val ALLOWED_HOSTS: Set<String> = setOf("8.153.145.175")

    /** 起始路径：默认手机版首页。区块 3 起可由 app-manifest.json 的 startRoute 覆盖。 */
    const val START_PATH = "/#/m"

    val START_URL: String = SITE_ORIGIN + START_PATH

    /**
     * UA 标记：网站靠它识别"来自 App"并得知壳版本（契约见 ARCHITECTURE §5.1）。
     * 格式固定为 `NhyzClubApp/<versionName> (Android; <versionCode>)`，不要在中间插空格。
     */
    fun uaMarker(): String =
        "NhyzClubApp/" + BuildConfig.VERSION_NAME + " (Android; " + BuildConfig.VERSION_CODE + ")"

    /** 允许交给系统的非 http(s) scheme；其它 scheme 一律拦截（安全文档 §3）。 */
    val SYSTEM_SCHEMES: Set<String> = setOf("tel", "mailto", "sms", "smsto", "geo", "market")

    /** 更新清单地址（区块 3 使用；nginx 静态文件，不经 Django）。 */
    const val UPDATE_MANIFEST_URL = SITE_ORIGIN + "/app-update/manifest.json"
}
