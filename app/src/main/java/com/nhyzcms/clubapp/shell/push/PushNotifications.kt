package com.nhyzcms.clubapp.shell.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nhyzcms.clubapp.BuildConfig
import com.nhyzcms.clubapp.MainActivity
import com.nhyzcms.clubapp.R
import com.nhyzcms.clubapp.shell.AppConfig
import com.nhyzcms.clubapp.shell.PermissionsPolicy

/**
 * 系统通知与图标角标。
 *
 * **为什么"角标"要靠一条通知实现**：Android 8.0 起没有"直接设置数字角标"的公开 API，
 * launcher 显示的数字来自**活动的通知**（渠道的 setShowBadge(true) 决定是否显示）。
 * 所以壳的做法是：一条**低优先级、静音、无横幅、可原地更新**的通知，setNumber(未读数)
 * 就是角标数字的来源。通知 id 固定 → "又来一条"是更新同一条，不会堆一屏。
 *
 * 权限：Android 13+ 需要运行时 POST_NOTIFICATIONS；没权限时 [canPost] 返回 false，
 * 上层直接不发（不装作有角标），由网页内的徽标兜底。
 *
 * 点击落点：不新写导航，PendingIntent 直接给一个**本站深链**（https://.../#/messages/12），
 * 复用 MainActivity 现成的 ACTION_VIEW 处理（singleTask → onNewIntent → 导航白名单裁决）。
 *
 * 另外两个方法只给 **debug 包**用（自检 / 诊断），走**独立渠道**且 setShowBadge(false)，
 * 避免污染未读角标的语义（见 docs/FIX-20261013-PUSH-NOT-FIRING.md）。
 */
