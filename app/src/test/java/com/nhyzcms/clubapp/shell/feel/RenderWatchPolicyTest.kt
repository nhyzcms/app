package com.nhyzcms.clubapp.shell.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首屏看护复核的判定（区块 6：修掉"慢速首屏弹诊断且不消失"的误报）。
 *
 * 这些用例钉住的是**行为契约**，不是实现细节：
 *   · 单次空白不算数（必须连续两次）；
 *   · 没到点不下结论；
 *   · 拿不到探测结果只在到点时才当成故障；
 *   · 内容一出现就必须算"画出来了"（诊断浮层据此撤销）。
 */
class RenderWatchPolicyTest {

    private fun rendered() = ContentProbe.Snapshot(
        rootChildren = 3,
        bodyChars = 5_000,
        scriptTags = 2,
        hasBundleGlobal = true,
    )

    private fun blank() = ContentProbe.Snapshot(
        rootChildren = 0,
        bodyChars = 20,
        scriptTags = 2,
        hasBundleGlobal = true,
    )

    /** 探测结果不可信：拿不到 body（bodyChars = -1）。 */
    private fun untrusted() = ContentProbe.Snapshot(
        rootChildren = -1,
        bodyChars = -1,
        scriptTags = 0,
        hasBundleGlobal = false,
    )

    @Test
    fun `画出来了就是 REVEAL（不等 load 事件）`() {
        assertEquals(
            RenderWatchPolicy.Verdict.REVEAL,
            RenderWatchPolicy.verdict(rendered(), deadlineReached = false, blankStreak = 0),
        )
        assertEquals(
            RenderWatchPolicy.Verdict.REVEAL,
            RenderWatchPolicy.verdict(rendered(), deadlineReached = true, blankStreak = 7),
        )
    }

    @Test
    fun `空 DOM 但没到点继续等`() {
        assertEquals(
            RenderWatchPolicy.Verdict.KEEP_WAITING,
            RenderWatchPolicy.verdict(blank(), deadlineReached = false, blankStreak = 1),
        )
    }

    @Test
    fun `到点前只空了一次不下结论（单次抖动不算）`() {
        assertEquals(
            RenderWatchPolicy.Verdict.KEEP_WAITING,
            RenderWatchPolicy.verdict(blank(), deadlineReached = true, blankStreak = 1),
        )
    }

    @Test
    fun `到点且连续两次空才列诊断`() {
        assertEquals(
            RenderWatchPolicy.Verdict.DIAGNOSE,
            RenderWatchPolicy.verdict(blank(), deadlineReached = true, blankStreak = 2),
        )
        assertEquals(
            RenderWatchPolicy.Verdict.DIAGNOSE,
            RenderWatchPolicy.verdict(blank(), deadlineReached = true, blankStreak = 5),
        )
    }

    @Test
    fun `拿不到探测结果时只在到点才当故障`() {
        assertEquals(
            RenderWatchPolicy.Verdict.KEEP_WAITING,
            RenderWatchPolicy.verdict(null, deadlineReached = false, blankStreak = 3),
        )
        assertEquals(
            RenderWatchPolicy.Verdict.DIAGNOSE,
            RenderWatchPolicy.verdict(null, deadlineReached = true, blankStreak = 3),
        )
    }

    @Test
    fun `探测结果不可信时视为有内容（不下空白结论）`() {
        // bodyChars < 0 = 那一刻可能正在导航，误判会让骨架被提前揭开。
        assertTrue(RenderWatchPolicy.contentAppeared(untrusted()))
        assertEquals(
            RenderWatchPolicy.Verdict.REVEAL,
            RenderWatchPolicy.verdict(untrusted(), deadlineReached = true, blankStreak = 9),
        )
    }

    @Test
    fun `contentAppeared 的三个分支`() {
        assertFalse(RenderWatchPolicy.contentAppeared(null))
        assertFalse(RenderWatchPolicy.contentAppeared(blank()))
        assertTrue(RenderWatchPolicy.contentAppeared(rendered()))
    }

    @Test
    fun `复核时刻表是递增的且会结束`() {
        val delays = (0..3).map { RenderWatchPolicy.nextRecheckDelay(it) }
        assertEquals(listOf(5_000L, 12_000L, 25_000L, 45_000L), delays)
        for (index in 1 until delays.size) {
            assertTrue("复核间隔必须递增", delays[index]!! > delays[index - 1]!!)
        }
        assertNull(RenderWatchPolicy.nextRecheckDelay(4))
        assertEquals(45_000L, RenderWatchPolicy.lastRecheckDelay())
    }

    @Test
    fun `撤销轮询间隔递增并用尽后停止`() {
        val delays = (0..4).map { RenderWatchPolicy.nextClearPollDelay(it) }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L), delays)
        for (index in 1 until delays.size) {
            assertTrue("撤销轮询必须退避", delays[index]!! >= delays[index - 1]!!)
        }
        assertNull("轮询必须会停：真空白时浮层要留给用户复制", RenderWatchPolicy.nextClearPollDelay(5))
    }

    @Test
    fun `撤销轮询窗口是各档之和（用来说明最多盯这么久）`() {
        assertEquals(59_000L, RenderWatchPolicy.clearPollWindowMs())
    }

    @Test
    fun `要求连续两次空白（常量被改小会让误报回来）`() {
        assertEquals(2, RenderWatchPolicy.REQUIRED_BLANK_STREAK)
    }
}
