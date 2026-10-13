package com.nhyzcms.clubapp.shell.perf

/**
 * 设备分级（纯逻辑）：决定探测节奏与缓存策略的档位。
 *
 * 面向的现实：本项目要照顾「学生手机」——大量 2~4 GB 内存、存储常年只剩几个 GB 的老机器。
 * 在这些机器上，壳侧每 2 秒一次的探测、以及一个可能涨到几百 MB 的 WebView 缓存，
 * 都是实打实的代价（掉帧、被系统杀、存储告警）。分档后它们自动降级。
 */
enum class DeviceClass {
    /** 内存与存储都正常：探测 2 s 起、上限 15 s。 */
    NORMAL,

    /** 低内存 / 存储紧张：探测放缓，内存缓存该清就清。 */
    CONSTRAINED,
}

/** 由设备分级派生出的运行参数（纯数据，便于单测与写进文档）。 */
data class PerfProfile(
    val deviceClass: DeviceClass,
    /** 状态探测基础间隔（毫秒）→ [com.nhyzcms.clubapp.shell.feel.ProbeCadence]。 */
    val probeBaseIntervalMs: Long,
    /** 状态探测间隔上限（毫秒）。 */
    val probeMaxIntervalMs: Long,
    /** 连「轻微内存偏低」也要清 WebView 内存缓存吗（低端机 yes，正常机只响应 critical）。 */
    val trimMemoryOnLowRam: Boolean,
    /** 剩余空间低于它就按「存储紧张」处理（见 [CacheTrimPolicy]）。 */
    val diskTrimFreeBytesThreshold: Long,
)

object DeviceClassifier {

    /** memoryClass 低于它按低内存对待（128 MB 与 Android 判定 lowRamDevice 的量级一致）。 */
    const val LOW_RAM_MEMORY_CLASS_MB = 128

    /** 剩余空间低于它算「存储紧张」（站点素材本身就有 49 MB 全景 + 60 MB live2d）。 */
    const val TIGHT_FREE_BYTES: Long = 256L * 1024L * 1024L

    /**
     * 判定设备档位。三个输入任一「明显紧张」即按 [DeviceClass.CONSTRAINED] 处理。
     *
     * @param isLowRamDevice ActivityManager.isLowRamDevice（系统自己的判定）
     * @param memoryClassMb  ActivityManager.memoryClass（0/负数 = 读不到 → 不参与判定）
     * @param freeBytes      缓存分区可用空间（负数 = 读不到 → 不参与判定）
     */
    fun classify(isLowRamDevice: Boolean, memoryClassMb: Int, freeBytes: Long): DeviceClass {
        if (isLowRamDevice) return DeviceClass.CONSTRAINED
        if (memoryClassMb in 1 until LOW_RAM_MEMORY_CLASS_MB) return DeviceClass.CONSTRAINED
        if (freeBytes in 0 until TIGHT_FREE_BYTES) return DeviceClass.CONSTRAINED
        return DeviceClass.NORMAL
    }

    /** 档位 → 运行参数。改数值只改这里（探测节奏与缓存策略都读它）。 */
    fun profileOf(deviceClass: DeviceClass): PerfProfile = when (deviceClass) {
        DeviceClass.NORMAL -> PerfProfile(
            deviceClass = deviceClass,
            probeBaseIntervalMs = 2_000L,
            probeMaxIntervalMs = 15_000L,
            trimMemoryOnLowRam = false,
            diskTrimFreeBytesThreshold = 128L * 1024L * 1024L,
        )

        DeviceClass.CONSTRAINED -> PerfProfile(
            deviceClass = deviceClass,
            probeBaseIntervalMs = 3_000L,
            probeMaxIntervalMs = 30_000L,
            trimMemoryOnLowRam = true,
            diskTrimFreeBytesThreshold = TIGHT_FREE_BYTES,
        )
    }
}
