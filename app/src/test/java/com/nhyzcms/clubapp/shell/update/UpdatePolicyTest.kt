package com.nhyzcms.clubapp.shell.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [UpdatePolicy] 的单测：这是更新流程的安全边界，规则必须逐条钉死。 */
class UpdatePolicyTest {

    private val hosts = setOf("8.153.145.175", "github.com", "objects.githubusercontent.com")
    private val sha = "b".repeat(64)

    private fun manifest(
        latestVersionCode: Int = 2,
        mandatory: Boolean = false,
        minShellVersionCode: Int = 0,
        apkSha256: String = sha,
        apkUrl: String = "https://8.153.145.175/app-update/latest.apk",
        apkSize: Long = 3_000_000L,
        blocked: List<BlockedVersion> = emptyList(),
        sources: List<String> = emptyList(),
        flags: Map<String, Boolean> = emptyMap(),
    ) = UpdateManifest(
        schema = 1,
        generatedAt = null,
        startRoute = "/#/app",
        flags = flags,
        notice = null,
        minShellVersionCode = minShellVersionCode,
        latest = LatestRelease(
            versionCode = latestVersionCode,
            versionName = "1.0." + latestVersionCode,
            notes = null,
            mandatory = mandatory,
            apk = ApkInfo(url = apkUrl, sha256 = apkSha256, size = apkSize),
        ),
        blockedVersions = blocked,
        updateSources = sources,
    )

    // ── 起始路由 ────────────────────────────────────────────────────────────

    @Test
    fun startRouteAcceptsInAppRoutes() {
        assertEquals("/#/app", UpdatePolicy.sanitizeStartRoute("/#/app", "/#/m"))
        assertEquals("/#/news/128", UpdatePolicy.sanitizeStartRoute("/#/news/128", "/#/m"))
        assertEquals("/#/m", UpdatePolicy.sanitizeStartRoute("/#/m", "/#/m"))
    }

    @Test
    fun startRouteRejectsForeignOrOddValues() {
        val fallback = "/#/m"
        assertEquals(fallback, UpdatePolicy.sanitizeStartRoute(null, fallback))
        assertEquals(fallback, UpdatePolicy.sanitizeStartRoute("", fallback))
        assertEquals(fallback, UpdatePolicy.sanitizeStartRoute("   ", fallback))
        assertEquals(fallback, UpdatePolicy.sanitizeStartRoute("/app", fallback))
        assertEquals(fallback, UpdatePolicy.sanitizeStartRoute("https://evil.example/x", fallback))
        assertEquals(fallback, UpdatePolicy.sanitizeStartRoute("//evil.example", fallback))
        assertEquals(fallback, UpdatePolicy.sanitizeStartRoute("/#/a b", fallback))
        assertEquals(fallback, UpdatePolicy.sanitizeStartRoute("/#/a\tb", fallback))
        assertEquals(fallback, UpdatePolicy.sanitizeStartRoute("/#/a\\b", fallback))
        assertEquals(fallback, UpdatePolicy.sanitizeStartRoute("/#/user@host", fallback))
        assertEquals(fallback, UpdatePolicy.sanitizeStartRoute("/#/x://y", fallback))
        assertEquals(fallback, UpdatePolicy.sanitizeStartRoute("/#/" + "x".repeat(200), fallback))
    }

    // ── 摘要与体积 ──────────────────────────────────────────────────────────

    @Test
    fun sha256FormatIsEnforced() {
        assertTrue(UpdatePolicy.isValidSha256("a".repeat(64)))
        assertTrue(UpdatePolicy.isValidSha256("0123456789abcdef".repeat(4)))
        assertTrue(UpdatePolicy.isValidSha256("A".repeat(64)))
        assertFalse(UpdatePolicy.isValidSha256(null))
        assertFalse(UpdatePolicy.isValidSha256(""))
        assertFalse(UpdatePolicy.isValidSha256("a".repeat(63)))
        assertFalse(UpdatePolicy.isValidSha256("a".repeat(65)))
        assertFalse(UpdatePolicy.isValidSha256("g".repeat(64)))
        assertFalse(UpdatePolicy.isValidSha256("z".repeat(64)))
    }

    @Test
    fun sizePlausibility() {
        assertTrue(UpdatePolicy.isPlausibleSize(0L))
        assertTrue(UpdatePolicy.isPlausibleSize(UpdatePolicy.MIN_APK_BYTES))
        assertTrue(UpdatePolicy.isPlausibleSize(5_000_000L))
        assertFalse(UpdatePolicy.isPlausibleSize(1L))
        assertFalse(UpdatePolicy.isPlausibleSize(UpdatePolicy.MAX_APK_BYTES + 1))
    }

