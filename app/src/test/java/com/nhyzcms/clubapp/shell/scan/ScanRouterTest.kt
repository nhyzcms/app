package com.nhyzcms.clubapp.shell.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 扫码落点判定（纯逻辑）。
 *
 * 覆盖工牌上真实存在的两种码 + 几种「扫到不是本站东西」的情形：
 * 这里判错一次，用户就会在 WebView 里打开别人的网站，或者白白多扣一次查询额度。
 */
class ScanRouterTest {

    private val origin = "https://8.153.145.175"
    private val host = "8.153.145.175"

    private fun url(raw: String): String? = ScanRouter.targetUrl(origin, host, raw)

    @Test
    fun `12 位条码载荷落到编号查询页并带上 q`() {
        assertEquals("$origin/#/v?q=260901667878", url("260901667878"))
    }

    @Test
    fun `旧裸 8 位识别码条码同样走编号查询页`() {
        assertEquals("$origin/#/v?q=26090188", url("26090188"))
    }

    @Test
    fun `扫码枪带进来的空格与短横线会被去掉`() {
        assertEquals("$origin/#/v?q=260901667878", url("  2609-0166-7878 \n"))
        assertEquals("$origin/#/v?q=26090188", url("2609 0188"))
    }

    @Test
    fun `工牌二维码指向站内校验页时原样放行`() {
        val qr = "$origin/#/v/26090188?s=0123456789abcdef"
        assertEquals(qr, url(qr))
    }

    @Test
    fun `二维码里的 http 会被改写成 https`() {
        assertEquals(
            "$origin/#/v/26090188?s=0123456789abcdef",
            url("http://8.153.145.175/#/v/26090188?s=0123456789abcdef"),
        )
    }

    @Test
    fun `别的主机的链接不接管`() {
        assertNull(url("https://evil.example/#/v/26090188?s=0123456789abcdef"))
    }

    @Test
    fun `长度不对或含字母的文本不认`() {
        assertNull(url(""))
        assertNull(url("   "))
        assertNull(url("2609018"))
        assertNull(url("26090166787"))
        assertNull(url("26090166ABCD"))
        assertNull(url("张三"))
    }

    @Test
    fun `超长文本直接拒绝（不往地址栏里塞垃圾）`() {
        assertNull(url("9".repeat(ScanRouter.MAX_TEXT_LEN + 1)))
    }
}
