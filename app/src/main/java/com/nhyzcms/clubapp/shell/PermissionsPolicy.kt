package com.nhyzcms.clubapp.shell

import java.util.Locale

/**
 * 运行时权限策略（纯函数，可 JVM 单测）。
 *
 * 本块只涉及一个权限：**相机**（android.permission.CAMERA）。
 *
 * 为什么文件选择不需要权限：上传走系统选择器（FileChooserParams.createIntent()，
 * 最终由系统的 ACTION_GET_CONTENT 完成），系统会按 URI 授予临时读权限。
 * 因此**不申请**读取媒体库与读取外部存储这两类权限
 * （最小权限原则，也少一次国产 ROM 的弹窗骚扰）。下载同样不需要存储权限，见 [DownloadPolicy]。
 *
 * 为什么相机反而要：我们在 AndroidManifest 里声明了 CAMERA（这样相机入口才能稳定出现），
 * 而一旦声明，Android 就要求调用方自己持有该权限，否则 ACTION_IMAGE_CAPTURE 会被系统拒绝。
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

    /** 拍照需要申请的权限集合（只有相机）。 */
    fun capturePermissions(): List<String> = listOf(CAMERA)

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
