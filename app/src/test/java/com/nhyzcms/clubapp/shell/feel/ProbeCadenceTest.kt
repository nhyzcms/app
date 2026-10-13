package com.nhyzcms.clubapp.shell.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探测节奏的退避曲线（区块 6：把固定 2 s 的轮询改成"稳定就退避、有变化立刻回快档"）。
 *
 * 关键契约：
 *   · 有变化 → 立刻回到基础间隔（主题色/滚动位置的变化必须被及时发现）；
 *   · 连续无变化 → 逐档退避，封顶在 max（不会无限退）；
 *   · 任何情况下都不快于 [ProbeCadence.MIN_INTERVAL_MS]。
 */
class ProbeCadenceTest {

    @Test
    fun `首次探测算有变化，回到基础间隔`() {
        val cadence = ProbeCadence()
        assertEquals(2_000L, cadence.onResult(changed = true))
        assertEquals(2_000L, cadence.currentIntervalMs)
    }

    @Test
    fun `连续稳定逐档退避并封顶`() {
        val cadence = ProbeCadence()
        // 每 3 次无变化升一档：2 s → 4 s → 8 s → 15 s（封顶）
        val observed = (1..12).map { cadence.onResult(changed = false) }
        assertEquals(
            listOf(
                2_000L, 2_000L, 4_000L, // 第 1~3 次
                4_000L, 4_000L, 8_000L, // 第 4~6 次
                8_000L, 8_000L, 15_000L, // 第 7~9 次
                15_000L, 15_000L, 15_000L, // 第 10~12 次：封顶不再涨
            ),
            observed,
        )
    }

    @Test
    fun `有变化立刻回快档`() {
        val cadence = ProbeCadence()
        val before = (1..9).map { cadence.onResult(changed = false) }.last()
        assertEquals(15_000L, before)
        assertEquals(2_000L, cadence.onResult(changed = true))
        assertEquals("回快档后必须重新从基础间隔开始退避", 2_000L, cadence.onResult(changed = false))
    }

    @Test
    fun `reset 回到基础间隔（导航与回到前台用）`() {
        val cadence = ProbeCadence()
        val before = (1..9).map { cadence.onResult(changed = false) }.last()
        assertEquals(15_000L, before)
        assertEquals(2_000L, cadence.reset())
        assertEquals(2_000L, cadence.currentIntervalMs)
    }

    @Test
    fun `低内存档位：基础与上限都被放大`() {
        val cadence = ProbeCadence(baseIntervalMs = 3_000L, maxIntervalMs = 30_000L)
        assertEquals(3_000L, cadence.onResult(changed = true))
        val observed = (1..12).map { cadence.onResult(changed = false) }
        assertEquals(3_000L, observed[0])
        assertEquals(6_000L, observed[2])
        assertEquals(12_000L, observed[5])
        assertEquals(24_000L, observed[8])
        assertEquals("放大后的上限是 30 s", 30_000L, observed[11])
    }

    @Test
    fun `基础间隔有下限，调用方给再小也会被收口`() {
        val cadence = ProbeCadence(baseIntervalMs = 10L)
        assertEquals(ProbeCadence.MIN_INTERVAL_MS, cadence.onResult(changed = true))
    }

    @Test
    fun `稳定次数给 0 也不崩（防除零，归一为每 1 次升一档）`() {
        val cadence = ProbeCadence(stableTicksToRelax = 0)
        assertEquals("每 1 次无变化就升一档", 4_000L, cadence.onResult(changed = false))
        assertEquals(8_000L, cadence.onResult(changed = false))
    }

    @Test
    fun `退避永远不超过上限（哪怕升档次数很多）`() {
        val cadence = ProbeCadence(baseIntervalMs = 2_000L, maxIntervalMs = 15_000L)
        repeat(200) { cadence.onResult(changed = false) }
        assertTrue(cadence.currentIntervalMs <= 15_000L)
    }
}
