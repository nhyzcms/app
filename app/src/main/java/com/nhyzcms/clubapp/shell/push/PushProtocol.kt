package com.nhyzcms.clubapp.shell.push

import com.nhyzcms.clubapp.shell.update.MiniJson

/**
 * 网站推送事件（跨仓库契约，见 docs/PUSH-CONTRACT.md）。
 *
 * 服务端形态（`messaging/services.py::_group_send` → `MessagingConsumer.messaging_event`）：
 * ```
 * {"event": "dm" | "notification" | "comment", "payload": {...}}
 * ```
 *
 * 只解析这三种；其余事件（含服务端将来新增的）一律忽略——**服务端加事件不该让壳出错**。
 * payload 里的字段是能拿就拿：拿不到会话 id 也能显示角标，只是点通知进不了具体会话。
 */
object PushProtocol {

    const val EVENT_DM = "dm"
    const val EVENT_NOTIFICATION = "notification"
    const val EVENT_COMMENT = "comment"

    /** 私信列表页（HashRouter 路由，见 AppShell 的 USER_MENU）。 */
    const val PATH_MESSAGES = "/#/messages"

    /** 通知列表页（只有通知、没有私信时点通知的落点）。 */
    const val PATH_NOTIFICATIONS = "/#/notifications"

    sealed class Event {
        /** 新私信（`send_dm` 与 `retract_dm` 都推这个事件）。 */
        class Dm(val conversationId: Int?, val messageId: Int?, val retracted: Boolean) : Event()

        /** 站内通知落库（`notifications/dispatch.py::_push`）。 */
        class Notification(val notificationId: Int?, val category: String) : Event()

        /** 评论：只与"当前打开的评论区"有关，网页自己会刷；壳仅记录线程号。 */
        class Comment(val threadId: Int?) : Event()
    }

    fun parse(raw: String?): Event? {
        val root = MiniJson.parse(raw) as? Map<*, *> ?: return null
        val name = root["event"] as? String ?: return null
        val payload = root["payload"] as? Map<*, *> ?: emptyMap<Any, Any>()
        return when (name) {
            EVENT_DM -> Event.Dm(
                conversationId = intOf(payload["conversation_id"]),
                messageId = intOf(payload["message_id"]),
                retracted = payload["retracted"] == true,
            )

            EVENT_NOTIFICATION -> Event.Notification(
                notificationId = intOf(payload["notification_id"]),
                category = payload["category"] as? String ?: "",
            )

            EVENT_COMMENT -> Event.Comment(intOf(payload["thread_id"]))

            else -> null
        }
    }

    /** 某个会话的深链路由（网站是 HashRouter，AppShell 已注册 /messages/:id）。 */
    fun routeForConversation(id: Int): String = PATH_MESSAGES + "/" + id

    private fun intOf(value: Any?): Int? = when (value) {
        is Double ->
            if (value.isFinite() && value >= 0.0 && value <= Int.MAX_VALUE.toDouble()) value.toInt() else null

        is String -> value.toIntOrNull()
        else -> null
    }
}
