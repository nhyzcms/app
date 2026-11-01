package com.nhyzcms.clubapp.shell.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPermissionPolicyTest {

    private val site = "https://8.153.145.175"
    private val video = listOf(WebPermissionPolicy.RES_VIDEO_CAPTURE)

    @Test
    fun grantsVideoOnlyForSiteOrigin() {
        val decision = WebPermissionPolicy.decide(site, video, cameraGranted = true, siteOrigin = site)
        assertTrue(decision is WebPermissionDecision.Grant)
        assertEquals(
            listOf(WebPermissionPolicy.RES_VIDEO_CAPTURE),
            (decision as WebPermissionDecision.Grant).resources,
        )
    }

    @Test
    fun originComparisonIgnoresCaseAndTrailingSlash() {
        val decision = WebPermissionPolicy.decide(
            "HTTPS://8.153.145.175/",
            video,
            cameraGranted = true,
            siteOrigin = site,
        )
        assertTrue(decision is WebPermissionDecision.Grant)
    }

    @Test
    fun rejectsForeignOrigin() {
        val decision = WebPermissionPolicy.decide(
            "https://evil.example.com",
            video,
            cameraGranted = true,
            siteOrigin = site,
        )
        assertEquals(
            WebPermissionDecision.Deny(WebPermissionPolicy.REASON_FOREIGN_ORIGIN),
            decision,
        )
    }

    @Test
    fun rejectsMissingOrigin() {
        assertEquals(
            WebPermissionDecision.Deny(WebPermissionPolicy.REASON_FOREIGN_ORIGIN),
            WebPermissionPolicy.decide(null, video, cameraGranted = true, siteOrigin = site),
        )
    }

    @Test
    fun rejectsAudioOnlyRequest() {
        val decision = WebPermissionPolicy.decide(
            site,
            listOf(WebPermissionPolicy.RES_AUDIO_CAPTURE),
            cameraGranted = true,
            siteOrigin = site,
        )
        assertEquals(
            WebPermissionDecision.Deny(WebPermissionPolicy.REASON_NO_SUPPORTED_RESOURCE),
            decision,
        )
    }

    @Test
    fun rejectsMixedAudioAndVideoRequest() {
        val decision = WebPermissionPolicy.decide(
            site,
            listOf(WebPermissionPolicy.RES_VIDEO_CAPTURE, WebPermissionPolicy.RES_AUDIO_CAPTURE),
            cameraGranted = true,
            siteOrigin = site,
        )
        assertEquals(
            WebPermissionDecision.Deny(WebPermissionPolicy.REASON_NO_SUPPORTED_RESOURCE),
            decision,
        )
    }

    @Test
    fun rejectsWhenCameraPermissionNotGranted() {
        val decision = WebPermissionPolicy.decide(site, video, cameraGranted = false, siteOrigin = site)
        assertEquals(
            WebPermissionDecision.Deny(WebPermissionPolicy.REASON_CAMERA_PERMISSION),
            decision,
        )
    }
}
