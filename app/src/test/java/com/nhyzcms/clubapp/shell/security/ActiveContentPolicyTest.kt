package com.nhyzcms.clubapp.shell.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveContentPolicyTest {

    @Test
    fun mediaActiveContentIsBlocked() {
        assertEquals(ActiveContentPolicy.REASON, ActiveContentPolicy.blockReason("https://8.153.145.175/media/a.html"))
        assertEquals(ActiveContentPolicy.REASON, ActiveContentPolicy.blockReason("https://8.153.145.175/media/b.svg"))
        assertEquals(ActiveContentPolicy.REASON, ActiveContentPolicy.blockReason("https://8.153.145.175/media/c.HTML"))
        assertEquals(ActiveContentPolicy.REASON, ActiveContentPolicy.blockReason("https://8.153.145.175/media/d%2Ehtml"))
    }

    @Test
    fun mediaAssetsAreAllowed() {
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/media/a.jpg"))
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/media/a.png"))
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/media/report.pdf"))
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/media/noext"))
    }

    @Test
    fun nonMediaSameSitePagesAreUntouched() {
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/#/m/events"))
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/static/index.html"))
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/about/"))
    }

    @Test
    fun queryStringDoesNotFoolTheJudge() {
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/media/photo.jpg?name=x.html"))
        assertEquals(
            ActiveContentPolicy.REASON,
            ActiveContentPolicy.blockReason("https://8.153.145.175/media/page.html?x=1"),
        )
    }

    @Test
    fun malformedUrlIsNotJudgedHere() {
        assertNull(ActiveContentPolicy.blockReason(""))
        assertNull(ActiveContentPolicy.blockReason("https://"))
    }
}
