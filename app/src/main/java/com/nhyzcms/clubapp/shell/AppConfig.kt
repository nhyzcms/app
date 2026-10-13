package com.nhyzcms.clubapp.shell

import com.nhyzcms.clubapp.BuildConfig

/**
 * 壳的静态配置（唯一真源）。
 *
 * 改站点地址只改本文件；跨仓库约定见 docs/ARCHITECTURE.md 第 5 节。
 */
object AppConfig {

    /** 生产站点 Origin。仅 HTTPS：明文会被导航策略与 network-security-config 双重拦截。 */
    const val SITE_ORIGIN = "https://8.153.145.175"

    /**
     * 允许在壳内打开的主机白名单。
     * 换域名/改用域名访问时必须同步修改，否则所有站内跳转都会被判为外链交给浏览器。
     */
    val ALLOWED_HOSTS: Set<String> = setOf("8.153.145.175")

    /** 起始路径：默认手机版首页。区块 3 起可由 app-manifest.json 的 startRoute 覆盖。 */
    const val START_PATH = "/#/m"

    val START_URL: String = SITE_ORIGIN + START_PATH

    /** 站点主机名（推送与未读接口共用；换域名时与 ALLOWED_HOSTS 一起改）。 */
    const val SITE_HOST = "8.153.145.175"

    /**
     * 推送 WebSocket 端点（区块 5）。
     * 站点侧契约：`/ws/messaging/` 需登录（AuthMiddlewareStack + AllowedHostsOriginValidator），
     * 由 nginx 的 `location /` 转发 Upgrade（见 docs/PUSH-CONTRACT.md）。
     */
    const val WS_PATH = "/ws/messaging/"
    const val WS_PORT = 443

    /** 未读总数接口（与网页 messagingApi.unreadCount / notificationUnreadCount 同源）。 */
    const val DM_UNREAD_URL = SITE_ORIGIN + "/messaging/conversations/unread_count/"
    const val NOTIFICATION_UNREAD_URL = SITE_ORIGIN + "/messaging/notifications/unread_count/"

    /** 未读通知的渠道 id（launcher 角标由它驱动，见 shell/push/PushNotifications）。 */
    const val NOTIFICATION_CHANNEL_ID = "unread"

    /**
     * FileProvider authority 的**唯一真源**（拍照临时文件与分享图片都用它）。
     *
     * 必须与 AndroidManifest 里 provider 的 android:authorities="${applicationId}.fileprovider" 一致；
     * 用 BuildConfig.APPLICATION_ID 而不是写死包名：debug 变体带 .debug 后缀，
     * 写死包名会让 debug 包的拍照/分享在运行时炸掉。
     */
    val FILE_PROVIDER_AUTHORITY: String = BuildConfig.APPLICATION_ID + ".fileprovider"

    /**
     * UA 标记：网站靠它识别"来自 App"并得知壳版本（契约见 ARCHITECTURE 第 5.1 节）。
     * 格式固定为 `NhyzClubApp/<versionName> (Android; <versionCode>)`，不要在中间插空格。
     */
    fun uaMarker(): String =
        "NhyzClubApp/" + BuildConfig.VERSION_NAME + " (Android; " + BuildConfig.VERSION_CODE + ")"

    /** 允许交给系统的非 http(s) scheme；其它 scheme 一律拦截（安全文档第 3 节）。 */
    val SYSTEM_SCHEMES: Set<String> = setOf("tel", "mailto", "sms", "smsto", "geo", "market")

    /** 更新清单地址（区块 3 使用；nginx 静态文件，不经 Django）。 */
    const val UPDATE_MANIFEST_URL = SITE_ORIGIN + "/app-update/manifest.json"

    /**
     * 允许的更新主机白名单（清单地址与 APK 下载地址共用，唯一真源）。
     *
     * 为什么需要它：更新流程会"下载并安装"——这是本 App 权限最大的一条路径，
     * 因此清单里写的任何地址都必须落在我们自己可控的主机内：
     *  - `8.153.145.175`：自托管（首选，见 docs/UPDATE-STRATEGY.md 第 4 节）；
     *  - `github.com` / `objects.githubusercontent.com`：GitHub Release 备用源。
     * 换域名或换托管时必须同步改这里，否则更新会被安全策略判为非法并忽略。
     */
    val UPDATE_ALLOWED_HOSTS: Set<String> = setOf(
        "8.153.145.175",
        "github.com",
        "objects.githubusercontent.com",
    )
}
