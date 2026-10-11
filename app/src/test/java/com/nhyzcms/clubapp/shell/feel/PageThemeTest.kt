package com.nhyzcms.clubapp.shell.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PageTheme] 单测：颜色解析、亮度判定、配色决策、探测结果解析。
 *
 * 这些是不依赖 Android 的纯逻辑，所以在本地/CI 都能跑（见 scripts/local-unit-test.sh）。
 */
class PageThemeTest {

    // ── 颜色解析 ────────────────────────────────────────────────────────────

    @Test
    fun parsesSixDigitHex() {
        assertEquals(0xFF080F17.toInt(), PageTheme.parseColor("#080f17"))
        assertEquals(0xFFFFFFFF.toInt(), PageTheme.parseColor("#FFFFFF"))
    }

    @Test
    fun parsesWithoutHashAndWithSurroundingSpaces() {
        assertEquals(0xFF080F17.toInt(), PageTheme.parseColor("080F17"))
        assertEquals(0xFF080F17.toInt(), PageTheme.parseColor("  #080F17  "))
    }

    @Test
    fun expandsThreeDigitHexAndAddsOpaqueAlpha() {
        assertEquals(0xFFFFFFFF.toInt(), PageTheme.parseColor("#fff"))
        assertEquals(0xFFAABBCC.toInt(), PageTheme.parseColor("#abc"))
    }

    @Test
    fun parsesEightDigitHexWithAlpha() {
        assertEquals(0x80080F17.toInt(), PageTheme.parseColor("#80080f17"))
    }

    @Test
    fun rejectsInvalidColors() {
        assertNull(PageTheme.parseColor(null))
        assertNull(PageTheme.parseColor(""))
        assertNull(PageTheme.parseColor("   "))
        assertNull(PageTheme.parseColor("#"))
        assertNull(PageTheme.parseColor("#12"))
        assertNull(PageTheme.parseColor("#12345"))
        assertNull(PageTheme.parseColor("#1234567"))
        assertNull(PageTheme.parseColor("#123456789"))
        assertNull(PageTheme.parseColor("#gggggg"))
        assertNull(PageTheme.parseColor("rgb(255,255,255)"))
        assertNull(PageTheme.parseColor("url(javascript:alert(1))"))
    }

    // ── 亮度与图标明暗 ──────────────────────────────────────────────────────

    @Test
    fun luminanceOfExtremes() {
        assertEquals(1.0, PageTheme.luminance(0xFFFFFFFF.toInt()), 0.0001)
        assertEquals(0.0, PageTheme.luminance(0xFF000000.toInt()), 0.0001)
    }

    @Test
    fun lightBackgroundDetection() {
        assertTrue(PageTheme.isLightBackground(0xFFFFFFFF.toInt())) // 站点浅色底
        assertFalse(PageTheme.isLightBackground(0xFF080F17.toInt())) // 站点深色底
        assertFalse(PageTheme.isLightBackground(PageTheme.parseColor("#0756A2")!!)) // 社徽钴蓝
    }

    @Test
    fun luminanceIsNotNaiveAverage() {
        // 深绿在「算术平均」眼里比中灰亮，但人眼不是：用 WCAG 公式时它必须更暗。
        val darkGreen = PageTheme.parseColor("#003300")!!
        val midGray = PageTheme.parseColor("#808080")!!
        assertTrue(PageTheme.luminance(darkGreen) < PageTheme.luminance(midGray))
    }

    // ── 配色决策 ────────────────────────────────────────────────────────────

    @Test
    fun usesWebColorWhenAvailable() {
        val light = PageTheme.decide(0xFFFFFFFF.toInt(), systemDark = true)
        assertEquals(0xFFFFFFFF.toInt(), light.statusBarColor)
        assertEquals(0xFFFFFFFF.toInt(), light.windowBackgroundColor)
        assertTrue(light.lightStatusBarIcons)

        val dark = PageTheme.decide(0xFF080F17.toInt(), systemDark = false)
        assertEquals(0xFF080F17.toInt(), dark.statusBarColor)
        assertFalse(dark.lightStatusBarIcons)
    }

    @Test
    fun fallsBackToSystemDarkness() {
        val dark = PageTheme.decide(null, systemDark = true)
        assertEquals(PageTheme.FALLBACK_DARK_COLOR, dark.statusBarColor)
        assertFalse(dark.lightStatusBarIcons)

        val light = PageTheme.decide(null, systemDark = false)
        assertEquals(PageTheme.FALLBACK_LIGHT_COLOR, light.statusBarColor)
        assertTrue(light.lightStatusBarIcons)
    }

    // ── 探测结果解析（evaluateJavascript 的双层 JSON）───────────────────────

    @Test
    fun parsesProbeResultFromEvaluateJavascript() {
        // evaluateJavascript 的返回形如 "[\"#080f17\",120]"（外层是 JSON 字符串）
        val raw = "\"[\\\"#080f17\\\",120]\""
        val probe = PageTheme.parseProbeResult(raw)
        assertEquals(0xFF080F17.toInt(), probe.color)
        assertEquals(120, probe.scrollY)
    }

    @Test
    fun parsesProbeResultWithEmptyThemeColor() {
        val probe = PageTheme.parseProbeResult("\"[\\\"\\\",0]\"")
        assertNull(probe.color)
        assertEquals(0, probe.scrollY)
    }

    @Test
    fun rejectsMalformedProbeResults() {
        assertEquals(PageTheme.Probe(null, 0), PageTheme.parseProbeResult(null))
        assertEquals(PageTheme.Probe(null, 0), PageTheme.parseProbeResult(""))
        assertEquals(PageTheme.Probe(null, 0), PageTheme.parseProbeResult("null"))
        assertEquals(PageTheme.Probe(null, 0), PageTheme.parseProbeResult("[]"))
        assertEquals(PageTheme.Probe(null, 0), PageTheme.parseProbeResult("\"[\\\"#zzz\\\"]\""))
    }

    @Test
    fun clampsNegativeAndHugeScrollValues() {
        assertEquals(0, PageTheme.parseProbeResult("\"[\\\"\\\",-30]\"").scrollY)
        assertEquals(0, PageTheme.parseProbeResult("\"[\\\"\\\",1e20]\"").scrollY)
        assertEquals(4096, PageTheme.parseProbeResult("\"[\\\"\\\",4096.7]\"").scrollY)
    }
}
