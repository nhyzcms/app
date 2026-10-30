package com.nhyzcms.clubapp.shell.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 扫码按钮显隐：三个条件同时成立才出现。 */
class ScanEntryPolicyTest {

    @Test
    fun `页面画完且没有错误面板时出现`() {
        assertTrue(ScanEntryPolicy.shouldShow(flagEnabled = true, pageLoaded = true, errorVisible = false))
    }

    @Test
    fun `清单开关关掉就不出现`() {
        assertFalse(ScanEntryPolicy.shouldShow(flagEnabled = false, pageLoaded = true, errorVisible = false))
    }

    @Test
    fun `首屏还没画出来时不出现`() {
        assertFalse(ScanEntryPolicy.shouldShow(flagEnabled = true, pageLoaded = false, errorVisible = false))
    }

    @Test
    fun `错误面板盖着时不出现（否则挡住重试按钮）`() {
        assertFalse(ScanEntryPolicy.shouldShow(flagEnabled = true, pageLoaded = true, errorVisible = true))
    }
}
