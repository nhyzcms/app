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
     * FileProvider authority 的**唯一真源**（拍照临时文件与分享图片都用它）。
     *
     * 必须与 AndroidManifest 里 provider 的 android:authorities="${applicationId}.fileprovider" 一致；
     * 用 BuildConfig.APPLICATION_ID 而不是写死包名：debug 变体带 .debug 后缀，
     * 写死包名会让 debug 包的拍照/分享在运行时炸掉。
     */
    val FILE_PROVIDER_AUTHORITY: String = BuildConfig.APPLICATION_ID + ".fileprovider"

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

    /**
     * 允许的更新主机白名单（清单地址与 APK 下载地址共用，唯一真源）。
     *
     * 为什么需要它：更新流程会"下载并安装"——这是本 App 权限最大的一条路径，
     * 因此清单里写的任何地址都必须落在我们自己可控的主机内：
     *  - `8.153.145.175`：自托管（首选，见 docs/UPDATE-STRATEGY.md §4）；
     *  - `github.com` / `objects.githubusercontent.com`：GitHub Release 备用源。
     * 换域名或换托管时必须同步改这里，否则更新会被安全策略判为非法并忽略。
     */
    val UPDATE_ALLOWED_HOSTS: Set<String> = setOf(
        "8.153.145.175",
        "github.com",
        "objects.githubusercontent.com",
    )
}
