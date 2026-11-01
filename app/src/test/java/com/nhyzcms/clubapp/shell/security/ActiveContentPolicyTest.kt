package com.nhyzcms.clubapp.shell.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveContentPolicyTest {

    @Test
    fun `media 下的 html 与 svg 顶层导航被拦`() {
        assertEquals(ActiveContentPolicy.REASON, ActiveContentPolicy.blockReason("https://8.153.145.175/media/a.html"))
        assertEquals(ActiveContentPolicy.REASON, ActiveContentPolicy.blockReason("https://8.153.145.175/media/b.svg"))
        assertEquals(ActiveContentPolicy.REASON, ActiveContentPolicy.blockReason("https://8.153.145.175/media/c.HTML"))
        assertEquals(ActiveContentPolicy.REASON, ActiveContentPolicy.blockReason("https://8.153.145.175/media/d%2Ehtml"))
    }

    @Test
    fun `media 下的图片与附件放行`() {
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/media/a.jpg"))
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/media/a.png"))
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/media/report.pdf"))
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/media/noext"))
    }

    @Test
    fun `media 之外的同源页面不受影响`() {
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/#/m/events"))
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/static/index.html"))
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/about/"))
    }

    @Test
    fun `query 里的假扩展名不影响判定`() {
        assertNull(ActiveContentPolicy.blockReason("https://8.153.145.175/media/photo.jpg?name=x.html"))
        assertEquals(
            ActiveContentPolicy.REASON,
            ActiveContentPolicy.blockReason("https://8.153.145.175/media/page.html?x=1"),
        )
    }

    @Test
    fun `畸形地址不在这里下结论`() {
        assertNull(ActiveContentPolicy.blockReason(""))
        assertNull(ActiveContentPolicy.blockReason("https://"))
    }
}
