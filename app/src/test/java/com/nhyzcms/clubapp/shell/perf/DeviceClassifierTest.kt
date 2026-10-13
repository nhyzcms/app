package com.nhyzcms.clubapp.shell.perf

import com.nhyzcms.clubapp.shell.feel.ProbeCadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备分级与派生参数（区块 6）。
 *
 * 为什么值得单测：这两个档位的数值直接决定"老手机上还卡不卡"，
 * 而且"读不到系统值"（memoryClass=0 / 空间=-1）时必须退回正常档 ——
 * 判错的代价是正常手机被莫名降级（探测变慢、缓存被清）。
 */
class DeviceClassifierTest {

    private val gb: Long = 1024L * 1024L * 1024L

    @Test
    fun `正常设备`() {
        assertEquals(
            DeviceClass.NORMAL,
            DeviceClassifier.classify(isLowRamDevice = false, memoryClassMb = 256, freeBytes = 8L * gb),
        )
    }

    @Test
    fun `系统自报低内存设备`() {
        assertEquals(
            DeviceClass.CONSTRAINED,
            DeviceClassifier.classify(isLowRamDevice = true, memoryClassMb = 512, freeBytes = 20L * gb),
        )
    }

    @Test
    fun `memoryClass 偏低也算低内存`() {
        assertEquals(
            DeviceClass.CONSTRAINED,
            DeviceClassifier.classify(isLowRamDevice = false, memoryClassMb = 96, freeBytes = 8L * gb),
        )
        assertEquals(
            "128 MB 是边界，不低于它不算低内存",
            DeviceClass.NORMAL,
            DeviceClassifier.classify(isLowRamDevice = false, memoryClassMb = 128, freeBytes = 8L * gb),
        )
    }

    @Test
    fun `读不到系统值时不降级（宁可当作正常设备）`() {
        assertEquals(
            DeviceClass.NORMAL,
            DeviceClassifier.classify(isLowRamDevice = false, memoryClassMb = 0, freeBytes = 8L * gb),
        )
        assertEquals(
            DeviceClass.NORMAL,
            DeviceClassifier.classify(isLowRamDevice = false, memoryClassMb = 256, freeBytes = -1L),
        )
    }

    @Test
    fun `存储紧张算受限设备`() {
        assertEquals(
            DeviceClass.CONSTRAINED,
            DeviceClassifier.classify(
                isLowRamDevice = false,
                memoryClassMb = 256,
                freeBytes = 100L * 1024L * 1024L,
            ),
        )
    }

    @Test
    fun `正常档参数`() {
        val profile = DeviceClassifier.profileOf(DeviceClass.NORMAL)
        assertEquals(2_000L, profile.probeBaseIntervalMs)
        assertEquals(15_000L, profile.probeMaxIntervalMs)
        assertEquals(false, profile.trimMemoryOnLowRam)
        assertEquals(128L * 1024L * 1024L, profile.diskTrimFreeBytesThreshold)
    }

    @Test
    fun `受限档参数：探测更慢、内存缓存更愿意清、存储阈值更保守`() {
        val profile = DeviceClassifier.profileOf(DeviceClass.CONSTRAINED)
        val normal = DeviceClassifier.profileOf(DeviceClass.NORMAL)
        assertTrue(profile.probeBaseIntervalMs > normal.probeBaseIntervalMs)
        assertTrue(profile.probeMaxIntervalMs > normal.probeMaxIntervalMs)
        assertTrue(profile.trimMemoryOnLowRam)
        assertTrue(profile.diskTrimFreeBytesThreshold > normal.diskTrimFreeBytesThreshold)
    }

    @Test
    fun `两个档位的参数都自洽（上限大于等于基础，且不低于探测硬下限）`() {
        for (deviceClass in DeviceClass.values()) {
            val profile = DeviceClassifier.profileOf(deviceClass)
            assertTrue("$deviceClass: 上限必须不小于基础", profile.probeMaxIntervalMs >= profile.probeBaseIntervalMs)
            assertTrue(
                "$deviceClass: 基础间隔不得低于探测硬下限",
                profile.probeBaseIntervalMs >= ProbeCadence.MIN_INTERVAL_MS,
            )
            assertTrue("$deviceClass: 磁盘阈值必须为正", profile.diskTrimFreeBytesThreshold > 0L)
        }
    }
}
