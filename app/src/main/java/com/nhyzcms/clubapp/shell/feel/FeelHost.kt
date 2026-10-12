package com.nhyzcms.clubapp.shell.feel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.nhyzcms.clubapp.R
import com.nhyzcms.clubapp.shell.AppConfig
import com.nhyzcms.clubapp.shell.update.UpdatePolicy
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.concurrent.thread

/**
 * 「原生手感」宿主（区块 4）：窗口配色、下拉刷新、首屏骨架、返回顶部按钮、返回键判定、
 * 网页状态探测的接线都收在这里，MainActivity 只留调用点。
 *
 * 安全边界：本类执行的 JS 都是固定常量脚本（[PageStateProbe.PROBE_SCRIPT]、[ContentProbe.SCRIPT]
 * 与 [SCROLL_TO_TOP_SCRIPT]），不拼接变量，也不用 addJavascriptInterface（SECURITY 第 4.1 节）。
 */
class FeelHost(
    private val activity: AppCompatActivity,
    private val rootView: View,
    private val webView: ScrollAwareWebView,
    private val swipeRefresh: SwipeRefreshLayout,
    private val skeleton: View,
    private val fabButton: ImageView,
    private val errorPanel: View,
    /** 读清单开关（updates.flags()）。不缓存：清单可能被更新流程换掉。 */
    private val flags: () -> Map<String, Boolean>,
    /** 下拉刷新时重新加载当前页（由 Activity 提供）。 */
    private val reload: () -> Unit,
) {

    /** 首屏是否已渲染完成（下拉刷新与返回顶部按钮都看它）。 */
    var pageLoaded = false
        private set

    private val probe = PageStateProbe(webView, ::onProbe)

    /** 首屏看护：定时探测"网页画出内容了吗"，见 [startRevealWatch] 与 [ContentProbe]。 */
    private val revealHandler = Handler(Looper.getMainLooper())
    private var revealDeadlineMs = 0L
    private var lastSnapshot: ContentProbe.Snapshot? = null
    private var diagView: TextView? = null

    /** 壳自己发起的 HTTP 探测结果（区分"App 没有网络"与"只有 WebView 有问题"）。 */
    private var netProbeResult: String? = null

    /** 页面脚本探测是否拿到过结果（能证明 WebView 里的 JS 是否正常）。 */
    private var stateProbeWorked = false

    /** 每次显示骨架屏自增：让"动画兜底收起"任务失效，避免它误收掉新一次的骨架屏。 */
    private var skeletonToken = 0
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
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            systemBarsBottomPx = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            applyOverlayMargins()
            // 返回 insets（不消费），交回系统继续下发。
            insets
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
        revealHandler.removeCallbacksAndMessages(null)
        diagView = null
    }

    /**
     * 冷启动首屏：显示骨架浮层，等网页**画出内容**就淡出（消除白屏那一闪）。
     *
     * ⚠️ 这里**不再**动 WebView 的可见性与 alpha —— 这是 2026-10-12 黑屏事故的修复点：
     *   ① 把 WebView 设成 INVISIBLE、再对它的 alpha 做动画，会让它被放到硬件层上；
     *      WebView 走自己的绘制通道，部分 ROM 在这之后就一直画不出内容；
     *   ② 更要命的是"何时揭开"原先只押在 `onPageFinished` 上，而它要等网页的 `load` 事件
     *      —— **所有子资源**（含大图、live2d 模型）都加载完才触发。本站只有 3 M 上行，
     *      这个事件迟迟不来 → 骨架屏一直盖着网页 → 用户看到的"一片黑"（骨架屏是深色资源色），
     *      同时 pageLoaded 一直为 false → 下拉刷新被判"不可用" → "下拉后没反应"。
     * 现在：骨架屏只是盖在上面的浮层，是否揭开由 [startRevealWatch] 主动探测决定。
     */
    fun showSkeleton() {
        pageLoaded = false
        skeletonToken++
        skeleton.animate().cancel()
        skeleton.alpha = 1f
        skeleton.visibility = View.VISIBLE
        startRevealWatch()
        // ❗ 硬看门狗：**不依赖任何 JS 回调**。踩过的坑：如果 WebView 连页面都没开始加载
        //   （例如这台手机把这个 App 的联网权限关了），evaluateJavascript 的回调永远不会回来，
        //   于是"探测式揭开"和"基于回调的 15 秒诊断"都不会发生 —— 浮层就永远盖着。
        //   所以这里额外挂一个纯计时的兜底，到点无条件揭开并列诊断。
        revealHandler.removeCallbacks(hardWatchdog)
        revealHandler.postDelayed(hardWatchdog, HARD_DEADLINE_MS)
        record("showSkeleton：已显示骨架浮层，等待首屏内容（" + HARD_DEADLINE_MS + "ms 后强制揭开）")
    }

    /** 纯计时兜底（见 [showSkeleton] 的说明）。 */
    private val hardWatchdog = Runnable {
        if (pageLoaded) return@Runnable
        record("硬看门狗触发：仍未 onPageFinished，强制揭开并列诊断")
        reportBlank()
    }

    /**
     * 页面加载完成后再过几秒确认一次 DOM：
     * "加载完了但什么都没渲染"（脚本没跑/脚本报错）同样要给出诊断，
     * 不能只是把浮层揭开、然后让用户对着一片空白。
     */
    private val postRenderCheck = Runnable {
        if (diagView != null) return@Runnable
        probeContent { snapshot ->
            if (snapshot != null && ContentProbe.looksUnrendered(snapshot)) {
                record("onPageFinished 之后仍是空 DOM，列出诊断")
                reportBlank()
            }
        }
    }

    /** 揭开浮层 + 做一次壳侧网络探测 + 把诊断摊在屏幕上。 */
    private fun reportBlank() {
        hideSkeleton()
        if (netProbeResult != null) {
            showContentDiagnostics(lastSnapshot)
            return
        }
        probeNetwork { result ->
            netProbeResult = result
            record("壳侧 HTTP 探测：" + result)
            showContentDiagnostics(lastSnapshot)
        }
    }

    /** onPageFinished：收尾刷新圈、骨架与按钮状态。 */
    fun onPageRendered() {
        pageLoaded = true
        revealHandler.removeCallbacks(hardWatchdog)
        refreshStartedAtMs = 0L
        swipeRefresh.isRefreshing = false
        hideSkeleton()
        updateBackToTopButton(webView.scrollOffset)
        probe.probeNow()
        dismissDiagnostics()
        record("onPageFinished（pageLoaded=true）")
        revealHandler.removeCallbacks(postRenderCheck)
        revealHandler.postDelayed(postRenderCheck, POST_RENDER_CHECK_MS)
    }

    /** 错误面板出现时同步浮层状态：面板盖着时不该有刷新圈或悬浮按钮。 */
    fun onErrorPanelChanged(visible: Boolean) {
        if (visible) {
            refreshStartedAtMs = 0L
            swipeRefresh.isRefreshing = false
            setBackToTopVisible(false)
        }
    }

    /** 返回键决策（含「再按一次退出」的时间窗维护）。 */
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
        if (!stateProbeWorked) {
            stateProbeWorked = true
            record("页面脚本探测（PageStateProbe）：有结果（说明 WebView 里 JS 正常）")
        }
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

    /** 返回顶部按钮的显隐（阈值判定在 [FabPolicy]）。 */
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

    /**
     * 收起骨架浮层。只淡出**骨架**，绝不碰 WebView 的 alpha 与可见性（原因见 [showSkeleton]）。
     *
     * 除了动画结束回调，还挂了一个"兜底收起"：动画被取消/打断时也必须收掉浮层，
     * 否则它就会一直盖在网页上 —— 那正是本次黑屏的表现。
     */
    private fun hideSkeleton() {
        if (skeleton.visibility != View.VISIBLE) return
        revealHandler.removeCallbacks(revealTick)
        val token = ++skeletonToken
        skeleton.animate().cancel()
        skeleton.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            if (token == skeletonToken) {
                skeleton.visibility = View.GONE
                skeleton.alpha = 1f
            }
        }.start()
        revealHandler.postDelayed({
            if (token == skeletonToken) {
                skeleton.animate().cancel()
                skeleton.alpha = 1f
                skeleton.visibility = View.GONE
            }
        }, FADE_MS + 200L)
    }

    // ── 首屏看护：骨架屏该不该揭开 / 空白页诊断 ───────────────────────────────

    /**
     * 定时问网页"画出内容了吗"：画出来了就**立刻**揭开骨架（并让下拉刷新/返回顶部可用），
     * 到点还没画出来就强制揭开 + 给出诊断浮层 —— 绝不让用户一直盯着黑屏。
     */
    private val revealTick = object : Runnable {
        override fun run() {
            if (pageLoaded || skeleton.visibility != View.VISIBLE) return
            probeContent()
            if (pageLoaded || skeleton.visibility != View.VISIBLE) return
            revealHandler.postDelayed(this, REVEAL_INTERVAL_MS)
        }
    }

    private fun startRevealWatch() {
        revealHandler.removeCallbacks(revealTick)
        revealDeadlineMs = SystemClock.elapsedRealtime() + REVEAL_TIMEOUT_MS
        revealHandler.postDelayed(revealTick, REVEAL_INTERVAL_MS)
    }

    /** 探测一次网页内容。判据见 [ContentProbe.looksUnrendered]。 */
    private fun probeContent() {
        probeContent { snapshot ->
            if (snapshot != null && !ContentProbe.looksUnrendered(snapshot)) {
                // 这里**不等 onPageFinished**：load 事件常被大图/模型拖住，等它就会一直黑屏。
                pageLoaded = true
                hideSkeleton()
            } else if (skeleton.visibility == View.VISIBLE &&
                SystemClock.elapsedRealtime() >= revealDeadlineMs
            ) {
                record("探测到空 DOM 且已到点，列出诊断")
                reportBlank()
            }
        }
    }

    /** 探测一次网页内容并把快照交给调用方（null = 探测结果不可信或页面无脚本环境）。 */
    private fun probeContent(onSnapshot: (ContentProbe.Snapshot?) -> Unit) {
        runCatching {
            webView.evaluateJavascript(ContentProbe.SCRIPT) { value ->
                val snapshot = ContentProbe.parse(value)
                lastSnapshot = snapshot
                record("内容探测：" + describe(snapshot))
                onSnapshot(snapshot)
            }
        }
    }

    private fun describe(snapshot: ContentProbe.Snapshot?): String =
        if (snapshot == null) {
            "拿不到结果（脚本未执行或页面已跳走）"
        } else {
            "#root=" + snapshot.rootChildren + " body=" + snapshot.bodyChars +
                " scripts=" + snapshot.scriptTags +
                " bundle=" + (if (snapshot.hasBundleGlobal) "有" else "无")
        }

    /**
     * 到点仍然没有内容：把诊断信息摊在屏幕上（点一下复制）。
     *
     * 为什么必须有它：用户端只有一台手机 —— 读不到 logcat（无 READ_LOGS）、也用不了 adb，
     * 所以"一张截图 + 一段可复制的诊断文本"就是我们唯一的线索。
     */
    private fun showContentDiagnostics(snapshot: ContentProbe.Snapshot?) {
        if (diagView != null) return
        val parent = rootView as? ViewGroup ?: return
        val detail = ContentProbe.diagnose(
            snapshot = snapshot,
            url = runCatching { webView.url }.getOrNull().orEmpty(),
            javaScriptEnabled = runCatching { webView.settings.javaScriptEnabled }.getOrDefault(false),
            webViewVersion = webViewVersion(),
        ) + "\n页面脚本探测：" + (if (stateProbeWorked) "有结果（JS 正常）" else "无结果（JS 未运行）") +
            "\n本机网络探测：" + (netProbeResult ?: "(未做)")
        record("诊断：" + detail.replace('\n', ' '))
        val view = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(DIAG_TEXT_COLOR)
            setBackgroundColor(DIAG_BACKGROUND_COLOR)
            setPadding(baseMarginPx / 2, baseMarginPx / 2, baseMarginPx / 2, baseMarginPx / 2)
            text = activity.getString(R.string.diag_blank_hint) + "\n\n" + detail
            setOnClickListener {
                runCatching {
                    val clipboard =
                        activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("diag", detail))
                    Toast.makeText(activity, R.string.toast_diag_copied, Toast.LENGTH_SHORT).show()
                }
            }
        }
        parent.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        diagView = view
    }

    /** 当前 WebView 内核版本（诊断用）。静态方法、不碰实例，任何线程都安全。 */
    private fun webViewVersion(): String =
        runCatching { WebView.getCurrentWebViewPackage()?.versionName.orEmpty() }.getOrDefault("")

    /**
     * 壳自己发一次 HTTP 请求（后台线程），用来区分两种完全不同的故障：
     *   · **App 根本没有网络**（例如系统把它的联网权限关了）→ 这里同样失败；
     *   · **只是 WebView 没加载成功** → 这里能拿到 HTTP 状态码。
     * 结果只用于诊断，不影响任何功能。
     */
    private fun probeNetwork(onResult: (String) -> Unit) {
        thread(name = "clubapp-netprobe") {
            val result = runCatching {
                val connection =
                    (URI(AppConfig.SITE_ORIGIN).toURL().openConnection() as HttpURLConnection).apply {
                        connectTimeout = NET_PROBE_TIMEOUT_MS
                        readTimeout = NET_PROBE_TIMEOUT_MS
                        requestMethod = "HEAD"
                        setRequestProperty("User-Agent", "NhyzClubApp-netprobe")
                    }
                val code = connection.responseCode
                connection.disconnect()
                "HTTP " + code
            }.getOrElse { error -> error.javaClass.simpleName + ": " + (error.message ?: "") }
            revealHandler.post { onResult(result) }
        }
    }

    /**
     * 关键事件落一行到 `filesDir/diag/last-run.txt`（超过 [DIAG_MAX_BYTES] 就不再写）。
     *
     * 为什么落盘：用户端没有 adb，浮层只解决"当下看得见"；落盘后即便 App 被系统杀掉，
     * 也能把上一次的运行轨迹取出来（例如经「文件管理 → Android/data」或下次启动时展示）。
     */
    private fun record(line: String) {
        runCatching {
            val dir = File(activity.filesDir, "diag")
            if (!dir.isDirectory && !dir.mkdirs()) return
            val file = File(dir, "last-run.txt")
            if (file.length() > DIAG_MAX_BYTES) return
            file.appendText("[" + System.currentTimeMillis() + "] " + line + "\n")
        }
    }

    /** 内容后来出来了就把诊断浮层撤掉（它只是"当时没画面"的临时说明）。 */
    private fun dismissDiagnostics() {
        val view = diagView ?: return
        (rootView as? ViewGroup)?.removeView(view)
        diagView = null
    }

    /** 滚回顶部（固定常量脚本，不拼接变量，见 SECURITY 第 4.1 节）。 */
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

        /** 首屏看护：探测间隔，以及"多久还没内容"就强制揭开并举诊断。 */
        const val REVEAL_INTERVAL_MS = 1_500L
        const val REVEAL_TIMEOUT_MS = 15_000L

        /** 硬看门狗：与所有 JS 回调无关，到点无条件揭开（见 [showSkeleton]）。 */
        const val HARD_DEADLINE_MS = 12_000L

        /** onPageFinished 之后再过多久复核 DOM（防止"加载完了但空 DOM"没人管）。 */
        const val POST_RENDER_CHECK_MS = 5_000L

        /** 壳侧 HTTP 探测超时，以及诊断文件的体积上限。 */
        const val NET_PROBE_TIMEOUT_MS = 8_000
        const val DIAG_MAX_BYTES = 64L * 1024L

        /** 诊断浮层配色：琥珀字 + 半透明深底（盖在网页上也看得清）。 */
        val DIAG_TEXT_COLOR = 0xFFFFE08A.toInt()
        val DIAG_BACKGROUND_COLOR = 0xE6101820.toInt()

        /** 浮层（返回顶部按钮）的基础外边距（dp）。 */
        const val OVERLAY_MARGIN_DP = 16

        /** 滚回顶部的固定脚本（常量集中在这里，「不拼接变量」这条约束一眼可查）。 */
        const val SCROLL_TO_TOP_SCRIPT = "window.scrollTo({top:0,behavior:'smooth'});"
    }
}
