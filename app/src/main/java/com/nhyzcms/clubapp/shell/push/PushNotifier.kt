package com.nhyzcms.clubapp.shell.push

import android.app.Application
import com.nhyzcms.clubapp.R

/**
 * 把"未读状态"变成系统通知 / 图标角标（未读清零时清掉通知）。
 *
 * 为什么单独成文件：
 *  1. 这是**展示层**——与"连接、登录态、生命周期"分开更好读；
 *  2. 也让文件大小落在可维护区间（GitHub MCP 单次推送上限约 1 万字符，
 *     PushHost 这类大文件拆小之后，本地校验与推送都更省事）。
 */
class PushNotifier(
    private val app: Application,
    private val notifications: PushNotifications,
    private val diagnostics: PushDiagnostics,
) {

    /** 依角标状态渲染；未读为 0 则清掉通知（角标随之消失）。 */
    fun render(badge: UnreadBadge) {
        // 没通知权限（Android 13+ 拒绝）：角标也无从显示，直接不发（不装作有角标）。
        if (!notifications.canPost()) {
            diagnostics.report("没有通知权限，角标/通知不可用")
            return
        }
        if (!badge.hasUnread) {
            notifications.clear()
            return
        }
        notifications.show(
            count = badge.total,
            title = app.getString(R.string.notif_title),
            text = summaryText(badge),
            route = route(badge),
        )
    }

    fun clear() {
        notifications.clear()
    }

    private fun summaryText(badge: UnreadBadge): String = when {
        badge.directMessages > 0 && badge.notifications > 0 ->
            app.getString(R.string.notif_mixed, badge.directMessages, badge.notifications)

        badge.directMessages > 0 -> app.getString(R.string.notif_dm, badge.directMessages)
        else -> app.getString(R.string.notif_notice, badge.notifications)
    }

    /** 点通知的落点：优先"最新未读会话"，否则私信列表，再否则通知列表。 */
    private fun route(badge: UnreadBadge): String {
        val conversation = badge.newestConversationId
        if (conversation != null) return PushProtocol.routeForConversation(conversation)
        if (badge.directMessages > 0) return PushProtocol.PATH_MESSAGES
        return PushProtocol.PATH_NOTIFICATIONS
    }
}
