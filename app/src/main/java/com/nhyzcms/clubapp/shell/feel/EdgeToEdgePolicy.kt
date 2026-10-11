package com.nhyzcms.clubapp.shell.feel

/**
 * 沉浸式（edge-to-edge）策略（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么默认**不开**铺满
 * 站点页面确实写了 `viewport-fit=cover` 与 `env(safe-area-inset-top)`
 * （网站仓库 frontend/src/styles/cobalt.css、mobile.css），看起来可以铺满。
 * 但 **Android WebView 的 `env(safe-area-inset-*)` 只反映屏幕缺口（display cutout）**，
 * 通常返回 0，并不会把「状态栏高度」告诉网页。于是铺满之后，网页那条 sticky 顶栏
 * 从 y=0 开始画，上半截正好被状态栏压住 —— 文字被遮住，比不铺满更糟。
 *
 * 所以铺满做成清单开关 `edgeToEdge`（默认 false）：等真机确认 WebView 会把 insets
 * 传下去（或网站侧改用固定顶部内边距），再考虑改成默认。
 *
 * ## 本块真正生效的沉浸式
 * 与开关无关、默认就有的部分是**配色沉浸**：状态栏 / 导航栏 / 窗口底色跟随网页
 * 主题色（见 [PageTheme]），深浅色下都不再出现刺眼色带。
 */
object EdgeToEdgePolicy {

    /** 系统是否替我们避让系统栏（true = 内容不铺到状态栏下，网页不会被遮）。 */
    fun decorFitsSystemWindows(edgeToEdge: Boolean): Boolean = !edgeToEdge

    /** 铺满时状态栏区域交给网页内容铺底，因此状态栏自身要透明。 */
    fun statusBarTransparent(edgeToEdge: Boolean): Boolean = edgeToEdge

    /**
     * 浮层（返回顶部按钮）距底部的边距。
     * 铺满时系统不再帮我们让位，必须自己加导航栏高度。
     */
    fun overlayBottomMarginPx(basePx: Int, systemBarsBottomPx: Int, edgeToEdge: Boolean): Int =
        if (edgeToEdge) basePx + systemBarsBottomPx else basePx

    /** 同上，但是距顶部的边距（骨架屏、错误面板用）。 */
    fun overlayTopMarginPx(basePx: Int, systemBarsTopPx: Int, edgeToEdge: Boolean): Int =
        if (edgeToEdge) basePx + systemBarsTopPx else basePx
}
