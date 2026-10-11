package com.nhyzcms.clubapp.shell.feel

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.nhyzcms.clubapp.R
import com.nhyzcms.clubapp.shell.update.UpdatePolicy

/**
 * 「原生手感」宿主（区块 4）：把窗口配色、下拉刷新、首屏骨架、返回顶部按钮、
 * 返回键判定、网页状态探测这些接线收在这一个类里，MainActivity 只留调用点。
 *
 * 为什么单独一个类：MainActivity 已经承载了区块 2/3 的能力接线，再往里堆区块 4
 * 会变成谁都不敢改的巨型文件。这里只依赖传入的几个视图与两个回调
 * （读清单开关、重新加载页面），不碰 Activity 的业务状态。
 *
 * 安全边界：本类执行的两段 JS 都是**固定常量脚本**
 * （[PageStateProbe.PROBE_SCRIPT] 与 [SCROLL_TO_TOP_SCRIPT]），不拼接任何变量，
 * 也不使用 addJavascriptInterface（见 docs/SECURITY.md 第 4 节）。
 */
class FeelHost(
    private val activity: AppCompatActivity,
    private val rootView: View,
    private val webView: ScrollAwareWebView,
    private val swipeRefresh: SwipeRefreshLayout,
    private val skeleton: View,
    private val fabButton: ImageView,
    private val errorPanel: View,
    /** 读清单开关（`updates.flags()`）。不缓存：清单可能被更新流程换掉。 */
    private val flags: () -> Map<String, Boolean>,
    /** 下拉刷新时重新加载当前页（由 Activity 提供，壳不干预加载策略）。 */
    private val reload: () -> Unit,
) {

    /** 首屏是否已渲染完成（下拉刷新与返回顶部按钮都看它）。 */
    var pageLoaded = false
        private set

    private val probe = PageStateProbe(webView, ::onProbe)
    private var lastExitConfirmAtMs = 0L
    private var refreshStartedAtMs = 0L
    private var systemBarsBottomPx = 0
    private var appliedPalette: PageTheme.Palette? = null
    private val density = activity.resources.displayMetrics.density
    private val fabThresholdPx = FabPolicy.thresholdPx(density)
    private val baseMarginPx = (OVERLAY_MARGIN_DP * density).toInt()

    /** onCreate 里调用一次：窗口配色、系统栏让位、下拉刷新与返回顶部接线。 */
    fun configure() {
        WindowCompat.setDecorFitsSystemWindows(
            activity.window,
            EdgeToEdgePolicy.decorFitsSystemWindows(isEdgeToEdge()),
        )
        // 先按系统深浅色兜底；等网页的 meta theme-color 探测回来再覆盖。
        applyPalette(PageTheme.decide(null, isSystemDark()))
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            systemBarsBottomPx = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            applyOverlayMargins()
            view.onApplyWindowInsets(insets)
        }

        // 手势只在网页已回到顶部时才交给刷新容器（判断是同步的，见 ScrollAwareWebView）。
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.canScrollVertically(-1) }
        swipeRefresh.setOnRefreshListener {
            if (!canPull()) {
                swipeRefresh.isRefreshing = false
                return@setOnRefreshListener
            }
            refreshStartedAtMs = SystemClock.elapsedRealtime()
            reload()
        }
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(activity, R.color.brand))

        fabButton.setOnClickListener { scrollToTop() }
        webView.onScrollOffsetChanged = { offset -> updateBackToTopButton(offset) }
    }

    /** 回到前台：开始探测网页主题/滚动（站内切深色后状态栏能跟上）。 */
    fun onResume() {
        probe.start()
        probe.probeNow()
    }

    /** 退到后台：立刻停止探测（只在可见时轮询，避免白耗电）。 */
    fun onPause() {
        probe.stop()
    }

    /** 销毁：停掉探测，避免 Handler 继续持有即将销毁的 WebView。 */
    fun dispose() {
        probe.stop()
    }

    /** 冷启动首屏：先显示骨架，等网页画出来再淡出（消除白屏那一闪）。 */
    fun showSkeleton() {
        pageLoaded = false
        webView.visibility = View.INVISIBLE
        skeleton.visibility = View.VISIBLE
    }

    /** onPageFinished：收尾刷新圈、骨架与按钮状态。 */
    fun onPageRendered() {
        pageLoaded = true
        refreshStartedAtMs = 0L
        swipeRefresh.isRefreshing = false
        hideSkeleton()
        updateBackToTopButton(webView.scrollOffset)
        probe.probeNow()
    }

    /**
     * 错误面板出现/消失时同步浮层状态：
     * 面板盖着时不该再有刷新圈或悬浮按钮（重试按钮才是唯一出路）。
     */
    fun onErrorPanelChanged(visible: Boolean) {
        if (visible) {
            refreshStartedAtMs = 0L
            swipeRefresh.isRefreshing = false
            setBackToTopVisible(false)
        }
    }

    /**
     * 返回键决策（并维护「再按一次退出」的时间窗）：
     * 能后退就后退；在起始页时第一次只提示，[BackExitPolicy.CONFIRM_WINDOW_MS] 内再按才退出。
     */
    fun decideBack(canGoBack: Boolean): BackExitPolicy.Action {
        val now = SystemClock.elapsedRealtime()
        val action = BackExitPolicy.decide(canGoBack, lastExitConfirmAtMs, now)
        lastExitConfirmAtMs = if (action == BackExitPolicy.Action.CONFIRM_EXIT) now else 0L
        return action
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    private fun canPull(): Boolean = PullToRefreshPolicy.canPull(
        enabled = flag("pullToRefresh", true),
        errorPanelVisible = errorPanel.visibility == View.VISIBLE,
        pageLoaded = pageLoaded,
    )

    /** 网页状态探测结果（每 2 秒一次，见 [PageStateProbe]）。 */
    private fun onProbe(state: PageTheme.Probe) {
        webView.applyProbeScrollOffset(state.scrollY)
        applyPalette(PageTheme.decide(state.color, isSystemDark()))
        // 网络卡住时不能一直转圈（本站只有 3 M 带宽，超时是常态而不是异常）。
        if (PullToRefreshPolicy.shouldStopSpin(refreshStartedAtMs, SystemClock.elapsedRealtime())) {
            refreshStartedAtMs = 0L
            swipeRefresh.isRefreshing = false
        }
    }

    /** 把配色落实到窗口；同样的配色重复调用会直接返回（探测每 2 秒一次）。 */
    private fun applyPalette(palette: PageTheme.Palette) {
        if (palette == appliedPalette) return
        appliedPalette = palette
        activity.window.statusBarColor =
            if (EdgeToEdgePolicy.statusBarTransparent(isEdgeToEdge())) {
                Color.TRANSPARENT
            } else {
                palette.statusBarColor
            }
        activity.window.navigationBarColor = palette.windowBackgroundColor
        activity.window.setBackgroundDrawable(ColorDrawable(palette.windowBackgroundColor))
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = palette.lightStatusBarIcons
        controller.isAppearanceLightNavigationBars = palette.lightStatusBarIcons
        // WebView 自身底色：下拉与回弹时露出来的就是它，必须与网页底色一致。
        webView.setBackgroundColor(palette.windowBackgroundColor)
    }

    private fun applyOverlayMargins() {
        (fabButton.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.marginEnd = baseMarginPx
            params.bottomMargin = EdgeToEdgePolicy.overlayBottomMarginPx(
                baseMarginPx,
                systemBarsBottomPx,
                isEdgeToEdge(),
            )
            fabButton.layoutParams = params
        }
    }

    /** 返回顶部按钮的显隐（阈值判定在 [FabPolicy]，这里只管动画与错误面板的优先级）。 */
    private fun updateBackToTopButton(offset: Int) {
        val enabled = flag("showBackToTop", true) && errorPanel.visibility != View.VISIBLE
        setBackToTopVisible(FabPolicy.shouldShow(offset, fabThresholdPx, enabled, pageLoaded))
    }

    private fun setBackToTopVisible(visible: Boolean) {
        if (visible == (fabButton.visibility == View.VISIBLE)) return
        fabButton.animate().cancel()
        if (visible) {
            fabButton.alpha = 0f
            fabButton.visibility = View.VISIBLE
            fabButton.animate().alpha(1f).setDuration(FADE_MS).start()
        } else {
            fabButton.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                fabButton.visibility = View.GONE
            }.start()
        }
    }

    private fun hideSkeleton() {
        if (skeleton.visibility != View.VISIBLE) return
        webView.visibility = View.VISIBLE
        webView.alpha = 0f
        webView.animate().alpha(1f).setDuration(FADE_MS).start()
        skeleton.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            skeleton.visibility = View.GONE
            skeleton.alpha = 1f
        }.start()
    }

    /**
     * 滚回顶部。
     *
     * 安全边界：执行的是固定常量脚本（[SCROLL_TO_TOP_SCRIPT]），不拼接任何变量，
     * 也不通过 addJavascriptInterface 暴露原生能力（docs/SECURITY.md 第 4 节）。
     */
    private fun scrollToTop() {
        runCatching { webView.evaluateJavascript(SCROLL_TO_TOP_SCRIPT, null) }
    }

    /** 系统是否处于深色模式（探测不到网页主题色时的兜底依据）。 */
    private fun isSystemDark(): Boolean =
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun isEdgeToEdge(): Boolean = flag("edgeToEdge", false)

    private fun flag(name: String, default: Boolean): Boolean =
        UpdatePolicy.flag(flags(), name, default)

    private companion object {
        /** 骨架淡出 / 按钮淡入时长（毫秒）。短一点更跟手，长了像卡顿。 */
        const val FADE_MS = 160L

        /** 浮层（返回顶部按钮）的基础外边距（dp）。 */
        const val OVERLAY_MARGIN_DP = 16

        /** 滚回顶部的固定脚本（常量集中在这里，「不拼接变量」这条约束一眼可查）。 */
        const val SCROLL_TO_TOP_SCRIPT = "window.scrollTo({top:0,behavior:'smooth'});"
    }
}
