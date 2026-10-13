package com.nhyzcms.clubapp.shell.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 握手是"连不上时最难查"的一段：RFC 里给了标准测试向量，正好拿来钉死实现。
 */
class WsHandshakeTest {

    /** RFC 6455 第 1.3 节的标准示例 key。 */
    private val key = "dGhlIHNhbXBsZSBub25jZQ=="

    @Test
    fun acceptMatchesRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsHandshake.acceptFor(key))
    }

    @Test
    fun randomKeyIsSixteenBytesInBase64() {
        val first = WsHandshake.randomKey()
        val second = WsHandshake.randomKey()
        assertEquals(24, first.length)
        assertFalse(first == second)
    }

    @Test
    fun requestCarriesEveryRequiredHeader() {
        val text = WsHandshake.request(
            host = "8.153.145.175",
            path = "/ws/messaging/",
            origin = "https://8.153.145.175",
            cookie = "sessionid=abc",
            userAgent = "UA-marker",
            key = key,
        )
        assertTrue(text.startsWith("GET /ws/messaging/ HTTP/1.1\r\n"))
        assertTrue(text.contains("\r\nHost: 8.153.145.175\r\n"))
        assertTrue(text.contains("\r\nUpgrade: websocket\r\n"))
        assertTrue(text.contains("\r\nConnection: Upgrade\r\n"))
        assertTrue(text.contains("\r\nSec-WebSocket-Key: " + key + "\r\n"))
        assertTrue(text.contains("\r\nSec-WebSocket-Version: 13\r\n"))
        assertTrue(text.contains("\r\nOrigin: https://8.153.145.175\r\n"))
        assertTrue(text.contains("\r\nCookie: sessionid=abc\r\n"))
        assertTrue(text.contains("\r\nUser-Agent: UA-marker\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun requestOmitsCookieHeaderWhenAnonymous() {
        val text = WsHandshake.request("host", "/ws/", "origin", null, "ua", key)
        assertFalse(text.contains("Cookie:"))
        val blank = WsHandshake.request("host", "/ws/", "origin", "  ", "ua", key)
        assertFalse(blank.contains("Cookie:"))
    }

    private fun validResponse(): String =
        "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: " + WsHandshake.acceptFor(key) + "\r\n" +
            "\r\n"

    @Test
    fun verifyAcceptsValidResponse() {
        assertTrue(WsHandshake.verify(validResponse(), key).ok)
    }

    @Test
    fun verifyIsCaseInsensitive() {
        val text = "HTTP/1.1 101 Switching Protocols\r\n" +
            "UPGRADE: WebSocket\r\n" +
            "CONNECTION: keep-alive, Upgrade\r\n" +
            "SEC-WEBSOCKET-ACCEPT: " + WsHandshake.acceptFor(key) + "\r\n\r\n"
        assertTrue(WsHandshake.verify(text, key).ok)
    }

    @Test
    fun verifyRejectsForbiddenResponse() {
        val result = WsHandshake.verify("HTTP/1.1 403 Forbidden\r\nServer: nginx\r\n\r\n", key)
        assertFalse(result.ok)
        assertTrue(result.reason.isNotEmpty())
    }

    @Test
    fun verifyRejectsEmptyResponse() {
        assertFalse(WsHandshake.verify("", key).ok)
    }

    @Test
    fun verifyRejectsWrongAccept() {
        val text = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: WRONG\r\n\r\n"
        assertFalse(WsHandshake.verify(text, key).ok)
    }

    @Test
    fun verifyRejectsNonWebsocketUpgrade() {
        val text = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: h2c\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: " + WsHandshake.acceptFor(key) + "\r\n\r\n"
        assertFalse(WsHandshake.verify(text, key).ok)
    }

    @Test
    fun verifyRejectsMissingConnectionUpgrade() {
        val text = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Sec-WebSocket-Accept: " + WsHandshake.acceptFor(key) + "\r\n\r\n"
        assertFalse(WsHandshake.verify(text, key).ok)
    }
}
