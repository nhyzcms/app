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
     * 调试自检通知的**独立渠道**（只有 debug 包会用）。
     * 与未读渠道分开是有意的：自检要能弹出来让人看见，而它**不能**污染角标语义。
     */
    const val SELF_TEST_CHANNEL_ID = "push_selftest"

    /**
     * 「大而不必要」的静态资源路径前缀（站点**相对路径**，区块 6）。
     *
     * 目前只有 Live2D 看板娘：模型 + 4096 纹理在服务器上约 60 MB，而服务器只有 3 M 带宽、
     * 无 CDN，用户也明确要求「App 上不展示 live2d」。
     *
     * 网站侧已经按「窗口宽度 ≤ 1024 px 不挂载看板娘」挡住（实测 2026-10-13：
     * App UA 在生产 access.log 里**没有任何 `/static/live2d/` 请求**），
     * 所以壳侧这层是**兜底** —— 网站改规则、或个别 ROM 上报的视口偏大时也不会白下几十 MB。
     * 命中后由 shell/perf/PerfHost 返回「空体 200」，网页自然降级。
     * 清单开关 `blockLive2dAssets`（默认开）可远程关掉。
     *
     * 换站点路径时改这里（唯一真源），不要在拦截器里写死字符串。
     */
    val BLOCKED_ASSET_PREFIXES: List<String> = listOf("/static/live2d/")

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

    /**
     * **原生层**请求（HttpURLConnection / SSLSocket）额外带的标记（区块 5 起）。
     *
     * 为什么需要：诊断时要能从服务器 access.log 里分清"这条请求是网页发的还是壳的原生层发的"。
     * 而 WebView 的 UA 与 `WebSettings.getDefaultUserAgent()` 的 UA 几乎一模一样
     * （都含 `NhyzClubApp/...` 标记），2026-10-13 排查"真机完全没反应"时就卡在这里：
     * 只能靠"HTTP/1.1 + 无 Referer"间接推断，太绕。加上这个后缀后一眼可辨。
     */
    fun nativeUaMarker(): String =
        "NhyzClubShell/" + BuildConfig.VERSION_NAME + " (native; " + BuildConfig.VERSION_CODE + ")"

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
