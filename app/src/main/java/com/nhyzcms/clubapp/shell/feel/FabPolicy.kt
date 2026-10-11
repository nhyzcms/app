package com.nhyzcms.clubapp.shell.feel

/**
 * 「返回顶部」浮动按钮的显隐策略（纯逻辑，可 JVM 单测）。
 *
 * 为什么要有阈值：短页面（登录、错误页、单条公告）不该浮出一颗球挡住内容；
 * 阈值取 600dp（约两屏），只有真的滚远了才出现。
 *
 * 开关：清单 `showBackToTop`（默认 true）——运营上想彻底关掉这个按钮时不用发新版。
 */
object FabPolicy {

    /** 显示阈值（dp）。 */
    const val THRESHOLD_DP = 600

    /** 阈值 dp → 像素。density 由 Activity 提供，避免本文件依赖 Android。 */
    fun thresholdPx(density: Float): Int = (THRESHOLD_DP * density).toInt().coerceAtLeast(1)

    /**
     * 是否显示浮动按钮。
     *
     * @param scrollOffset 当前页面滚动位置（像素）。
     * @param enabled 清单开关 `showBackToTop`。
     * @param pageLoaded 首屏是否已经渲染完成（加载中不显示，避免和骨架屏打架）。
     */
    fun shouldShow(
        scrollOffset: Int,
        thresholdPx: Int,
        enabled: Boolean,
        pageLoaded: Boolean,
    ): Boolean = enabled && pageLoaded && scrollOffset >= thresholdPx
}
