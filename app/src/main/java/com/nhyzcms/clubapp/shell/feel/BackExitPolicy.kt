package com.nhyzcms.clubapp.shell.feel

/**
 * 返回键策略（纯逻辑，可 JVM 单测）。
 *
 * 目标（区块 4 验收点）：**误触返回不会直接退出 App**。
 * 规则：
 *   1. 网页还能后退 → 后退（交给 WebView）；
 *   2. 已在起始页 → 第一次返回只提示「再按一次退出应用」，不退出；
 *   3. 提示后 [CONFIRM_WINDOW_MS] 内再按一次 → 才真正退出。
 *
 * 说明：Android 13+ 的预测式返回（predictive back）由系统负责动画，
 * 前提是清单里开了 `android:enableOnBackInvokedCallback="true"` 并使用
 * `OnBackPressedCallback`（MainActivity 已如此）。本策略只决定「按下去做什么」，
 * 与动画无关，所以可以脱离 Android 单测。
 */
object BackExitPolicy {

    /** 双击退出的时间窗（毫秒）。 */
    const val CONFIRM_WINDOW_MS = 2000L

    /** 决策结果。 */
    enum class Action {
        /** 网页后退。 */
        GO_BACK,

        /** 提示「再按一次退出应用」，并记录本次时间戳。 */
        CONFIRM_EXIT,

        /** 真正退出应用。 */
        EXIT,
    }

    /**
     * @param canGoBack 网页是否还能后退（`WebView.canGoBack()`）。
     * @param lastConfirmAtMs 上一次提示「再按一次退出」的时间戳（毫秒，0 = 从未提示过）。
     * @param nowMs 当前时间戳（毫秒）。
     * @param windowMs 时间窗，默认 [CONFIRM_WINDOW_MS]。
     */
    fun decide(
        canGoBack: Boolean,
        lastConfirmAtMs: Long,
        nowMs: Long,
        windowMs: Long = CONFIRM_WINDOW_MS,
    ): Action = when {
        canGoBack -> Action.GO_BACK
        // 时钟可能被用户改小（差值为负），这种异常情况按「重新提示」处理，不直接退出。
        lastConfirmAtMs > 0L && nowMs - lastConfirmAtMs in 0L..windowMs -> Action.EXIT
        else -> Action.CONFIRM_EXIT
    }
}
