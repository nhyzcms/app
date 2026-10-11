package com.nhyzcms.clubapp.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PermissionsPolicy] 的单测：只申请必要权限；拍照/下载两个入口的判定规则。 */
class PermissionsPolicyTest {

    @Test
    fun 拍照只申请相机一个权限() {
        assertEquals(listOf("android.permission.CAMERA"), PermissionsPolicy.capturePermissions())
    }

    @Test
    fun 下载权限只在_android_8_9_上申请() {
        assertEquals(
            listOf("android.permission.WRITE_EXTERNAL_STORAGE"),
            PermissionsPolicy.downloadPermissions(26),
        )
        assertEquals(
            listOf("android.permission.WRITE_EXTERNAL_STORAGE"),
            PermissionsPolicy.downloadPermissions(28),
        )
        assertEquals(emptyList<String>(), PermissionsPolicy.downloadPermissions(29))
        assertEquals(emptyList<String>(), PermissionsPolicy.downloadPermissions(34))
    }

    @Test
    fun 网站没有_capture_属性时也要能看到拍照入口() {
        // 关键回归：站点里的 input 是 accept="image/*"，没有 capture 属性
        assertTrue(PermissionsPolicy.shouldOfferCapture(false, arrayOf("image/*")))
        assertTrue(PermissionsPolicy.shouldOfferCapture(false, arrayOf("image/jpeg", "image/png")))
    }

    @Test
    fun accept_写成逗号串也能识别() {
        assertTrue(PermissionsPolicy.shouldOfferCapture(false, arrayOf("image/*,video/*")))
        assertTrue(PermissionsPolicy.shouldOfferCapture(false, arrayOf(" image/jpeg , image/webp ")))
    }

    @Test
    fun 非图片的_accept_不给拍照入口() {
        assertFalse(PermissionsPolicy.shouldOfferCapture(false, arrayOf("video/mp4")))
        assertFalse(PermissionsPolicy.shouldOfferCapture(false, arrayOf(".pdf,.docx")))
        assertFalse(PermissionsPolicy.shouldOfferCapture(false, arrayOf("*/*")))
        assertFalse(PermissionsPolicy.shouldOfferCapture(false, null))
        assertFalse(PermissionsPolicy.shouldOfferCapture(false, emptyArray()))
    }

    @Test
    fun 网页显式写了_capture_属性时优先() {
        assertTrue(PermissionsPolicy.shouldOfferCapture(true, null))
        assertTrue(PermissionsPolicy.shouldOfferCapture(true, arrayOf("video/mp4")))
    }
}
