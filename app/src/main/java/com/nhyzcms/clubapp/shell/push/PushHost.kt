package com.nhyzcms.clubapp.shell.push

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebSettings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nhyzcms.clubapp.R
import com.nhyzcms.clubapp.shell.AppConfig
import com.nhyzcms.clubapp.shell.PermissionsPolicy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 区块 5 的接线器：把「WS 事件 → 未读角标 → 系统通知」串起来，并管前后台。
 *
 * 刻意不改 MainActivity（已 30 KB+）：用 [Application.ActivityLifecycleCallbacks] 感知前后台；
 * 通知点击也不新写导航——PendingIntent 发的是本站深链，复用 MainActivity 现成的 ACTION_VIEW 处理。
 *
 * 2026-10-13 真机事故（详见 docs/FIX-20261013-PUSH-NOT-FIRING.md）后补的三件事：
 *  1. **看门狗**（每 15 s，主线程）：重新取 Cookie 快照并依登录态连/断——
 *     冷启动时 CookieManager 可能尚未就绪（返回空），第一版取一次就放弃、之后再无重试；
 *  2. **兜底刷新**（每 90 s 拉一次未读）：即使 WS 事件一条都没到，有未读也会亮；
 *  3. **可见诊断**（只对 debug 包，见 [PushDiagnostics]）：toast + 通知栏状态 + 轨迹文件。
 *
 * 线程约定：CookieManager / WebSettings 只在主线程访问；WS 与未读接口在后台线程；
 * 通知与角标只在主线程渲染。
 */
