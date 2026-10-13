package com.nhyzcms.clubapp.shell.perf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存清理时机（区块 6）。
 *
 * 核心立场：**默认什么都不做**。服务器的 `/static/` 静态资源带 `immutable` 长缓存，
 * 是弱网下"不重复下载"的主要依靠 —— 只有"空间告急"或"太久没清"才允许动它。
 * 因此下面一半用例是在验证"不该清的时候确实没清"。
 */
class CacheTrimPolicyTest {

    private val normal = DeviceClassifier.profileOf(DeviceClass.NORMAL)
    private val constrained = DeviceClassifier.profileOf(DeviceClass.CONSTRAINED)
    private val mb = 1024L * 1024L
    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_800_000_000_000L

    @Test
    fun `首次运行不清（缓存本来就是空的）`() {
        assertFalse(
            CacheTrimPolicy.shouldTrimDiskCache(
                now,
                lastTrimMs = 0L,
                freeBytes = 8L * 1024L * mb,
                profile = normal,
            ),
        )
    }

    @Test
    fun `空间充足且没到期，一律不清`() {
        assertFalse(CacheTrimPolicy.shouldTrimDiskCache(now, now - 10 * day, 8L * 1024L * mb, normal))
    }

    @Test
    fun `太久没清（超过 30 天）才清一次`() {
        assertTrue(CacheTrimPolicy.shouldTrimDiskCache(now, now - 31 * day, 8L * 1024L * mb, normal))
    }

    @Test
    fun `空间低于档位阈值也会清（但要看最小间隔）`() {
        val lowFree = 100L * mb // 低于 normal 档的 128 MB 阈值
        assertTrue(
            "两天前清过、现在空间偏低 → 清",
            CacheTrimPolicy.shouldTrimDiskCache(now, now - 2 * day, lowFree, normal),
        )
        assertFalse(
            "一小时前刚清过 → 不清（避免抖动式反复清）",
            CacheTrimPolicy.shouldTrimDiskCache(now, now - 60L * 60L * 1000L, lowFree, normal),
        )
    }

    @Test
    fun `空间告急可以突破最小间隔`() {
        assertTrue(
            CacheTrimPolicy.shouldTrimDiskCache(
                now,
                lastTrimMs = now - 60_000L, // 一分钟前刚清过
                freeBytes = 10L * mb, // 远低于 CRITICAL_FREE_BYTES
                profile = normal,
            ),
        )
    }

    @Test
    fun `读不到剩余空间时只按年龄判断（不因读不到就狂清）`() {
        assertFalse(CacheTrimPolicy.shouldTrimDiskCache(now, now - 2 * day, freeBytes = -1L, profile = normal))
        assertTrue(CacheTrimPolicy.shouldTrimDiskCache(now, now - 31 * day, freeBytes = -1L, profile = normal))
    }

    @Test
    fun `受限设备的存储阈值更保守，更早开始清`() {
        val freeBytes = 200L * mb // 高于 normal 阈值 128 MB、低于受限阈值 256 MB
        assertFalse(CacheTrimPolicy.shouldTrimDiskCache(now, now - 2 * day, freeBytes, normal))
        assertTrue(CacheTrimPolicy.shouldTrimDiskCache(now, now - 2 * day, freeBytes, constrained))
    }

    @Test
    fun `内存压力：正常设备只响应 CRITICAL`() {
        val recent = now - 60_000L
        val anHourAgo = now - 60L * 60L * 1000L
        assertFalse("NONE 什么都不做", CacheTrimPolicy.shouldTrimMemoryCache(now, 0L, MemoryPressure.NONE, normal))
        assertFalse(
            "正常设备不响应 RUNNING_LOW",
            CacheTrimPolicy.shouldTrimMemoryCache(now, 0L, MemoryPressure.LOW, normal),
        )
        assertTrue(
            "CRITICAL 正常设备也响应",
            CacheTrimPolicy.shouldTrimMemoryCache(now, anHourAgo, MemoryPressure.CRITICAL, normal),
        )
        assertFalse(
            "但仍在节流窗口内就不重复清",
            CacheTrimPolicy.shouldTrimMemoryCache(now, recent, MemoryPressure.CRITICAL, normal),
        )
    }

    @Test
    fun `内存压力：受限设备连 RUNNING_LOW 也响应`() {
        assertTrue(CacheTrimPolicy.shouldTrimMemoryCache(now, 0L, MemoryPressure.LOW, constrained))
    }

    @Test
    fun `常量自洽：告急阈值小于两级存储阈值`() {
        assertTrue(CacheTrimPolicy.CRITICAL_FREE_BYTES < normal.diskTrimFreeBytesThreshold)
        assertTrue(normal.diskTrimFreeBytesThreshold <= constrained.diskTrimFreeBytesThreshold)
        assertTrue(CacheTrimPolicy.MIN_DISK_TRIM_INTERVAL_MS < CacheTrimPolicy.MAX_DISK_CACHE_AGE_MS)
    }
}
