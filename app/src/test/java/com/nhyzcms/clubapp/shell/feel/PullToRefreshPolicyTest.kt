package com.nhyzcms.clubapp.shell.feel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PullToRefreshPolicy] 单测：门控条件与「转圈超时」保护。 */
class PullToRefreshPolicyTest {

    @Test
    fun pullsOnlyWhenEnabledLoadedAndNoError() {
        assertTrue(PullToRefreshPolicy.canPull(enabled = true, errorPanelVisible = false, pageLoaded = true))
        assertFalse(PullToRefreshPolicy.canPull(enabled = false, errorPanelVisible = false, pageLoaded = true))
        assertFalse(PullToRefreshPolicy.canPull(enabled = true, errorPanelVisible = true, pageLoaded = true))
        assertFalse(PullToRefreshPolicy.canPull(enabled = true, errorPanelVisible = false, pageLoaded = false))
    }

    @Test
    fun spinNeverStopsWhenNotRefreshing() {
        // refreshStartedAtMs = 0 表示当前没有在刷新，任何时刻都不该「停」。
        assertFalse(PullToRefreshPolicy.shouldStopSpin(0L, 1_000_000L))
    }

    @Test
    fun spinStopsAfterTimeout() {
        val started = 1_000L
        assertFalse(PullToRefreshPolicy.shouldStopSpin(started, started + 14_999L))
        assertTrue(PullToRefreshPolicy.shouldStopSpin(started, started + 15_000L))
        assertTrue(PullToRefreshPolicy.shouldStopSpin(started, started + 60_000L))
    }

    @Test
    fun spinTimeoutIsConfigurable() {
        assertTrue(PullToRefreshPolicy.shouldStopSpin(1_000L, 2_000L, maxMs = 500L))
        assertFalse(PullToRefreshPolicy.shouldStopSpin(1_000L, 1_400L, maxMs = 500L))
    }
}