class PushNotifications(private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(AppConfig.NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            AppConfig.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = context.getString(R.string.notif_channel_desc)
        channel.setShowBadge(true)
        channel.enableVibration(false)
        channel.enableLights(false)
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)
        ensureAlertChannel(manager)
    }

    /**
     * 「有声」的私信渠道（AppConfig.ALERT_CHANNEL_ID）。
     *
     * IMPORTANCE_DEFAULT：响铃 + 振动，允许角标；用户若嫌吵可以在系统设置里把它调成静音，
     * 那时会退化成与未读渠道一样的「静默角标」，但**不影响**能否收到通知。
     */
    private fun ensureAlertChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(AppConfig.ALERT_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            AppConfig.ALERT_CHANNEL_ID,
            context.getString(R.string.notif_alert_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = context.getString(R.string.notif_alert_channel_desc)
        channel.setShowBadge(true)
        manager.createNotificationChannel(channel)
    }

    /** 能不能发通知（Android 13+ 看运行时权限）。 */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            PermissionsPolicy.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 发 / 更新未读通知（固定 id，更新同一条）。
     *
     * @param alert true = 新私信，走有声渠道（响铃/振动）；false = 站内通知，走静音渠道。
     *              两者共用同一个通知 id —— 后到的会原地更新，不在通知栏堆一屏。
     */
    fun show(count: Int, title: String, text: String, route: String, alert: Boolean) {
        ensureChannel()
        val notification = build(count, title, text, route, alert)
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    /** 清掉未读通知（已读 / 登出 / 被挤号）。 */
    fun clear() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /**
     * 常驻后台（区块 5 补强）：前台服务的通知渠道。
     *
     * 为什么必须单独一条渠道：前台服务必须有一条**持续可见**的通知，而 launcher 的数字角标
     * 来自「活动的通知」——若把服务通知塞进未读渠道，角标就会一直挂着、清不掉。
     * 因此这里 IMPORTANCE_MIN + setShowBadge(false)，只起「告诉用户正在后台收消息」的作用。
     */
    fun ensureServiceChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(AppConfig.SERVICE_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            AppConfig.SERVICE_CHANNEL_ID,
            context.getString(R.string.notif_service_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        )
        channel.description = context.getString(R.string.notif_service_channel_desc)
        channel.setShowBadge(false)
        channel.enableVibration(false)
        channel.enableLights(false)
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)
    }

    /** 前台服务用的常驻通知（START_STICKY 保活的生命周期与它绑定）。 */
    fun buildServiceNotification(): Notification {
        ensureServiceChannel()
        return NotificationCompat.Builder(context, AppConfig.SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_club)
            .setContentTitle(context.getString(R.string.notif_service_title))
            .setContentText(context.getString(R.string.notif_service_text))
            .setContentIntent(contentIntent("/#/m"))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** 服务停止时把常驻通知一起收掉（否则会留一条点不动的僵尸通知）。 */
    fun hideService() {
        runCatching { NotificationManagerCompat.from(context).cancel(SERVICE_ID) }
    }

    /**
     * 调试自检通知（只有 debug 包调用）：验证"通知能不能送达这台设备"。
     *
     * 走**独立渠道**（AppConfig.SELF_TEST_CHANNEL_ID）且 setShowBadge(false)：
     * 自检要能被看见，但绝不能污染"未读角标"的语义（角标只该由未读通知驱动）。
     */
    fun showSelfTest() {
        ensureSelfTestChannel()
        val notification = NotificationCompat.Builder(context, AppConfig.SELF_TEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_club)
            .setContentTitle(context.getString(R.string.selftest_title))
            .setContentText(context.getString(R.string.selftest_text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SELF_TEST_ID, notification) }
    }

    /**
     * 调试诊断通知（只有 debug 包）：把**最新的推送状态**留在通知栏，可反复更新同一条。
     *
     * 为什么用通知而不是只弹 toast：toast 一闪就没了，用户来不及看/截屏；
     * 通知留在栏里，用户可以慢慢看、顺手截给我——这比"猜"便宜太多。
     * 同样走独立渠道且 setShowBadge(false)（绝不能污染未读角标语义）。
     */
    fun showDiagnostic(state: String) {
        if (!BuildConfig.DEBUG) return
        ensureSelfTestChannel()
        val notification = NotificationCompat.Builder(context, AppConfig.SELF_TEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_club)
            .setContentTitle(context.getString(R.string.selftest_diag_title))
            .setContentText(state)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(DIAG_ID, notification) }
    }

    /** 自检与诊断共用一个"调试专用"渠道（IMPORTANCE_DEFAULT 才看得到，但不驱动角标）。 */
    private fun ensureSelfTestChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(AppConfig.SELF_TEST_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            AppConfig.SELF_TEST_CHANNEL_ID,
            context.getString(R.string.selftest_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = context.getString(R.string.selftest_channel_desc)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun build(
        count: Int,
        title: String,
        text: String,
        route: String,
        alert: Boolean,
    ): Notification {
        val channel = if (alert) AppConfig.ALERT_CHANNEL_ID else AppConfig.NOTIFICATION_CHANNEL_ID
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_club)
            .setContentTitle(title)
            .setContentText(text)
            .setNumber(count)
            .setContentIntent(contentIntent(route))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (alert) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            // 同一条通知只在**第一次**出现时响：之后继续更新数字/内容，但不反复打断。
            .setOnlyAlertOnce(true)
            .setSilent(!alert)
            .setLocalOnly(true)
            .build()
    }

    private fun contentIntent(route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(AppConfig.SITE_ORIGIN + route)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        // Android 12 起必须显式声明可变性；本通知内容不可变，用 IMMUTABLE。
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, REQUEST_CODE, intent, flags)
    }

    companion object {
        /** 固定通知 id：未读通知永远是"同一条"，只更新内容与数字。 */
        const val NOTIFICATION_ID = 1001
        /** 自检通知单独用一个 id，绝不复用未读通知那条（否则会把它顶掉）。 */
        const val SELF_TEST_ID = 1002
        /** 诊断通知（debug）：永远更新同一条，不刷屏。 */
        const val DIAG_ID = 1003
        /** 常驻后台的前台服务通知（不能复用未读那条，否则会互相顶掉）。 */
        const val SERVICE_ID = 1004
        const val REQUEST_CODE = 2001
    }
}
