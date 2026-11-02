package com.nhyzcms.clubapp.shell.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionPolicyTest {

    @Test
    fun recognisesHashRouteWithQuery() {
        assertTrue(CameraPermissionPolicy.isScanRoute("https://8.153.145.175/#/v"))
        assertTrue(CameraPermissionPolicy.isScanRoute("https://8.153.145.175/#/v?q=123456789012"))
        assertTrue(CameraPermissionPolicy.isScanRoute("https://8.153.145.175/#/v/"))
        assertTrue(CameraPermissionPolicy.isScanRoute("https://8.153.145.175/v"))
    }

    @Test
    fun ignoresOtherRoutes() {
        assertFalse(CameraPermissionPolicy.isScanRoute("https://8.153.145.175/#/m"))
        assertFalse(CameraPermissionPolicy.isScanRoute("https://8.153.145.175/"))
        // /v/<码> 是校验页：只出结果卡，不开取景
        assertFalse(CameraPermissionPolicy.isScanRoute("https://8.153.145.175/#/v/ABC12345"))
        assertFalse(CameraPermissionPolicy.isScanRoute("https://8.153.145.175/#/vx"))
        assertFalse(CameraPermissionPolicy.isScanRoute(null))
        assertFalse(CameraPermissionPolicy.isScanRoute(""))
    }

    @Test
    fun asksOnlyWhenMissingAndNotYetAsked() {
        assertTrue(CameraPermissionPolicy.shouldAskOnScanRoute(granted = false, askedForRoute = false, promptInFlight = false))
        assertFalse(CameraPermissionPolicy.shouldAskOnScanRoute(granted = true, askedForRoute = false, promptInFlight = false))
        assertFalse(CameraPermissionPolicy.shouldAskOnScanRoute(granted = false, askedForRoute = true, promptInFlight = false))
        assertFalse(CameraPermissionPolicy.shouldAskOnScanRoute(granted = false, askedForRoute = false, promptInFlight = true))
    }

    @Test
    fun holdsWebRequestOnlyWhenCameraExists() {
        assertEquals(
            CameraPermissionPolicy.WebAsk.Hold,
            CameraPermissionPolicy.webAskAction(hasCameraHardware = true),
        )
        assertEquals(
            CameraPermissionPolicy.WebAsk.Deny,
            CameraPermissionPolicy.webAskAction(hasCameraHardware = false),
        )
    }
}
