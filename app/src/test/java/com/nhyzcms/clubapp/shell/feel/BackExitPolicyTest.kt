package com.nhyzcms.clubapp.shell.feel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BackExitPolicy] 单测：确保「误触返回不会直接退出 App」这条验收标准有测试兜着。
 */
class BackExitPolicyTest {

    @Test
    fun goesBackWhileHistoryExists() {
        assertEquals(
            BackExitPolicy.Action.GO_BACK,
            BackExitPolicy.decide(canGoBack = true, lastConfirmAtMs = 0L, nowMs = 1_000L),
        )
    }

    @Test
    fun canGoBackWinsEvenInsideConfirmWindow() {
        // 历史能后退时，即使刚提示过退出，也要继续后退（不能把用户困住）。
        assertEquals(
            BackExitPolicy.Action.GO_BACK,
            BackExitPolicy.decide(canGoBack = true, lastConfirmAtMs = 1_000L, nowMs = 1_500L),
        )
    }

    @Test
    fun firstBackOnStartPageOnlyConfirms() {
        assertEquals(
            BackExitPolicy.Action.CONFIRM_EXIT,
            BackExitPolicy.decide(canGoBack = false, lastConfirmAtMs = 0L, nowMs = 10_000L),
        )
    }

    @Test
    fun secondBackInsideWindowExits() {
        assertEquals(
            BackExitPolicy.Action.EXIT,
            BackExitPolicy.decide(canGoBack = false, lastConfirmAtMs = 10_000L, nowMs = 11_000L),
        )
    }

    @Test
    fun secondBackExactlyAtWindowEdgeExits() {
        assertEquals(
            BackExitPolicy.Action.EXIT,
            BackExitPolicy.decide(
                canGoBack = false,
                lastConfirmAtMs = 10_000L,
                nowMs = 10_000L + BackExitPolicy.CONFIRM_WINDOW_MS,
            ),
        )
    }

    @Test
    fun secondBackAfterWindowConfirmsAgain() {
        assertEquals(
            BackExitPolicy.Action.CONFIRM_EXIT,
            BackExitPolicy.decide(canGoBack = false, lastConfirmAtMs = 10_000L, nowMs = 12_001L),
        )
    }

    @Test
    fun clockGoingBackwardsDoesNotExit() {
        // 用户把系统时间改小：差值变负，必须重新提示而不是直接退出。
        assertEquals(
            BackExitPolicy.Action.CONFIRM_EXIT,
            BackExitPolicy.decide(canGoBack = false, lastConfirmAtMs = 20_000L, nowMs = 15_000L),
        )
    }
}
