package com.nhyzcms.clubapp.shell.feel

/**
 * 首屏看护的「复核定策」（**纯逻辑**，不 import android.*，可在 JVM 单测）。
 *
 * ## 它解决什么问题（2026-10-12 真机反馈的误报）
 * 原先的看护只有**一次**机会：`onPageFinished` 之后 5 秒复核一次 DOM，只要是空的就弹诊断浮层 ——
 * 而浮层**之后再没有撤销路径**（唯一的撤销点在 `onPageFinished`，可它已经过去了）。
 * 在 3 M 带宽 + 大图首页上，5 秒往往只是「首屏还没画完」，于是出现
 * 「打开 `/#/u/6` 时弹出诊断浮层并一直挂着」这种误报。
 *
 * 现在改成三条规则：
 *   ① **多次重试复核**：[RECHECK_DELAYS_MS] 是递增的复核时刻（5 s / 12 s / 25 s / 45 s），
 *      中途任何一次画出来了就按「有内容」处理；
 *   ② **只在真空白时下结论**：要求连续 [REQUIRED_BLANK_STREAK] 次都探测到空 DOM，
 *      单次抖动（脚本刚执行、页面正在导航）不算；
 *   ③ 诊断浮层一旦出现，[CLEAR_POLL_DELAYS_MS] 会继续轻量轮询，**内容一出现立即撤销浮层**，
 *      只有「真的空白」才会把浮层留在屏幕上（用户仍可点一下复制诊断文本）。
 *
 * 分工：[ContentProbe] 负责「DOM 事实」，本文件只负责「什么时候下什么结论」。
 */
object RenderWatchPolicy {

    /** onPageFinished 之后的复核时刻（毫秒，相对 onPageFinished 起算），递增退避。 */
    val RECHECK_DELAYS_MS: LongArray = longArrayOf(5_000L, 12_000L, 25_000L, 45_000L)

    /** 诊断浮层出现后的撤销轮询间隔（毫秒）；用尽后停止轮询（＝真空白，浮层留给用户复制）。 */
    val CLEAR_POLL_DELAYS_MS: LongArray = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L)

    /** 判定「真空白」所需的连续空白次数（单次抖动不算）。 */
    const val REQUIRED_BLANK_STREAK = 2

    /** 一次复核的结论。 */
    enum class Verdict {
        /** 继续等（还没到点，或只是单次抖动）。 */
        KEEP_WAITING,

        /** 网页画出来了：揭开骨架、收起诊断、让下拉刷新与返回顶部可用。 */
        REVEAL,

        /** 到点且确认是空的：揭开骨架并列诊断（随后由撤销轮询继续盯着）。 */
        DIAGNOSE,
    }

    /**
     * 复核结论。
     *
     * @param snapshot        本次 DOM 探测结果（null = 拿不到，页面可能正在导航或 JS 没跑起来）
     * @param deadlineReached 是否已到「必须给用户一个交代」的时刻
     * @param blankStreak     连续探测到空 DOM 的次数（含本次）
     */
    fun verdict(
        snapshot: ContentProbe.Snapshot?,
        deadlineReached: Boolean,
        blankStreak: Int,
    ): Verdict {
        if (contentAppeared(snapshot)) return Verdict.REVEAL
        if (snapshot == null) {
            // 拿不到探测结果：页面可能在导航，也可能 JS 根本没跑起来 —— 只有到点才下结论。
            return if (deadlineReached) Verdict.DIAGNOSE else Verdict.KEEP_WAITING
        }
        return if (deadlineReached && blankStreak >= REQUIRED_BLANK_STREAK) {
            Verdict.DIAGNOSE
        } else {
            Verdict.KEEP_WAITING
        }
    }

    /** 网页画出内容了吗（＝揭开骨架 + 撤销诊断浮层的判据）。 */
    fun contentAppeared(snapshot: ContentProbe.Snapshot?): Boolean =
        snapshot != null && !ContentProbe.looksUnrendered(snapshot)

    /** 第 [attempt] 次复核的延迟；null = 复核次数用尽，不再复核。 */
    fun nextRecheckDelay(attempt: Int): Long? = RECHECK_DELAYS_MS.getOrNull(attempt)

    /** 第 [attempt] 次撤销轮询的延迟；null = 停止轮询（真空白，浮层保留给用户复制）。 */
    fun nextClearPollDelay(attempt: Int): Long? = CLEAR_POLL_DELAYS_MS.getOrNull(attempt)

    /** 最后一次复核的延迟（用来算「最迟什么时候给出结论」）。 */
    fun lastRecheckDelay(): Long = RECHECK_DELAYS_MS.lastOrNull() ?: 0L

    /** 撤销轮询的总时长上限（毫秒）：这段时间内一直盯着，之后接受「真空白」并把浮层留着。 */
    fun clearPollWindowMs(): Long = CLEAR_POLL_DELAYS_MS.sum()
}
