package com.nhyzcms.clubapp.shell.scan

/**
 * 「扫码核验」浮动按钮的显隐（纯逻辑，有 JVM 单测）。
 *
 * 只在页面真的画出来之后才出现：首屏骨架期间按下去会挡住加载，错误面板盖着时
 * 它还会挡住「重试」按钮；这两种情况下用户也没法扫码核验任何东西。
 *
 * 开关：清单 `showScanEntry`（默认 true）——运营上想把这颗按钮撤掉时不用发新版。
 */
object ScanEntryPolicy {

    fun shouldShow(flagEnabled: Boolean, pageLoaded: Boolean, errorVisible: Boolean): Boolean =
        flagEnabled && pageLoaded && !errorVisible
}