class PushHost(
    private val app: Application,
    private val notifications: PushNotifications,
) : Application.ActivityLifecycleCallbacks {

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "push-io").apply { isDaemon = true }
    }
    private val diagnostics = PushDiagnostics(app, notifications, main)
    private val notifier = PushNotifier(app, notifications, diagnostics)
    private val badge = UnreadBadge()
    private val refreshTask = Runnable { refreshUnreadNow() }

    /** 主线程看门狗：跟登录态、做兜底刷新。 */
    private val watchdog: Runnable = object : Runnable {
        override fun run() {
            ticks += 1
            refreshCookie()
            syncLoginState()
            if (ticks % SAFETY_REFRESH_TICKS == 0) scheduleRefresh(0)
            main.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    @Volatile
    private var userAgent: String? = null

    @Volatile
    private var cookie: String? = null

    private var currentActivity: Activity? = null
    private var socket: PushSocket? = null
    private var permissionAsked = false
    private var ticks = 0

    /** 在 `Application.onCreate` 里调用一次。 */
    fun install() {
        app.registerActivityLifecycleCallbacks(this)
        notifications.ensureChannel()
    }

    fun dispose() {
        main.removeCallbacks(refreshTask)
        stopWatchdog()
        stopPush("退出应用")
        io.shutdownNow()
    }

    // ── 生命周期（前台保活）──────────────────────────────────────────

    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
        ensureUserAgent()
        refreshCookie()
        syncLoginState()
        scheduleRefresh(0)
        requestNotificationPermission(activity)
        startWatchdog()
        diagnostics.selfTestOnce()
        diagnostics.report("启动：" + PushSession.describe(cookie) + "，连接=" + socketStateText())
    }

    override fun onActivityStopped(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
        // 只在前台保活：退到后台就断开（省电，也不白占服务器连接；见区块 5 边界）。
        stopWatchdog()
        stopPush("退到后台")
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
    }

    // ── 看门狗 ───────────────────────────────────────────────────────

    private fun startWatchdog() {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        main.removeCallbacks(watchdog)
    }

    /** 主线程重新取 Cookie 快照（登录/登出/冷启动 Cookie 未就绪都靠它跟上）。 */
    private fun refreshCookie() {
        cookie = SessionCookies.snapshot(AppConfig.SITE_ORIGIN)
    }

    /** 依登录态决定连/断。 */
    private fun syncLoginState() {
        if (PushSession.isLoggedIn(cookie)) {
            startPush()
        } else {
            if (socket != null) stopPush("未登录")
            if (badge.total > 0) {
                badge.reset()
                notifier.clear()
            }
        }
    }

    // ── 推送连接 ─────────────────────────────────────────────────────

    private fun startPush() {
        val snapshot = cookie ?: return
        if (!PushSession.isLoggedIn(snapshot)) return
        val existing = socket
        // 不能只看引用非空：连接线程可能已退出（未登录 / 会话失效 / 连续失败），
        // 那必须换一个新的，否则"永久再也连不上"（真机事故的第二个坑）。
        if (existing != null && existing.isRunning) return
        socket = null
        val ua = userAgent ?: return
        val created = PushSocket(
            host = AppConfig.SITE_HOST,
            port = AppConfig.WS_PORT,
            path = AppConfig.WS_PATH,
            origin = AppConfig.SITE_ORIGIN,
            userAgent = ua,
            cookie = snapshot,
            listener = socketListener,
        )
        socket = created
        created.start()
    }

    private fun stopPush(reason: String) {
        val current = socket ?: return
        socket = null
        current.stop(reason)
    }

    private fun socketStateText(): String = socket?.state?.name ?: "未建立"

    private val socketListener = object : PushSocket.Listener {
        override fun onOpen() {
            Log.d(TAG, "推送连接已建立")
            diagnostics.report("已连上服务器")
            scheduleRefresh(0)
        }

        override fun onEvent(event: PushProtocol.Event) {
            main.post { handleEvent(event) }
        }

        override fun onUnauthorized() {
            main.post {
                diagnostics.report("会话已失效（可能被挤号），等重新登录")
                onSessionInvalid()
            }
        }

        override fun onClosed(reason: String) {
            Log.d(TAG, "推送连接结束：" + reason)
            main.post { diagnostics.report("连接结束：" + reason) }
        }
    }

    // ── 事件 → 角标 → 通知 ─────────────────────────────────────────

    private fun handleEvent(event: PushProtocol.Event) {
        val changed = when (event) {
            is PushProtocol.Event.Dm -> badge.onDmEvent(event.conversationId, event.retracted)
            is PushProtocol.Event.Notification -> badge.onNotificationEvent()
            is PushProtocol.Event.Comment -> false
        }
        if (!changed) return
        notifier.render(badge)
        // 事件只说明"有新东西"，数字以 HTTP 为准；节流合并，避免连来 10 条就发 10 个请求。
        scheduleRefresh(REFRESH_THROTTLE_MS)
    }

    private fun scheduleRefresh(delayMs: Long) {
        main.removeCallbacks(refreshTask)
        main.postDelayed(refreshTask, delayMs)
    }

    /** 在 IO 线程拉未读总数（HTTP 是事实源）。 */
    private fun refreshUnreadNow() {
        val ua = userAgent ?: return
        val snapshot = cookie
        if (!PushSession.isLoggedIn(snapshot)) return

        val dm = UnreadApi.fetch(AppConfig.DM_UNREAD_URL, AppConfig.SITE_ORIGIN, snapshot, ua)
        if (dm.unauthorized) {
            main.post { onSessionInvalid() }
            return
        }
        if (dm.ok) main.post { if (badge.applyDirectMessages(dm.total)) notifier.render(badge) }

        val notices = UnreadApi.fetch(AppConfig.NOTIFICATION_UNREAD_URL, AppConfig.SITE_ORIGIN, snapshot, ua)
        if (notices.unauthorized) {
            main.post { onSessionInvalid() }
            return
        }
        if (notices.ok) main.post { if (badge.applyNotifications(notices.total)) notifier.render(badge) }

        // 决定性诊断数据：这一次到底拉到了什么（"失败"= 没拿到）
        val dmText = if (dm.ok) dm.total.toString() else "失败"
        val noticeText = if (notices.ok) notices.total.toString() else "失败"
        diagnostics.report("未读拉取：私信=" + dmText + "，通知=" + noticeText)
    }

    private fun onSessionInvalid() {
        badge.reset()
        notifier.clear()
        stopPush("会话失效")
    }

    // ── 权限与 UA ──────────────────────────────────────────────────

    private fun ensureUserAgent() {
        if (userAgent != null) return
        val base = runCatching { WebSettings.getDefaultUserAgent(app) }.getOrNull().orEmpty()
        // 原生请求带上独立的 nativeUaMarker，服务器日志里才能与网页请求区分开。
        val native = " " + AppConfig.nativeUaMarker()
        userAgent = when {
            base.isBlank() -> AppConfig.uaMarker() + native
            else -> base + " " + AppConfig.uaMarker() + native
        }
    }

    private fun requestNotificationPermission(activity: Activity) {
        val permission = PermissionsPolicy.notificationPermission(Build.VERSION.SDK_INT) ?: return
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (permissionAsked) return
        permissionAsked = true
        // 延迟一点：不与首屏抢资源，也让用户先看到界面再理解弹窗来意。
        main.postDelayed({
            val target = currentActivity
            if (target != null) {
                runCatching {
                    ActivityCompat.requestPermissions(target, arrayOf(permission), PERMISSION_REQUEST_CODE)
                }
            }
        }, PERMISSION_DELAY_MS)
    }

    private companion object {
        const val TAG = "ClubApp"
        const val REFRESH_THROTTLE_MS = 2000L
        /** 看门狗周期：跟登录态 + 兜底刷新的最小粒度。 */
        const val WATCHDOG_INTERVAL_MS = 15_000L
        /** 每 N 次看门狗做一次兜底刷新（15 s × 6 = 90 s），WS 事件不到也能亮。 */
        const val SAFETY_REFRESH_TICKS = 6
        const val PERMISSION_DELAY_MS = 4000L
        const val PERMISSION_REQUEST_CODE = 3001
    }
}
