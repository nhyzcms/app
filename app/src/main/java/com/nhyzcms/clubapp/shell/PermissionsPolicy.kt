package com.nhyzcms.clubapp.shell

import java.util.Locale

/**
 * 运行时权限策略（纯函数，可 JVM 单测）。
 *
 * 本块涉及两个权限，都是“能不申请就不申请”的结果：
 *
 * [CAMERA] —— 拍照上传需要。因为我们在清单里声明了它（这样相机入口才能稳定出现），
 * 而一旦声明，Android 就要求调用方自己持有，否则 ACTION_IMAGE_CAPTURE 会被拒。
 *
 * [WRITE_EXTERNAL_STORAGE] —— 只在 Android 8/9（API ≤ 28）上需要：
 * DownloadManager 写公共「下载」目录在这两个版本要求写权限（Android 10 起不再需要）。
 * 清单里用 maxSdkVersion="28" 限定，Android 10+ 的机器上是“声明但不申请也不生效”。
 * 管理员已确认（2026-10-11）可以把下载落到公共目录；权限被拒则退回 App 目录，不影响下载本身。
 *
 * 文件选择（图片/文档/视频）**不需要任何权限**：走系统 SAF，系统按 URI 授予临时读权限。
 * 所以读取媒体库与读取外部存储这两类权限一个都不申请。
 *
 * ⚠️ 写注释的坑（区块 2 踩过一次，CI 才暴露）：Kotlin 的块注释**支持嵌套**，
 * 所以块注释里不能出现「斜杠 + 星号」这两个连续字符（例如 MIME 通配的写法），
 * 否则注释不会在预期的位置结束，而是把文件剩余部分全吞掉，
 * 报错却是 Unclosed comment / Unresolved reference，极难定位。
 * 要举例就写在线注释（双斜杠）里，或把星号写成文字（已加进 scripts/preflight.sh）。
 * （字符串字面量里没有这个问题，只有块注释会嵌套。）
 */
object PermissionsPolicy {

    const val CAMERA = "android.permission.CAMERA"

    const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"

    /** 拍照需要申请的权限集合（只有相机）。 */
    fun capturePermissions(): List<String> = listOf(CAMERA)

    /**
     * 下载落盘需要申请的权限：只有 Android 8/9（API ≤ 28）需要写外部存储。
     * Android 10 及以上返回空列表（DownloadManager 直写公共「下载」目录无需权限）。
     */
    fun downloadPermissions(sdkInt: Int): List<String> =
        if (sdkInt <= 28) listOf(WRITE_EXTERNAL_STORAGE) else emptyList()

    /**
     * 是否应当把「拍照」塞进文件选择器。
     *
     * 注意：网站里的 input 元素用的是 image 通配的 accept（形如 image 加斜杠加星号），
     * **没有** capture 属性，所以不能只看 captureRequested —— 否则拍照入口永远不会出现。
     *
     * @param captureRequested FileChooserParams.isCaptureEnabled()（网页写了 capture 属性时为 true）。
     * @param acceptTypes      网页 accept 的值，浏览器可能按逗号拆好，也可能给一整串。
     */
    fun shouldOfferCapture(captureRequested: Boolean, acceptTypes: Array<String>?): Boolean {
        if (captureRequested) return true
        val tokens = acceptTypes
            ?.flatMap { it.split(',') }
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (tokens.isEmpty()) return false
        return tokens.any { it.startsWith("image/") }
    }
}
