package com.nhyzcms.clubapp.shell.feel

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

/**
 * 会报告滚动位置的 WebView（区块 4）。
 *
 * ## 为什么需要它
 * 下拉刷新必须**同步**知道「网页是否已经滚上去了」：判断不及时的话，手指在页面中部
 * 下滑就会把页面当成「已在顶部」而误触发刷新 —— 区块 4 的验收点明确要求
 * 「手势只作用于页面顶部，不干扰网页滚动」。
 *
 * WebView 会把页面的滚动同步到 View 的滚动位置，因此重写 [onScrollChanged]
 * 是同步、零开销的信息源，不需要往网页里注入监听脚本。
 *
 * ## 兜底
 * 个别 ROM 的 WebView 可能不上报滚动（或站内用了内部滚动容器）。
 * [PageStateProbe] 每 2 秒用固定脚本读一次 `window.pageYOffset`，
 * 通过 [applyProbeScrollOffset] 把结果补进来 —— 顶多晚 2 秒，不会永久失真。
 */
class ScrollAwareWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {

    /** 当前页面滚动位置（像素，0 = 顶部）。 */
    var scrollOffset: Int = 0
        private set

    /** 滚动位置变化回调（用于「返回顶部」按钮的显隐）。 */
    var onScrollOffsetChanged: ((offset: Int) -> Unit)? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t != scrollOffset) {
            scrollOffset = t
            onScrollOffsetChanged?.invoke(t)
        }
    }

    /** 由 [PageStateProbe] 的 JS 探测结果补正滚动位置。 */
    fun applyProbeScrollOffset(offset: Int) {
        if (offset < 0 || offset == scrollOffset) return
        scrollOffset = offset
        onScrollOffsetChanged?.invoke(offset)
    }

    /**
     * 下拉刷新的判断依据：只有网页已经在顶部才允许下拉。
     *
     * SwipeRefreshLayout 默认也会问 `canScrollVertically(-1)`，
     * 这里显式覆写，语义完全一致但不依赖 View 内部实现细节。
     */
    override fun canScrollVertically(direction: Int): Boolean =
        if (direction < 0) scrollOffset > 0 else super.canScrollVertically(direction)
}
