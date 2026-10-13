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
 * **为什么不动 MainActivity**：它已 30 KB+（区块 2~4 的接线都在里面），而本块逻辑自成一体。
 * 这里用 [Application.ActivityLifecycleCallbacks] 感知前后台，自己找当前 Activity——
 * 与"一个 Activity"的事实耦合，但换来 MainActivity 零改动（也少一处回归风险）。
 * 通知点击同样不新写导航：PendingIntent 发的是本站深链，复用 MainActivity 现成的
 * ACTION_VIEW 处理（singleTask → onNewIntent → 导航白名单裁决）。
 *
 * 线程约定：
 *  · CookieManager / WebSettings 只在**主线程**访问（见 [SessionCookies] 注释）；
 *  · WS 与未读接口在 push-socket / push-io 线程；
 *  · 通知与角标只在主线程渲染。
 */
class PushHost(
    private val app: Application,
    private val notifications: PushNotifications,
) : Application.ActivityLifecycleCallbacks {

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "push-io").apply { isDaemon = true }
    }
    private val badge = UnreadBadge()
    private val refreshTask = Runnable { refreshUnreadNow() }

    @Volatile
    private var userAgent: String? = null

    @Volatile
    private var cookie: String? = null

    private var currentActivity: Activity? = null
    private var socket: PushSocket? = null
    private var permissionAsked = false

    /** 在 `Application.onCreate` 里调用一次。 */
    fun install() {
        app.registerActivityLifecycleCallbacks(this)
        notifications.ensureChannel()
    }

    fun dispose() {
        main.removeCallbacks(refreshTask)
        stopPush("退出应用")
        io.shutdownNow()
    }

    // ── 生命周期（前台保活）──────────────────────────────────────────

    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
        ensureUserAgent()
        cookie = SessionCookies.snapshot(AppConfig.SITE_ORIGIN)
        startPush()
        scheduleRefresh(0)
        requestNotificationPermission(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
        // 只在前台保活：退到后台就断开（省电，也不白占服务器连接；见区块 5 边界）。
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

    // ── 推送连接 ─────────────────────────────────────────────────────

    private fun startPush() {
        val snapshot = cookie
        if (!SessionCookies.isLoggedIn(snapshot)) {
            // 未登录：不发任何请求、也不显示角标（登录在网页里做，壳只跟随）。
            if (badge.total > 0) {
                badge.reset()
                notifications.clear()
            }
            return
        }
        if (socket != null) return
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

    private val socketListener = object : PushSocket.Listener {
        override fun onOpen() {
            Log.d(TAG, "推送连接已建立")
            scheduleRefresh(0)
        }

        override fun onEvent(event: PushProtocol.Event) {
            main.post { handleEvent(event) }
        }

        override fun onUnauthorized() {
            main.post { onSessionInvalid() }
        }

        override fun onClosed(reason: String) {
            Log.d(TAG, "推送连接结束：" + reason)
        }
    }

    // ── 事件 → 角标 → 通知 ───────────────────────────────────────────

    private fun handleEvent(event: PushProtocol.Event) {
        val changed = when (event) {
            is PushProtocol.Event.Dm -> badge.onDmEvent(event.conversationId, event.retracted)
            is PushProtocol.Event.Notification -> badge.onNotificationEvent()
            is PushProtocol.Event.Comment -> false
        }
        if (!changed) return
        renderBadge()
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
        if (!SessionCookies.isLoggedIn(snapshot)) return

        val dm = UnreadApi.fetch(AppConfig.DM_UNREAD_URL, AppConfig.SITE_ORIGIN, snapshot, ua)
        if (dm.unauthorized) {
            main.post { onSessionInvalid() }
            return
        }
        if (dm.ok) main.post { if (badge.applyDirectMessages(dm.total)) renderBadge() }

        val notices = UnreadApi.fetch(AppConfig.NOTIFICATION_UNREAD_URL, AppConfig.SITE_ORIGIN, snapshot, ua)
        if (notices.unauthorized) {
            main.post { onSessionInvalid() }
            return
        }
        if (notices.ok) main.post { if (badge.applyNotifications(notices.total)) renderBadge() }
    }

    private fun renderBadge() {
        // 没通知权限（Android 13+ 拒绝）：角标也无从显示，直接不发（不装作有角标）。
        if (!notifications.canPost()) return
        if (!badge.hasUnread) {
            notifications.clear()
            return
        }
        notifications.show(
            count = badge.total,
            title = app.getString(R.string.notif_title),
            text = summaryText(),
            route = route(),
        )
    }

    private fun summaryText(): String = when {
        badge.directMessages > 0 && badge.notifications > 0 ->
            app.getString(R.string.notif_mixed, badge.directMessages, badge.notifications)

        badge.directMessages > 0 -> app.getString(R.string.notif_dm, badge.directMessages)
        else -> app.getString(R.string.notif_notice, badge.notifications)
    }

    /** 点通知的落点：优先"最新未读会话"，否则私信列表，再否则通知列表。 */
    private fun route(): String {
        val conversation = badge.newestConversationId
        if (conversation != null) return PushProtocol.routeForConversation(conversation)
        if (badge.directMessages > 0) return PushProtocol.PATH_MESSAGES
        return PushProtocol.PATH_NOTIFICATIONS
    }

    private fun onSessionInvalid() {
        badge.reset()
        notifications.clear()
        stopPush("会话失效")
    }

    // ── 权限与 UA ────────────────────────────────────────────────────

    private fun ensureUserAgent() {
        if (userAgent != null) return
        val base = runCatching { WebSettings.getDefaultUserAgent(app) }.getOrNull().orEmpty()
        userAgent = if (base.isBlank()) AppConfig.uaMarker() else base + " " + AppConfig.uaMarker()
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
        const val PERMISSION_DELAY_MS = 4000L
        const val PERMISSION_REQUEST_CODE = 3001
    }
}
