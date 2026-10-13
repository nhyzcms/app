package com.nhyzcms.clubapp.shell.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角标状态机：事件（即时、可能不准）与 HTTP 校准（准确、但慢）的交汇逻辑，
 * 是"数字看起来怪"这类问题唯一能被测到的地方。
 */
class UnreadBadgeTest {

    @Test
    fun startsEmpty() {
        val badge = UnreadBadge()
        assertEquals(0, badge.total)
        assertFalse(badge.hasUnread)
        assertNull(badge.newestConversationId)
    }

    @Test
    fun dmEventRaisesBadgeBeforeAnyCalibration() {
        val badge = UnreadBadge()
        assertTrue(badge.onDmEvent(7, retracted = false))
        assertEquals(1, badge.total)
        assertEquals(7, badge.newestConversationId)
    }

    @Test
    fun dmEventWithoutConversationIdStillRaisesBadge() {
        val badge = UnreadBadge()
        assertTrue(badge.onDmEvent(null, retracted = false))
        assertEquals(1, badge.total)
        assertNull(badge.newestConversationId)
    }

    @Test
    fun repeatedDmInSameConversationCountsOnceWhenUncalibrated() {
        val badge = UnreadBadge()
        badge.onDmEvent(7, retracted = false)
        badge.onDmEvent(7, retracted = false)
        assertEquals(1, badge.directMessages)
    }

    @Test
    fun retractedMessageDoesNotRaiseBadge() {
        val badge = UnreadBadge()
        assertFalse(badge.onDmEvent(7, retracted = true))
        assertEquals(0, badge.total)
    }

    @Test
    fun httpCalibrationReplacesEstimate() {
        val badge = UnreadBadge()
        badge.onDmEvent(7, retracted = false)
        assertTrue(badge.applyDirectMessages(5))
        assertEquals(5, badge.directMessages)
    }

    @Test
    fun calibratedBadgeDoesNotInflateOnEvents() {
        val badge = UnreadBadge()
        badge.applyDirectMessages(3)
        badge.onDmEvent(7, retracted = false)
        assertEquals(3, badge.directMessages)
        assertEquals(7, badge.newestConversationId)
    }

    @Test
    fun calibrationReportsWhetherNumberChanged() {
        val badge = UnreadBadge()
        assertTrue(badge.applyDirectMessages(1))
        assertFalse(badge.applyDirectMessages(1))
    }

    @Test
    fun notificationEventsAccumulateUntilCalibrated() {
        val badge = UnreadBadge()
        badge.onNotificationEvent()
        badge.onNotificationEvent()
        assertEquals(2, badge.notifications)
        badge.applyNotifications(0)
        assertEquals(0, badge.notifications)
    }

    @Test
    fun openingConversationDropsItWhenUncalibrated() {
        val badge = UnreadBadge()
        badge.onDmEvent(1, retracted = false)
        badge.onDmEvent(2, retracted = false)
        assertEquals(2, badge.directMessages)
        assertTrue(badge.onConversationOpened(2))
        assertEquals(1, badge.directMessages)
        assertEquals(1, badge.newestConversationId)
    }

    @Test
    fun openingConversationIsNoOpOnceCalibrated() {
        val badge = UnreadBadge()
        badge.applyDirectMessages(4)
        assertFalse(badge.onConversationOpened(2))
        assertEquals(4, badge.directMessages)
    }

    @Test
    fun openingUnknownConversationIsNoOp() {
        val badge = UnreadBadge()
        assertFalse(badge.onConversationOpened(99))
    }

    @Test
    fun calibrationToZeroClearsConversations() {
        val badge = UnreadBadge()
        badge.onDmEvent(3, retracted = false)
        badge.applyDirectMessages(0)
        assertNull(badge.newestConversationId)
        assertEquals(0, badge.total)
    }

    @Test
    fun newestConversationIsTheMostRecentEvent() {
        val badge = UnreadBadge()
        badge.onDmEvent(1, retracted = false)
        badge.onDmEvent(2, retracted = false)
        badge.onDmEvent(1, retracted = false)
        assertEquals(1, badge.newestConversationId)
    }

    @Test
    fun trackedConversationsAreBounded() {
        val badge = UnreadBadge(maxTrackedConversations = 2)
        badge.onDmEvent(1, retracted = false)
        badge.onDmEvent(2, retracted = false)
        badge.onDmEvent(3, retracted = false)
        assertEquals(2, badge.trackedConversations)
        assertEquals(3, badge.newestConversationId)
    }

    @Test
    fun totalCombinesDmAndNotifications() {
        val badge = UnreadBadge()
        badge.applyDirectMessages(2)
        badge.applyNotifications(3)
        assertEquals(5, badge.total)
        assertTrue(badge.hasUnread)
    }

    @Test
    fun negativeCalibrationIsClampedToZero() {
        val badge = UnreadBadge()
        badge.applyDirectMessages(-5)
        badge.applyNotifications(-1)
        assertEquals(0, badge.total)
    }

    @Test
    fun resetClearsEverything() {
        val badge = UnreadBadge()
        badge.onDmEvent(1, retracted = false)
        badge.onNotificationEvent()
        badge.reset()
        assertEquals(0, badge.total)
        assertNull(badge.newestConversationId)
        assertEquals(0, badge.trackedConversations)
    }

    @Test
    fun toStringCarriesDiagnostics() {
        val badge = UnreadBadge()
        badge.onDmEvent(4, retracted = false)
        assertTrue(badge.toString().contains("conversations=1"))
    }
}
