package com.nhyzcms.clubapp.shell

import java.util.Locale

/**
 * 运行时权限策略（纯函数，可 JVM 单测）。
 *
 * 本块只涉及一个权限：**相机**（`android.permission.CAMERA`）。
 *
 * 为什么文件选择不需要权限：上传走系统选择器（`FileChooserParams.createIntent()` →
 * `ACTION_GET_CONTENT`），系统按 URI 授予临时读权限。因此**不申请**
 * `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`（最小权限，也少一次国产 ROM 的弹窗骚扰）。
 * 下载同样不需要存储权限，见 [DownloadPolicy]。
 *
 * 为什么相机反而要：我们在 AndroidManifest 里声明了 CAMERA（这样相机入口才能稳定出现），
 * 而一旦声明，Android 就要求调用方自己持有该权限，否则 ACTION_IMAGE_CAPTURE 会被系统拒绝。
 */
object PermissionsPolicy {

    const val CAMERA = "android.permission.CAMERA"

    /** 拍照需要申请的权限集合（只有相机）。 */
    fun capturePermissions(): List<String> = listOf(CAMERA)

    /**
     * 是否应当把「拍照」塞进文件选择器。
     *
     * 注意：网站里的 `<input type="file">` 用的是 `accept="image/*,video/*"` 这类写法，
     * **没有** `capture` 属性，所以不能只看 captureRequested —— 否则拍照入口永远不会出现。
     *
     * @param captureRequested `FileChooserParams.isCaptureEnabled()`（网页写了 capture 属性时为 true）。
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
        return tokens.any { it == "image/*" || it.startsWith("image/") }
    }
}
