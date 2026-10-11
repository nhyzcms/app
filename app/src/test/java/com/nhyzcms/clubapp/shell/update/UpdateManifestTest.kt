package com.nhyzcms.clubapp.shell.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [UpdateManifestCodec] 的单测：字段容错与默认值。 */
class UpdateManifestTest {

    private val sha = "a".repeat(64)

    private val sample = """
        {
          "schema": 1,
          "generatedAt": "2026-10-11T07:00:00+08:00",
          "startRoute": "/#/app",
          "flags": { "live2d": false, "panorama": true, "autoUpdateCheck": true },
          "notice": {
            "id": "n1", "text": "开放日", "level": "info",
            "url": "/#/news/128", "dismissible": true
          },
          "minShellVersionCode": 1,
          "latest": {
            "versionCode": 2,
            "versionName": "0.2.0",
            "notes": "新增上传",
            "mandatory": false,
            "apk": {
              "url": "https://8.153.145.175/app-update/latest.apk",
              "sha256": "$sha",
              "size": 3500000
            }
          },
          "blockedVersions": [ { "versionCode": 1, "action": "warn", "message": "旧版" } ],
          "updateSources": [
            "https://8.153.145.175/app-update/latest.apk",
            "https://github.com/nhyzcms/app/releases/latest/download/app-release.apk"
          ]
        }
    """.trimIndent()

    @Test
    fun parsesFullManifest() {
        val manifest = UpdateManifestCodec.parse(sample) ?: error("应能解析完整清单")
        assertEquals(1, manifest.schema)
        assertEquals("/#/app", manifest.startRoute)
        assertEquals(1, manifest.minShellVersionCode)
        assertEquals(3, manifest.flags.size)
        assertEquals(false, manifest.flags["live2d"])
        assertEquals("/#/news/128", manifest.notice?.url)
        assertEquals("n1", manifest.notice?.id)
        assertEquals(2, manifest.latest?.versionCode)
        assertEquals("0.2.0", manifest.latest?.versionName)
        assertEquals("新增上传", manifest.latest?.notes)
        assertEquals(false, manifest.latest?.mandatory)
        assertEquals(3_500_000L, manifest.latest?.apk?.size)
        assertEquals(sha, manifest.latest?.apk?.sha256)
        assertEquals(2, manifest.updateSources.size)
        assertEquals(1, manifest.blockedVersions.size)
        assertEquals("warn", manifest.blockedVersions[0].action)
    }

    @Test
    fun missingOptionalFieldsFallBackToDefaults() {
        val manifest = UpdateManifestCodec.parse("""{"schema":1}""") ?: error("应能解析")
        assertNull(manifest.startRoute)
        assertNull(manifest.notice)
        assertNull(manifest.latest)
        assertTrue(manifest.flags.isEmpty())
        assertTrue(manifest.blockedVersions.isEmpty())
        assertTrue(manifest.updateSources.isEmpty())
        assertEquals(0, manifest.minShellVersionCode)
    }

    @Test
    fun schemaIsAssumedWhenMissing() {
        val manifest = UpdateManifestCodec.parse("{}") ?: error("应能解析")
        assertEquals(UpdateManifest.SUPPORTED_SCHEMA, manifest.schema)
        assertTrue(UpdateManifestCodec.isSchemaSupported(manifest.schema))
    }

    @Test
    fun wrongTypesAreDroppedInsteadOfCrashing() {
        val manifest = UpdateManifestCodec.parse(
            """
            {
              "startRoute": 5,
              "minShellVersionCode": "1",
              "latest": { "versionCode": "2" },
              "flags": { "live2d": true, "bad": "yes", "n": 1 },
              "notice": { "id": "n1" },
              "blockedVersions": [ { "action": "block" }, { "versionCode": 3 } ],
              "updateSources": [ 1, "https://8.153.145.175/x" ]
            }
            """.trimIndent(),
        ) ?: error("应能解析")
        assertNull(manifest.startRoute)
        assertEquals(0, manifest.minShellVersionCode)
        assertNull(manifest.latest)
        assertEquals(1, manifest.flags.size)
        assertEquals(true, manifest.flags["live2d"])
        assertNull(manifest.notice) // 缺 text → 整条公告丢弃
        assertEquals(1, manifest.blockedVersions.size) // 缺 versionCode 的条目被丢弃
        assertEquals(1, manifest.updateSources.size)
    }

    @Test
    fun nonPositiveVersionCodeIsRejected() {
        val manifest = UpdateManifestCodec.parse("""{"latest":{"versionCode":0}}""") ?: error("应能解析")
        assertNull(manifest.latest)
    }

    @Test
    fun sha256IsNormalizedToLowerCase() {
        val upper = "A".repeat(64)
        val manifest = UpdateManifestCodec.parse(
            """{"latest":{"versionCode":2,"apk":{"url":"https://8.153.145.175/a.apk","sha256":"$upper"}}}""",
        ) ?: error("应能解析")
        assertEquals("a".repeat(64), manifest.latest?.apk?.sha256)
    }

    @Test
    fun topLevelMustBeObject() {
        assertNull(UpdateManifestCodec.parse("[]"))
        assertNull(UpdateManifestCodec.parse("\"text\""))
        assertNull(UpdateManifestCodec.parse("not json at all"))
        assertNull(UpdateManifestCodec.parse(null))
    }

    @Test
    fun noticeDismissibleDefaultsToTrue() {
        val manifest = UpdateManifestCodec.parse(
            """{"notice":{"id":"n","text":"t"}}""",
        ) ?: error("应能解析")
        assertNotNull(manifest.notice)
        assertEquals(true, manifest.notice?.dismissible)
        assertEquals("info", manifest.notice?.level)
    }

    @Test
    fun higherSchemaIsStillParsed() {
        // 契约（docs/APP-MANIFEST.md §6）：旧壳遇到更高 schema 只忽略不认识的字段，不能整体拒绝。
        val manifest = UpdateManifestCodec.parse("""{"schema":99,"minShellVersionCode":3}""")
        assertNotNull(manifest)
        assertEquals(99, manifest?.schema)
        assertEquals(3, manifest?.minShellVersionCode)
        assertFalse(manifest?.schema == 0)
    }
}
