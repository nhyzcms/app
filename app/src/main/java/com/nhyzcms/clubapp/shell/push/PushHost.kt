package com.nhyzcms.clubapp.shell.push

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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
 * 2026-10-17 再次补强：管理员反馈「退到后台就收不到提醒」。根因是上面的
 * `onActivityStopped` 会 stopPush()——**后台根本不连**。现在改为：
 *  4. **常驻后台**：登录后用前台服务（[PushService]）把进程与连接一起保活，
 *     界面退到后台不再断连（见 [startForegroundServiceSafely]）；
 *  5. **省电引导**：登录后一次性提示「忽略电池优化」，否则 Doze 会掐掉长连接；
 *  6. **站内红点联动**：[onUnreadChanged] 回调让 MainActivity 给网页派发 `club:unread`，
 *     App 内的「私信/通知」红点即时刷新（网页侧见 frontend/src/api/unreadBadges.ts）。
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
            val every = if (currentActivity != null) SAFETY_REFRESH_TICKS else SAFETY_REFRESH_TICKS_BACKGROUND
            if (ticks % every == 0) scheduleRefresh(0)
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

    /** 前台服务是否已被我们启动（避免重复 startForegroundService）。 */
    @Volatile
    private var serviceRunning = false

    /** 本次进程内是否已见过「已登录」状态（用于只弹一次省电引导）。 */
    private var loggedInSeen = false

    /**
     * 未读数量发生变化时的回调（主线程）。MainActivity 注册它，把变化转成网页事件
     * `club:unread`，让站内红点即时刷新；不注册也不影响通知与角标。
     */
    var onUnreadChanged: (() -> Unit)? = null

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
        // 区块 5 补强（2026-10-17）：**不再**在退到后台时停掉看门狗与连接。
        // 旧写法让 App 一离开界面就彻底失联（管理员实测：只有打开私信页才看到新消息）。
        // 现在连接与看门狗都继续跑，由前台服务保活（服务在登录时就已启动，见 syncLoginState）。
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

    /** 依登录态决定连/断，并起停前台服务。 */
    private fun syncLoginState() {
        if (PushSession.isLoggedIn(cookie)) {
            startPush()
            // 登录后就常驻：只有前台服务能让进程在后台活下去（详见 PushService 的注释）。
            startForegroundServiceSafely("已登录")
            if (!loggedInSeen) {
                loggedInSeen = true
                // 延后一点：别和首屏、通知权限弹窗抢注意力。
                main.postDelayed({ askBatteryExemptionOnce() }, BATTERY_PROMPT_DELAY_MS)
            }
        } else {
            if (socket != null) stopPush("未登录")
            stopForegroundServiceSafely("未登录")
            if (badge.total > 0) {
                badge.reset()
                renderBadge()
            }
            loggedInSeen = false
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

    /**
     * 启动常驻前台服务（幂等，异常吞掉）。
     *
     * 注意：Android 12+ 禁止「从后台启动前台服务」。本方法**只在登录态变化时**调用，
     * 而那时通常有可见 Activity；万一真的被拒（进程被系统在后台拉起），
     * 我们有两条兜底：连接本身已经建好（进程活着就继续收）、
     * 以及服务被系统以 START_STICKY 重启后会走 [onServiceStarted]。
     */
    private fun startForegroundServiceSafely(reason: String) {
        if (serviceRunning) return
        val intent = Intent(app, PushService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
            serviceRunning = true
            diagnostics.report("常驻服务已启动（" + reason + "）")
        } catch (e: Exception) {
            diagnostics.report("常驻服务启动被拒：" + e.javaClass.simpleName)
        }
    }

    private fun stopForegroundServiceSafely(reason: String) {
        if (!serviceRunning) return
        serviceRunning = false
        runCatching { app.stopService(Intent(app, PushService::class.java)) }
        diagnostics.report("常驻服务已停止（" + reason + "）")
    }

    /** 进程被系统重启后由 [PushService] 唤醒：把看门狗与连接重新接上。 */
    fun onServiceStarted() {
        main.post {
            serviceRunning = true
            ensureUserAgent()
            refreshCookie()
            syncLoginState()
            startWatchdog()
            scheduleRefresh(0)
            diagnostics.report("常驻服务就绪：" + PushSession.describe(cookie))
        }
    }

    /**
     * 一次性引导「忽略电池优化」。
     *
     * 为什么需要：Doze / 各家 ROM 的后台管控会在息屏几分钟后掐断长连接，
     * 到那时任何代码都救不回来 —— 只能由用户在系统里放行。
     * 只在**登录后**问一次（记在 SharedPreferences），不反复骚扰。
     */
    private fun askBatteryExemptionOnce() {
        val activity = currentActivity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(app.packageName)) return
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATTERY_ASKED, false)) return
        prefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
        AlertDialog.Builder(activity)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            .setPositiveButton(R.string.battery_allow) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.packageName),
                )
                runCatching { activity.startActivity(intent) }
            }
            .setNegativeButton(R.string.action_later, null)
            .show()
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
        if (!PushSession.isLoggedIn(snapshot)) return

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

        // 决定性诊断数据：这一次到底拉到了什么（"失败"= 没拿到）
        val dmText = if (dm.ok) dm.total.toString() else "失败"
        val noticeText = if (notices.ok) notices.total.toString() else "失败"
        diagnostics.report("未读拉取：私信=" + dmText + "，通知=" + noticeText)
    }

    private fun onSessionInvalid() {
        badge.reset()
        renderBadge()
        stopPush("会话失效")
    }

    /** 角标/通知变化的唯一出口：先画通知，再通知网页刷新红点。 */
    private fun renderBadge() {
        notifier.render(badge)
        onUnreadChanged?.invoke()
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
        /** 后台时的兜底刷新间隔（15 s × 20 = 5 min）：WS 通着就不必勤拉，省电也省服务器。 */
        const val SAFETY_REFRESH_TICKS_BACKGROUND = 20
        /** 登录后多久弹「忽略电池优化」引导。 */
        const val BATTERY_PROMPT_DELAY_MS = 6000L
        const val PREFS_NAME = "push"
        const val KEY_BATTERY_ASKED = "battery_asked"
        const val PERMISSION_DELAY_MS = 4000L
        const val PERMISSION_REQUEST_CODE = 3001
    }
}
