package com.nhyzcms.clubapp.shell.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [EdgeToEdgePolicy] 单测：铺满开关决定系统栏怎么处理、浮层要不要自己让位。 */
class EdgeToEdgePolicyTest {

    @Test
    fun decorFitsIsInverseOfEdgeToEdge() {
        assertTrue(EdgeToEdgePolicy.decorFitsSystemWindows(edgeToEdge = false))
        assertFalse(EdgeToEdgePolicy.decorFitsSystemWindows(edgeToEdge = true))
    }

    @Test
    fun statusBarIsTransparentOnlyWhenEdgeToEdge() {
        assertFalse(EdgeToEdgePolicy.statusBarTransparent(edgeToEdge = false))
        assertTrue(EdgeToEdgePolicy.statusBarTransparent(edgeToEdge = true))
    }

    @Test
    fun overlayMarginsOnlyGrowWhenEdgeToEdge() {
        assertEquals(16, EdgeToEdgePolicy.overlayBottomMarginPx(16, systemBarsBottomPx = 48, edgeToEdge = false))
        assertEquals(64, EdgeToEdgePolicy.overlayBottomMarginPx(16, systemBarsBottomPx = 48, edgeToEdge = true))

        assertEquals(0, EdgeToEdgePolicy.overlayTopMarginPx(0, systemBarsTopPx = 72, edgeToEdge = false))
        assertEquals(72, EdgeToEdgePolicy.overlayTopMarginPx(0, systemBarsTopPx = 72, edgeToEdge = true))
    }

    @Test
    fun zeroInsetsKeepBaseMargins() {
        assertEquals(24, EdgeToEdgePolicy.overlayBottomMarginPx(24, 0, edgeToEdge = true))
        assertEquals(24, EdgeToEdgePolicy.overlayBottomMarginPx(24, 0, edgeToEdge = false))
    }
}
