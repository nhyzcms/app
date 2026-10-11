package com.nhyzcms.clubapp.shell.feel

/**
 * 下拉刷新策略（纯逻辑，可 JVM 单测）。
 *
 * 两条与真机手感直接相关的规则：
 *   1. **不能在错误面板上叠着下拉**（错误面板有自己的「重试」按钮）；
 *   2. **转圈不能无限转**：网络卡住时（本站只有 3 M 带宽、无 CDN）必须自己停下，
 *      否则用户会以为 App 死了。
 *
 * 注：本策略只管「允不允许 / 要不要停」，至于「手指下拉时网页是否已经在顶部」
 * 由 `ScrollAwareWebView.canScrollVertically(-1)` 同步回答（见该文件注释）。
 */
object PullToRefreshPolicy {

    /** 指示器最长旋转时间（毫秒）。 */
    const val MAX_SPIN_MS = 15000L

    /**
     * 现在能否下拉刷新。
     *
     * @param enabled 清单开关 `pullToRefresh`。
     * @param errorPanelVisible 原生错误面板是否盖在网页上。
     * @param pageLoaded 首屏是否已渲染完成（骨架屏期间不给下拉）。
     */
    fun canPull(enabled: Boolean, errorPanelVisible: Boolean, pageLoaded: Boolean): Boolean =
        enabled && !errorPanelVisible && pageLoaded

    /**
     * 指示器是否该强制停止。
     *
     * @param refreshStartedAtMs 本次刷新开始时间戳（0 表示当前没有在刷新）。
     */
    fun shouldStopSpin(refreshStartedAtMs: Long, nowMs: Long, maxMs: Long = MAX_SPIN_MS): Boolean =
        refreshStartedAtMs > 0L && nowMs - refreshStartedAtMs >= maxMs
}
