package com.nhyzcms.clubapp.shell.perf

/** 系统内存压力档位（由 Activity.onTrimMemory 的 level 映射而来，见 [PerfHost]）。 */
enum class MemoryPressure {
    /** UI_HIDDEN / BACKGROUND / MODERATE 等，不算压力。 */
    NONE,

    /** TRIM_MEMORY_RUNNING_LOW：只有低端机才响应（见 [PerfProfile.trimMemoryOnLowRam]）。 */
    LOW,

    /** TRIM_MEMORY_RUNNING_CRITICAL / COMPLETE：正常机也要响应。 */
    CRITICAL,
}

/**
 * WebView 缓存清理时机（**纯逻辑**，不 import android.*，可在 JVM 单测）。
 *
 * ## 为什么需要"时机"而不是"每次启动都清"
 * 服务器的 `/static/` 静态资源带 `immutable` 长缓存，是弱网下"不重复下载"的主要依靠；
 * 每次启动都 `clearCache(true)` 等于把这份优势扔掉。所以磁盘缓存只在两种情况下清：
 *   · **空间紧张**（剩余低于档位阈值）—— 必须让位；
 *   · **太久没清**（超过 [MAX_DISK_CACHE_AGE_MS]）—— 防它无限增长。
 * 两种情况都有最小间隔 [MIN_DISK_TRIM_INTERVAL_MS]（"空间告急"除外），避免抖动式反复清。
 *
 * 内存缓存（`clearCache(false)`）只在系统明确报告内存压力时清，且同一间隔内最多一次。
 *
 * ⚠️ 两者都**不动 Cookie**（登录态），只清资源缓存。
 */
object CacheTrimPolicy {

    /** 磁盘缓存"最大年龄"：超过就清一次（站点素材多，缓存迟早涨到几百 MB）。 */
    const val MAX_DISK_CACHE_AGE_MS: Long = 30L * 24L * 60L * 60L * 1000L

    /** 两次磁盘清理之间的最小间隔（"空间告急"可突破）。 */
    const val MIN_DISK_TRIM_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

    /** 剩余空间低于它算"告急"：可以立刻清（不看最小间隔）。 */
    const val CRITICAL_FREE_BYTES: Long = 64L * 1024L * 1024L

    /** 两次内存缓存清理之间的最小间隔（防止 TrimMemory 抖动时反复清）。 */
    const val MIN_MEMORY_TRIM_INTERVAL_MS: Long = 5L * 60L * 1000L

    /**
     * 该不该清磁盘缓存。
     *
     * @param lastTrimMs 上次清理的时间戳（0 = 本机从没清过）
     * @param freeBytes  缓存分区可用空间（负数 = 读不到，按"不紧张"处理）
     */
    fun shouldTrimDiskCache(
        nowMs: Long,
        lastTrimMs: Long,
        freeBytes: Long,
        profile: PerfProfile,
    ): Boolean {
        // 告急：任何情况下都让位（哪怕刚刚清过）。
        if (freeBytes in 0 until CRITICAL_FREE_BYTES) return true
        // 首次运行：缓存本来就是空的，别白清一次（顺便把时间戳立起来）。
        if (lastTrimMs <= 0L) return false
        val age = nowMs - lastTrimMs
        if (age < MIN_DISK_TRIM_INTERVAL_MS) return false
        if (freeBytes in 0 until profile.diskTrimFreeBytesThreshold) return true
        return age >= MAX_DISK_CACHE_AGE_MS
    }

    /** 该不该清内存缓存（只清 RAM，不动磁盘）。 */
    fun shouldTrimMemoryCache(
        nowMs: Long,
        lastTrimMs: Long,
        pressure: MemoryPressure,
        profile: PerfProfile,
    ): Boolean {
        val urgent = when (pressure) {
            MemoryPressure.NONE -> return false
            MemoryPressure.LOW -> profile.trimMemoryOnLowRam
            MemoryPressure.CRITICAL -> true
        }
        if (!urgent) return false
        return lastTrimMs <= 0L || nowMs - lastTrimMs >= MIN_MEMORY_TRIM_INTERVAL_MS
    }
}
