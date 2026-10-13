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
import com.nhyzcms.clubapp.MainActivity
import com.nhyzcms.clubapp.R
import com.nhyzcms.clubapp.shell.AppConfig

/**
 * 系统通知与图标角标。
 *
 * **为什么"角标"要靠一条通知实现**：Android 8.0 起没有"直接设置数字角标"的公开 API，
 * launcher 显示的数字来自**活动的通知**（渠道的 setShowBadge(true) 决定是否显示）。
 * 所以壳的做法是：一条**低优先级、静音、无横幅、可原地更新**的通知，setNumber(未读数)
 * 就是角标数字的来源。通知 id 固定 → "又来一条"是更新同一条，不会堆一屏。
 *
 * 权限：Android 13+ 需要运行时 POST_NOTIFICATIONS；没权限时 [canPost] 返回 false，
 * 上层直接不发（绝不装作有角标），由网页内的徽标兜底。
 *
 * 点击落点：不新写导航，PendingIntent 直接给一个**本站深链**（https://.../#/messages/12），
 * 复用 MainActivity 现成的 ACTION_VIEW 处理（singleTask → onNewIntent → 导航白名单裁决）。
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
    }

    /** 能不能发通知（Android 13+ 看运行时权限）。 */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            PermissionsPolicy.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 发 / 更新未读通知（固定 id，更新同一条）。 */
    fun show(count: Int, title: String, text: String, route: String) {
        ensureChannel()
        val notification = build(count, title, text, route)
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    /** 清掉未读通知（已读 / 登出 / 被挤号）。 */
    fun clear() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun build(count: Int, title: String, text: String, route: String): Notification {
        return NotificationCompat.Builder(context, AppConfig.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_club)
            .setContentTitle(title)
            .setContentText(text)
            .setNumber(count)
            .setContentIntent(contentIntent(route))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
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

    private companion object {
        /** 固定通知 id：未读通知永远是"同一条"，只更新内容与数字。 */
        const val NOTIFICATION_ID = 1001
        const val REQUEST_CODE = 2001
    }
}