    // ── 地址白名单 ──────────────────────────────────────────────────────────

    @Test
    fun allowsOnlyWhitelistedHttpsHosts() {
        assertTrue(UpdatePolicy.isAllowedHttpsUrl("https://8.153.145.175/app-update/latest.apk", hosts))
        assertTrue(
            UpdatePolicy.isAllowedHttpsUrl(
                "https://github.com/nhyzcms/app/releases/latest/download/app-release.apk",
                hosts,
            ),
        )
        assertTrue(UpdatePolicy.isAllowedHttpsUrl("https://objects.githubusercontent.com/x/y.apk", hosts))
        assertTrue(UpdatePolicy.isAllowedHttpsUrl("HTTPS://8.153.145.175/x.apk", hosts))
    }

    @Test
    fun rejectsNonHttpsForeignHostsAndUserInfo() {
        assertFalse(UpdatePolicy.isAllowedHttpsUrl(null, hosts))
        assertFalse(UpdatePolicy.isAllowedHttpsUrl("", hosts))
        assertFalse(UpdatePolicy.isAllowedHttpsUrl("http://8.153.145.175/x.apk", hosts))
        assertFalse(UpdatePolicy.isAllowedHttpsUrl("https://evil.example/x.apk", hosts))
        assertFalse(UpdatePolicy.isAllowedHttpsUrl("https://8.153.145.175.evil.example/x.apk", hosts))
        assertFalse(UpdatePolicy.isAllowedHttpsUrl("https://user:pw@8.153.145.175/x.apk", hosts))
        assertFalse(UpdatePolicy.isAllowedHttpsUrl("ftp://8.153.145.175/x.apk", hosts))
        assertFalse(UpdatePolicy.isAllowedHttpsUrl("javascript:alert(1)", hosts))
    }

    @Test
    fun noticeUrlMustBeInAppRoute() {
        assertEquals("/#/news/1", UpdatePolicy.sanitizeNoticeUrl("/#/news/1"))
        assertEquals(null, UpdatePolicy.sanitizeNoticeUrl("https://evil.example"))
        assertEquals(null, UpdatePolicy.sanitizeNoticeUrl("/app"))
        assertEquals(null, UpdatePolicy.sanitizeNoticeUrl(null))
    }

    @Test
    fun downloadUrlsPreferPrimaryThenSourcesDedup() {
        val m = manifest(
            sources = listOf(
                "https://github.com/nhyzcms/app/releases/latest/download/app-release.apk",
                "https://evil.example/app-release.apk",
                "https://8.153.145.175/app-update/latest.apk", // 与主源重复
                "http://8.153.145.175/plain.apk",
            ),
        )
        val urls = UpdatePolicy.downloadUrls(m, hosts)
        assertEquals(2, urls.size)
        assertEquals("https://8.153.145.175/app-update/latest.apk", urls[0])
        assertEquals("https://github.com/nhyzcms/app/releases/latest/download/app-release.apk", urls[1])
    }

    @Test
    fun downloadUrlsSkipsIllegalPrimary() {
        val m = manifest(
            apkUrl = "https://evil.example/app.apk",
            sources = listOf("https://github.com/nhyzcms/app/releases/latest/download/app-release.apk"),
        )
        val urls = UpdatePolicy.downloadUrls(m, hosts)
        assertEquals(1, urls.size)
        assertEquals("https://github.com/nhyzcms/app/releases/latest/download/app-release.apk", urls[0])
    }

    // ── 决策 ────────────────────────────────────────────────────────────────

    @Test
    fun decisionsWhenUpToDate() {
        val decision = UpdatePolicy.decide(manifest(latestVersionCode = 1), 1, hosts)
        assertEquals(UpdatePolicy.Decision.None, decision)
    }

    @Test
    fun optionalUpdateIsReportedWithUrls() {
        val decision = UpdatePolicy.decide(manifest(latestVersionCode = 2), 1, hosts)
        assertTrue(decision is UpdatePolicy.Decision.Update)
        val update = decision as UpdatePolicy.Decision.Update
        assertFalse(update.mandatory)
        assertEquals(1, update.urls.size)
        assertEquals(2, update.release.versionCode)
    }

    @Test
    fun mandatoryFlagMakesItNonSkippable() {
        val decision = UpdatePolicy.decide(manifest(latestVersionCode = 2, mandatory = true), 1, hosts)
        assertTrue(decision is UpdatePolicy.Decision.Update)
        assertTrue((decision as UpdatePolicy.Decision.Update).mandatory)
    }

