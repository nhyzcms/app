package com.nhyzcms.clubapp.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NavPolicy] 的回归单测（安全红线：导航裁决是“壳只加载本站”的唯一闸门）。
 *
 * 新增这些用例，是为了让后面几块改壳时不会把这条红线改破。
 */
class NavPolicyTest {

    private fun decide(scheme: String, host: String, raw: String): NavDecision =
        NavPolicy.decide(UrlParts.of(scheme, host, raw))

    @Test
    fun 本站_https_在壳内打开() {
        assertTrue(decide("https", "8.153.145.175", "https://8.153.145.175/#/m") is NavDecision.InApp)
    }

    @Test
    fun 站外_https_交浏览器() {
        assertTrue(decide("https", "example.com", "https://example.com/x") is NavDecision.External)
    }

    @Test
    fun 本站明文一律拦下() {
        assertEquals(
            NavDecision.Blocked("http://8.153.145.175/x", NavPolicy.REASON_CLEARTEXT),
            decide("http", "8.153.145.175", "http://8.153.145.175/x"),
        )
    }

    @Test
    fun 站外明文交系统处理() {
        assertTrue(decide("http", "example.com", "http://example.com/x") is NavDecision.External)
    }

    @Test
    fun 系统_scheme_交系统() {
        assertTrue(decide("tel", "", "tel:10086") is NavDecision.External)
        assertTrue(decide("mailto", "", "mailto:a@163.com") is NavDecision.External)
    }

    @Test
    fun 未知_scheme_拦下() {
        val decision = decide("javascript", "", "javascript:alert(1)")
        assertTrue(decision is NavDecision.Blocked)
        assertEquals(
            NavPolicy.REASON_UNKNOWN_SCHEME + ":javascript",
            (decision as NavDecision.Blocked).reason,
        )
        assertTrue(decide("file", "", "file:///etc/hosts") is NavDecision.Blocked)
        assertTrue(decide("content", "", "content://media/1") is NavDecision.Blocked)
    }

    @Test
    fun 空地址视为畸形() {
        assertEquals(NavDecision.Blocked("", NavPolicy.REASON_MALFORMED), decide("", "", ""))
    }

    @Test
    fun 大小写不同的_scheme_归一化后再判() {
        assertTrue(decide("HTTPS", "8.153.145.175", "HTTPS://8.153.145.175/x") is NavDecision.InApp)
        assertTrue(decide("TEL", "", "TEL:10086") is NavDecision.External)
    }
}
