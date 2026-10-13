package com.nhyzcms.clubapp.shell.push

/**
 * 未读角标状态机（纯逻辑，可 JVM 单测）。
 *
 * 两个数据源，职责不同：
 *  1. WS 事件（即时）：知道"刚来了私信 / 通知"，并带会话 id（用于点通知直达会话）；
 *  2. HTTP 未读总数（精确）：冷启动与每次事件后校准"到底几条未读"。
 *
 * 事件先让角标立刻变化（不等网络），HTTP 回来后再校正数字；HTTP 一直失败就继续用
 * 事件推断值——**宁可少算，也不要凭空造数字**。
 *
 * 为什么不把"事件计数"当唯一事实源：同一会话连来 5 条消息是 5 条未读，而事件只知道
 * "这个会话有新消息"。网站是事实源（HTTP 是事实源，socket 只提示刷新，ADR-0015）。
 */
class UnreadBadge(private val maxTrackedConversations: Int = 20) {

    /** 未读私信条数（HTTP 校准值；未校准时是事件推断值）。 */
    var directMessages: Int = 0
        private set

    /** 未读通知条数（HTTP 校准值；未校准时是事件累加值）。 */
    var notifications: Int = 0
        private set

    private var dmCalibrated = false
    private var noticesCalibrated = false

    /** 事件带来的未读会话（严格按"最新在最后"排序；重复出现会被移到末尾）。 */
    private val conversations = LinkedHashSet<Int>()

    val total: Int get() = directMessages + notifications

    val hasUnread: Boolean get() = total > 0

    /** 通知该落到哪个会话；没有会话信息时返回 null（落点退化为私信列表）。 */
    val newestConversationId: Int? get() = conversations.lastOrNull()

    /** 记着的未读会话个数（诊断与上界测试用）。 */
    val trackedConversations: Int get() = conversations.size

    /** 收到 dm 事件。返回 true 表示角标需要刷新（撤回不是新消息，不刷新）。 */
    fun onDmEvent(conversationId: Int?, retracted: Boolean): Boolean {
        if (retracted) return false
        if (conversationId != null) {
            conversations.remove(conversationId)
            conversations.add(conversationId)
            while (conversations.size > maxTrackedConversations) {
                val oldest = conversations.firstOrNull() ?: break
                conversations.remove(oldest)
            }
        }
        if (!dmCalibrated) {
            directMessages = maxOf(conversations.size, 1)
        }
        return true
    }

    /** 收到 notification 事件。返回 true 表示角标需要刷新。 */
    fun onNotificationEvent(): Boolean {
        if (!noticesCalibrated) notifications += 1
        return true
    }

    /** HTTP 校准：未读私信条数。返回 true 表示数字有变化。 */
    fun applyDirectMessages(total: Int): Boolean {
        val value = total.coerceAtLeast(0)
        dmCalibrated = true
        val changed = value != directMessages
        directMessages = value
        if (value == 0) conversations.clear()
        return changed
    }

    /** HTTP 校准：未读通知条数。返回 true 表示数字有变化。 */
    fun applyNotifications(total: Int): Boolean {
        val value = total.coerceAtLeast(0)
        noticesCalibrated = true
        val changed = value != notifications
        notifications = value
        return changed
    }

    /**
     * 用户打开了某个会话（网页会调 mark_conversation_read，服务端联动把该会话的通知也标已读）。
     * 返回 true 表示角标需要刷新。已校准过的数字不在这里猜，等下一次 HTTP 校准。
     */
    fun onConversationOpened(conversationId: Int): Boolean {
        val removed = conversations.remove(conversationId)
        if (dmCalibrated || !removed) return false
        directMessages = conversations.size
        return true
    }

    /** 登出 / 换账号：全部清零并忘掉校准状态。 */
    fun reset() {
        directMessages = 0
        notifications = 0
        dmCalibrated = false
        noticesCalibrated = false
        conversations.clear()
    }

    /** 诊断用（日志 / 诊断浮层）。 */
    override fun toString(): String =
        "UnreadBadge(dm=" + directMessages + ", notices=" + notifications +
            ", conversations=" + conversations.size + ")"
}
