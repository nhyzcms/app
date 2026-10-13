package com.nhyzcms.clubapp.shell.feel

/**
 * 网页状态探测的「节奏控制器」（**纯逻辑**，不 import android.*，可在 JVM 单测）。
 *
 * ## 为什么要退避
 * [PageStateProbe] 原先固定每 2 秒往网页里跑一次固定脚本，而且**一直都在跑**：
 * 把 App 打开放在桌上不动，它照样每 2 秒跨一次 WebView 边界、惊动一次 JS 引擎 ——
 * 在低端机上这会与滚动/渲染抢主线程（区块 6 的「不卡」目标）。
 *
 * 合理做法是**按需**：页面看起来稳定了就退避，一旦有变化立刻回到快档。
 * 「有变化」由调用方给出（本类不认识任何 android 类型）：主题色变了、滚动位置动了都算。
 *
 * 退避曲线（基础 2 s、上限 15 s、每连续 3 次无变化升一档）：
 * `2 s → 4 s → 8 s → 15 s（封顶）`；任何一次变化、或调用 [reset]（导航/回到前台）立刻回 2 s。
 *
 * ## 为什么退避是安全的（重要）
 * · 「返回顶部」按钮与下拉刷新的**主**信息源是
 *   [ScrollAwareWebView.onScrollChanged]（同步、零开销、不依赖 JS），
 *   探测结果只用来**兜底补正** —— 所以退避不会让按钮或手势变迟钝；
 * · 探测的作用是「网页主题色 → 状态栏配色」与「滚动位置补正」。
 *   系统深浅色变化会让 Activity 重建（`onResume` 也会立刻探测一次），
 *   最多晚一个退避周期，不会长期不一致。
 */
class ProbeCadence(
    baseIntervalMs: Long = DEFAULT_BASE_INTERVAL_MS,
    private val maxIntervalMs: Long = DEFAULT_MAX_INTERVAL_MS,
    stableTicksToRelax: Int = DEFAULT_STABLE_TICKS_TO_RELAX,
) {

    /** 基础间隔（已按 [MIN_INTERVAL_MS] 收口：探得太勤没有意义）。 */
    private val baseIntervalMs: Long = baseIntervalMs.coerceAtLeast(MIN_INTERVAL_MS)

    /** 升一档所需的连续稳定次数（至少 1，避免除零）。 */
    private val relaxEvery: Int = stableTicksToRelax.coerceAtLeast(1)

    /** 连续「无变化」的探测次数。 */
    private var stableTicks = 0

    /** 当前间隔（调用方每次排下一轮时读它）。 */
    var currentIntervalMs: Long = this.baseIntervalMs
        private set

    /**
     * 喂一次探测结果，返回**下一轮**的延迟。
     *
     * @param changed 与上一次探测相比，主题色或滚动位置是否有变化
     */
    fun onResult(changed: Boolean): Long {
        if (changed) return reset()
        stableTicks++
        val steps = (stableTicks / relaxEvery).coerceAtMost(MAX_BACKOFF_STEPS)
        currentIntervalMs = minOf(baseIntervalMs shl steps, maxIntervalMs)
        return currentIntervalMs
    }

    /** 回到快档（页面导航、回到前台、从错误恢复时调用）。 */
    fun reset(): Long {
        stableTicks = 0
        currentIntervalMs = baseIntervalMs
        return currentIntervalMs
    }

    companion object {
        /** 基础间隔：站内切换主题后状态栏最多晚这么久跟上。 */
        const val DEFAULT_BASE_INTERVAL_MS = 2_000L

        /** 上限：再慢也要能在十几秒内发现主题/滚动变化。 */
        const val DEFAULT_MAX_INTERVAL_MS = 15_000L

        /** 连续多少次无变化才升一档。 */
        const val DEFAULT_STABLE_TICKS_TO_RELAX = 3

        /** 退避最多升几档（防位移溢出；与 max 一起构成上限）。 */
        const val MAX_BACKOFF_STEPS = 6

        /** 任何情况下都不允许比它更快（`scripts/preflight-perf.sh` 会钉住这个下限）。 */
        const val MIN_INTERVAL_MS = 1_500L
    }
}
