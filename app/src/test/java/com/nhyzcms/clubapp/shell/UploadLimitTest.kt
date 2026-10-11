package com.nhyzcms.clubapp.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [UploadLimit] 的单测：上限边界、多文件求和、文案格式。 */
class UploadLimitTest {

    @Test
    fun 上限是_20_MB() {
        assertEquals(20L * 1024L * 1024L, UploadLimit.MAX_BYTES)
    }

    @Test
    fun 空选择或大小未知时不拦() {
        assertEquals(UploadLimit.Verdict.Allowed, UploadLimit.check(emptyList()))
        assertEquals(UploadLimit.Verdict.Allowed, UploadLimit.check(listOf(null, null)))
    }

    @Test
    fun 恰好等于上限通过() {
        assertEquals(UploadLimit.Verdict.Allowed, UploadLimit.check(listOf(UploadLimit.MAX_BYTES)))
    }

    @Test
    fun 超过一字节即拦下() {
        val verdict = UploadLimit.check(listOf(UploadLimit.MAX_BYTES + 1))
        assertTrue(verdict is UploadLimit.Verdict.Exceeded)
        assertEquals(UploadLimit.MAX_BYTES, (verdict as UploadLimit.Verdict.Exceeded).limitBytes)
    }

    @Test
    fun 多选文件按总和判定() {
        val half = UploadLimit.MAX_BYTES / 2
        assertTrue(UploadLimit.check(listOf(half, half, 1L)) is UploadLimit.Verdict.Exceeded)
        assertEquals(UploadLimit.Verdict.Allowed, UploadLimit.check(listOf(half, half)))
    }

    @Test
    fun 大小文案可读且不补多余小数() {
        assertEquals("512 B", UploadLimit.formatBytes(512))
        assertEquals("1 KB", UploadLimit.formatBytes(1024))
        assertEquals("20 MB", UploadLimit.formatBytes(UploadLimit.MAX_BYTES))
        assertEquals("23.4 MB", UploadLimit.formatBytes(24_536_678L))
    }

    @Test
    fun 负值不崩() {
        assertEquals("未知", UploadLimit.formatBytes(-1))
    }
}
