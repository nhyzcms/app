package com.nhyzcms.clubapp.shell.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FabPolicy] 单测：阈值换算与三个显示条件。 */
class FabPolicyTest {

    @Test
    fun convertsThresholdToPixels() {
        assertEquals(600, FabPolicy.thresholdPx(1.0f))
        assertEquals(1200, FabPolicy.thresholdPx(2.0f))
        assertEquals(1800, FabPolicy.thresholdPx(3.0f))
    }

    @Test
    fun thresholdIsAtLeastOnePixel() {
        assertEquals(1, FabPolicy.thresholdPx(0f))
        assertEquals(1, FabPolicy.thresholdPx(0.001f))
    }

    @Test
    fun showsOnlyAfterPassingThreshold() {
        assertFalse(FabPolicy.shouldShow(0, 1200, enabled = true, pageLoaded = true))
        assertFalse(FabPolicy.shouldShow(1199, 1200, enabled = true, pageLoaded = true))
        assertTrue(FabPolicy.shouldShow(1200, 1200, enabled = true, pageLoaded = true))
        assertTrue(FabPolicy.shouldShow(50_000, 1200, enabled = true, pageLoaded = true))
    }

    @Test
    fun respectsManifestFlagAndLoadState() {
        assertFalse(FabPolicy.shouldShow(5000, 1200, enabled = false, pageLoaded = true))
        assertFalse(FabPolicy.shouldShow(5000, 1200, enabled = true, pageLoaded = false))
    }
}
