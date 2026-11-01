package com.nhyzcms.clubapp.shell.security

import java.util.Locale

/** `WebChromeClient.onPermissionRequest` 的裁决结果。 */
sealed interface WebPermissionDecision {
    /** 只授予 [resources] 里的这几项（绝不整包转交网页要什么给什么）。 */
    data class Grant(val resources: List<String>) : WebPermissionDecision

    /** 拒绝，并给出可写日志的原因（不含 URL/查询串）。 */
    data class Deny(val reason: String) : WebPermissionDecision
}

/**
 * 网页 `getUserMedia` 的权限裁决（纯函数，可 JVM 单测）。
 *
 * 为什么现在需要它：网站 `/#/v`（社员查询）用 `getUserMedia` 扫工牌条码。
 * 没接 `onPermissionRequest` 之前，WebView 一律 deny —— 壳里那个按钮永远只会报"权限被拒绝"，
 * 用户只能手输编号。接上之后必须**同时**满足三条才放行：
 *
 *  1. 请求来自**本站 Origin**（`AppConfig.SITE_ORIGIN`，scheme + host + port 全等）——
 *     外部网页一律拒绝，避免第三方页面借壳要摄像头；
 *  2. 只要 `VIDEO_CAPTURE`（**麦克风 AUDIO_CAPTURE 永远不给**，本站没有录音需求）；
 *  3. 系统层的 CAMERA 运行时权限已经授予（没给就先引导用户授权，不静默放行）。
 *
 * 拒绝理由只写原因码与 host，不写完整 URL（日志脱敏，同 SECURITY §2 的做法）。
 */
object WebPermissionPolicy {

    /** 与 `android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE` 的字面值一致。 */
    const val RES_VIDEO_CAPTURE = "android.webkit.resource.VIDEO_CAPTURE"

    /** 与 `android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE` 的字面值一致（永不授予）。 */
    const val RES_AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE"

    const val REASON_FOREIGN_ORIGIN = "origin"
    const val REASON_NO_SUPPORTED_RESOURCE = "resource"
    const val REASON_CAMERA_PERMISSION = "camera-permission"

    /**
     * @param origin        `PermissionRequest.origin` 的字符串形式（如 `https://8.153.145.175`）
     * @param requested     网页申请的资源列表（`PermissionRequest.resources`）
     * @param cameraGranted 系统层 CAMERA 权限是否已授予
     * @param siteOrigin    本站 Origin（唯一真源：`AppConfig.SITE_ORIGIN`）
     */
    fun decide(
        origin: String?,
        requested: Collection<String>,
        cameraGranted: Boolean,
        siteOrigin: String,
    ): WebPermissionDecision {
        if (!sameOrigin(origin, siteOrigin)) {
            return WebPermissionDecision.Deny(REASON_FOREIGN_ORIGIN)
        }
        if (RES_VIDEO_CAPTURE !in requested) {
            // 只要有一项是我们不支持的（例如 AUDIO_CAPTURE），就整单拒绝：
            // 部分授予音频没有意义，而"网页要什么给什么"是这类桥最典型的翻车方式。
            return WebPermissionDecision.Deny(REASON_NO_SUPPORTED_RESOURCE)
        }
        if (!cameraGranted) {
            return WebPermissionDecision.Deny(REASON_CAMERA_PERMISSION)
        }
        return WebPermissionDecision.Grant(listOf(RES_VIDEO_CAPTURE))
    }

    /** Origin 归一化比较：忽略大小写与结尾斜杠（`https://host/` 与 `https://host` 等价）。 */
    fun sameOrigin(origin: String?, siteOrigin: String): Boolean {
        if (origin.isNullOrBlank()) return false
        return normalize(origin) == normalize(siteOrigin)
    }

    private fun normalize(origin: String): String =
        origin.trim().trimEnd('/').lowercase(Locale.ROOT)
}
