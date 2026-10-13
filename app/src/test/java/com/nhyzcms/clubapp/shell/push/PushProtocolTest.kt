package com.nhyzcms.clubapp.shell.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 事件样例逐字取自网站侧 `messaging/services.py` 的 push 调用。 */
class PushProtocolTest {

    @Test
    fun parsesDmEvent() {
        val raw = "{\"event\":\"dm\",\"payload\":{\"conversation_id\":12,\"message_id\":345}}"
        val event = PushProtocol.parse(raw)
        assertTrue(event is PushProtocol.Event.Dm)
        val dm = event as PushProtocol.Event.Dm
        assertEquals(12, dm.conversationId)
        assertEquals(345, dm.messageId)
        assertFalse(dm.retracted)
    }

    @Test
    fun parsesRetractedDm() {
        val raw = "{\"event\":\"dm\",\"payload\":{\"conversation_id\":12,\"message_id\":9,\"retracted\":true}}"
        val dm = PushProtocol.parse(raw) as PushProtocol.Event.Dm
        assertTrue(dm.retracted)
    }

    @Test
    fun parsesNotificationEvent() {
        val raw = "{\"event\":\"notification\",\"payload\":{\"notification_id\":77,\"category\":\"comment\"}}"
        val notice = PushProtocol.parse(raw) as PushProtocol.Event.Notification
        assertEquals(77, notice.notificationId)
        assertEquals("comment", notice.category)
    }

    @Test
    fun parsesCommentEvent() {
        val raw = "{\"event\":\"comment\",\"payload\":{\"thread_id\":5,\"comment_id\":6}}"
        val comment = PushProtocol.parse(raw) as PushProtocol.Event.Comment
        assertEquals(5, comment.threadId)
    }

    @Test
    fun ignoresUnknownEventNames() {
        assertNull(PushProtocol.parse("{\"event\":\"typing\",\"payload\":{}}"))
        assertNull(PushProtocol.parse("{\"event\":\"unread\",\"payload\":{}}"))
    }

    @Test
    fun ignoresMalformedInput() {
        assertNull(PushProtocol.parse(null))
        assertNull(PushProtocol.parse(""))
        assertNull(PushProtocol.parse("not json at all"))
        assertNull(PushProtocol.parse("[1,2,3]"))
        assertNull(PushProtocol.parse("{\"payload\":{}}"))
    }

    @Test
    fun toleratesMissingPayloadFields() {
        val dm = PushProtocol.parse("{\"event\":\"dm\",\"payload\":{}}") as PushProtocol.Event.Dm
        assertNull(dm.conversationId)
        assertNull(dm.messageId)
        val notice = PushProtocol.parse("{\"event\":\"notification\"}") as PushProtocol.Event.Notification
        assertNull(notice.notificationId)
        assertEquals("", notice.category)
    }

    @Test
    fun acceptsNumericIdsGivenAsStrings() {
        val dm = PushProtocol.parse("{\"event\":\"dm\",\"payload\":{\"conversation_id\":\"12\"}}")
            as PushProtocol.Event.Dm
        assertEquals(12, dm.conversationId)
    }

    @Test
    fun rejectsNegativeAndNonNumericIds() {
        val negative = PushProtocol.parse("{\"event\":\"dm\",\"payload\":{\"conversation_id\":-3}}")
            as PushProtocol.Event.Dm
        assertNull(negative.conversationId)
        val junk = PushProtocol.parse("{\"event\":\"dm\",\"payload\":{\"conversation_id\":\"x\"}}")
            as PushProtocol.Event.Dm
        assertNull(junk.conversationId)
    }

    @Test
    fun buildsConversationRoute() {
        assertEquals("/#/messages/12", PushProtocol.routeForConversation(12))
        assertEquals("/#/messages", PushProtocol.PATH_MESSAGES)
    }
}