    @Test
    fun minShellVersionForcesUpdate() {
        val decision = UpdatePolicy.decide(manifest(latestVersionCode = 2, minShellVersionCode = 5), 1, hosts)
        assertTrue(decision is UpdatePolicy.Decision.Update)
        assertTrue((decision as UpdatePolicy.Decision.Update).mandatory)
    }

    @Test
    fun blockedVersionWinsOverEverything() {
        val m = manifest(
            latestVersionCode = 2,
            blocked = listOf(
                BlockedVersion(versionCode = 1, action = "block", message = "请更新"),
                BlockedVersion(versionCode = 2, action = "block", message = "别的版本"),
            ),
        )
        val decision = UpdatePolicy.decide(m, 1, hosts)
        assertTrue(decision is UpdatePolicy.Decision.Blocked)
        val blocked = decision as UpdatePolicy.Decision.Blocked
        assertEquals(UpdatePolicy.REASON_VERSION_BLOCKED, blocked.reason)
        assertEquals("请更新", blocked.message)
    }

    @Test
    fun warnActionDoesNotBlock() {
        val m = manifest(
            latestVersionCode = 1,
            blocked = listOf(BlockedVersion(versionCode = 1, action = "warn", message = "旧版")),
        )
        assertEquals(UpdatePolicy.Decision.None, UpdatePolicy.decide(m, 1, hosts))
    }

    @Test
    fun forcedButNoReleaseMeansBlocked() {
        val m = manifest(latestVersionCode = 1, minShellVersionCode = 9)
        val decision = UpdatePolicy.decide(m, 1, hosts)
        assertTrue(decision is UpdatePolicy.Decision.Blocked)
        assertEquals(UpdatePolicy.REASON_SHELL_TOO_OLD, (decision as UpdatePolicy.Decision.Blocked).reason)
    }

    @Test
    fun illegalSha256IsIgnoredWhenOptional() {
        val m = manifest(latestVersionCode = 2, apkSha256 = "z".repeat(64))
        assertEquals(UpdatePolicy.Decision.None, UpdatePolicy.decide(m, 1, hosts))
    }

    @Test
    fun illegalSha256BlocksWhenMandatory() {
        val m = manifest(latestVersionCode = 2, mandatory = true, apkSha256 = "short")
        val decision = UpdatePolicy.decide(m, 1, hosts)
        assertTrue(decision is UpdatePolicy.Decision.Blocked)
        assertEquals(
            UpdatePolicy.REASON_NO_DOWNLOAD_SOURCE,
            (decision as UpdatePolicy.Decision.Blocked).reason,
        )
    }

    @Test
    fun foreignApkUrlIsNotUsable() {
        val m = manifest(latestVersionCode = 2, apkUrl = "https://evil.example/app.apk")
        assertEquals(UpdatePolicy.Decision.None, UpdatePolicy.decide(m, 1, hosts))
    }

    @Test
    fun versionCompare() {
        assertEquals(1, UpdatePolicy.compareVersions(1, 2))
        assertEquals(-1, UpdatePolicy.compareVersions(3, 2))
        assertEquals(0, UpdatePolicy.compareVersions(2, 2))
    }

    // ── 开关与公告 ──────────────────────────────────────────────────────────

    @Test
    fun flagsMergeWithBuiltInDefaults() {
        val merged = UpdatePolicy.mergedFlags(manifest(flags = mapOf("live2d" to true)))
        assertEquals(true, merged["live2d"])
        assertEquals(true, merged["panorama"])
        assertEquals(true, merged["autoUpdateCheck"])
        assertEquals(false, merged["forceRefreshEntry"])
    }

    @Test
    fun flagFallsBackToDefault() {
        assertTrue(UpdatePolicy.flag(emptyMap(), "autoUpdateCheck", true))
        assertFalse(UpdatePolicy.flag(mapOf("autoUpdateCheck" to false), "autoUpdateCheck", true))
        assertTrue(UpdatePolicy.flag(mapOf("autoUpdateCheck" to false), "other", true))
    }

    @Test
    fun noticeShowsOnlyOnce() {
        val notice = Notice(id = "n1", text = "t", level = "info", url = null, dismissible = true)
        assertTrue(UpdatePolicy.shouldShowNotice(notice, null))
        assertTrue(UpdatePolicy.shouldShowNotice(notice, "other"))
        assertFalse(UpdatePolicy.shouldShowNotice(notice, "n1"))
        assertFalse(UpdatePolicy.shouldShowNotice(null, null))
    }
}
