package com.nhyzcms.clubapp.shell.feel

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * 定期向网页问两个值：`meta[name=theme-color]`（主题色）与 `window.pageYOffset`（滚动位置）。
 *
 * 用途：
 *   · 主题色 → 壳把自己的状态栏/导航栏/窗口底色调成与网页一致（沉浸式配色）；
 *   · 滚动位置 → 兜底校正「返回顶部」按钮与下拉刷新的判断（见 [ScrollAwareWebView]）。
 *
 * ## 安全边界（重要）
 * 这里执行的是**固定常量脚本**（[PROBE_SCRIPT] 里没有任何拼接），
 * 不接收网页传来的任何字符串，也绝不使用 `addJavascriptInterface`
 * 暴露原生对象（见 docs/SECURITY.md §4）。读取结果只用于壳自身的配色与悬浮按钮，
 * **不写回网页**，因此网页无法借它提升权限。
 *
 * ## 开销
 * 每 [INTERVAL_MS] 求值一次，只读两个属性、不做 DOM 遍历；页面不可见时
 * （Activity onPause）必须调用 [stop] 停掉，避免后台白耗电。
 */
class PageStateProbe(
    private val webView: WebView,
    private val onState: (PageTheme.Probe) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            probeNow()
            if (running) handler.postDelayed(this, INTERVAL_MS)
        }
    }

    /** 开始轮询（前台风）。重复调用无副作用。 */
    fun start() {
        if (running) return
        running = true
        handler.postDelayed(tick, INTERVAL_MS)
    }

    /** 停止轮询（onPause / onDestroy）。 */
    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    /** 立即探测一次（页面加载完成、回到前台时用，避免等一个轮询周期）。 */
    fun probeNow() {
        if (!running) return
        // 页面正在销毁时 evaluateJavascript 会抛异常，吞掉即可（探测失败不影响功能）。
        runCatching {
            webView.evaluateJavascript(PROBE_SCRIPT) { value ->
                if (running) onState(PageTheme.parseProbeResult(value))
            }
        }
    }

    companion object {
        /** 轮询间隔：太短白耗电，太长会让站内切换主题后状态栏慢半拍。 */
        const val INTERVAL_MS = 2000L

        /**
         * 固定脚本：返回 `JSON.stringify([themeColor, pageYOffset])`。
         *
         * 注意 `evaluateJavascript` 会把返回值再 JSON 化一次，
         * 所以 Kotlin 侧要解两层（见 [PageTheme.parseProbeResult]）。
         * 脚本里对 `document.querySelector` 的结果做了空判断，站点结构变化只会
         * 让探测结果退化为「空字符串 + 0」，不会报错。
         */
        const val PROBE_SCRIPT =
            "(function(){var m=document.querySelector('meta[name=\"theme-color\"]');" +
                "var c=m?String(m.content||''):'';" +
                "var y=window.pageYOffset||document.documentElement.scrollTop||0;" +
                "return JSON.stringify([c,y]);})()"
    }
}
