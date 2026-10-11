package com.nhyzcms.clubapp.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MediaSharePolicy] 的单测：分享图片文件本体前的前置判定。 */
class MediaSharePolicyTest {

    @Test
    fun https_图片可以分享() {
        assertEquals(
            MediaSharePolicy.Verdict.Allowed,
            MediaSharePolicy.check("https://8.153.145.175/media/a.jpg", MediaSharePolicy.UNKNOWN_SIZE),
        )
        assertEquals(
            MediaSharePolicy.Verdict.Allowed,
            MediaSharePolicy.check("https://8.153.145.175/media/a.jpg", 1024),
        )
    }

    @Test
    fun 不能下载的地址一律不可分享() {
        assertEquals(
            MediaSharePolicy.Verdict.Rejected(DownloadPolicy.REASON_MISSING),
            MediaSharePolicy.check(null, MediaSharePolicy.UNKNOWN_SIZE),
        )
        assertTrue(
            MediaSharePolicy.check("blob:https://8.153.145.175/uuid", MediaSharePolicy.UNKNOWN_SIZE)
                is MediaSharePolicy.Verdict.Rejected,
        )
        assertTrue(
            MediaSharePolicy.check("http://8.153.145.175/a.jpg", MediaSharePolicy.UNKNOWN_SIZE)
                is MediaSharePolicy.Verdict.Rejected,
        )
    }

    @Test
    fun 已知超大时直接拒绝() {
        val tooBig = MediaSharePolicy.MAX_SHARE_BYTES + 1
        assertEquals(MediaSharePolicy.Verdict.TooLarge(tooBig), MediaSharePolicy.check("https://x/a.jpg", tooBig))
    }

    @Test
    fun 恰好等于上限仍允许() {
        assertEquals(
            MediaSharePolicy.Verdict.Allowed,
            MediaSharePolicy.check("https://x/a.jpg", MediaSharePolicy.MAX_SHARE_BYTES),
        )
    }
}
