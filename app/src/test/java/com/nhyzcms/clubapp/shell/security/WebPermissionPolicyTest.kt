package com.nhyzcms.clubapp.shell.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WebPermissionPolicyTest {

    private val site = "https://8.153.145.175"
    private val video = listOf(WebPermissionPolicy.RES_VIDEO_CAPTURE)

    @Test
    fun `本站请求摄像头且系统权限已给 —— 只授予视频采集`() {
        val decision = WebPermissionPolicy.decide(site, video, cameraGranted = true, siteOrigin = site)
        val grant = assertIs<WebPermissionDecision.Grant>(decision)
        assertEquals(listOf(WebPermissionPolicy.RES_VIDEO_CAPTURE), grant.resources)
    }

    @Test
    fun `origin 大小写与结尾斜杠不影响判定`() {
        val decision = WebPermissionPolicy.decide(
            "HTTPS://8.153.145.175/",
            video,
            cameraGranted = true,
            siteOrigin = site,
        )
        assertIs<WebPermissionDecision.Grant>(decision)
    }

    @Test
    fun `外部 origin 一律拒绝`() {
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
    fun `缺失 origin 也拒绝`() {
        assertEquals(
            WebPermissionDecision.Deny(WebPermissionPolicy.REASON_FOREIGN_ORIGIN),
            WebPermissionPolicy.decide(null, video, cameraGranted = true, siteOrigin = site),
        )
    }

    @Test
    fun `只要麦克风时整单拒绝`() {
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
    fun `同时要麦克风与摄像头也整单拒绝（不做部分授予）`() {
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
    fun `系统层还没给相机权限时拒绝（先引导授权，不静默放行）`() {
        val decision = WebPermissionPolicy.decide(site, video, cameraGranted = false, siteOrigin = site)
        assertEquals(
            WebPermissionDecision.Deny(WebPermissionPolicy.REASON_CAMERA_PERMISSION),
            decision,
        )
    }
}
