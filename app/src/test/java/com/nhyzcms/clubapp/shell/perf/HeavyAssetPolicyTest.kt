package com.nhyzcms.clubapp.shell.perf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大资源拦截的判定（区块 6：Live2D 素材兜底拦截）。
 *
 * 这是**安全相关**的判定，所以用例刻意覆盖三类"看起来像但其实不能拦"的输入：
 *   ① 主文档（拦了就是白屏）；
 *   ② 站外地址（拿的是别人家的资源，拦不拦都不是我们的事，但绝不能因为前缀像就拦）；
 *   ③ 前缀陷阱（`8.153.145.175.evil.example` 这种"我们的域名只是别人的子域"）。
 */
class HeavyAssetPolicyTest {

    private val origin = "https://8.153.145.175"
    private val prefixes = listOf("/static/live2d/")

    private fun block(url: String?, forMainFrame: Boolean = false, enabled: Boolean = true): Boolean =
        HeavyAssetPolicy.shouldBlock(
            url = url,
            forMainFrame = forMainFrame,
            siteOrigin = origin,
            prefixes = prefixes,
            enabled = enabled,
        )

    @Test
    fun `站点 live2d 素材会被拦`() {
        assertTrue(block("$origin/static/live2d/catalog.json"))
        assertTrue(block("$origin/static/live2d/widget/waifu-tips.js"))
        assertTrue(block("$origin/static/live2d/models/kumo/%E4%BA%91.model3.json"))
        assertTrue(block("$origin/static/live2d/models/cat/x.4096/texture_00.png"))
    }

    @Test
    fun `带查询串或锚点也算命中`() {
        assertTrue(block("$origin/static/live2d/catalog.json?v=2"))
        assertTrue(block("$origin/static/live2d/catalog.json#frag"))
    }

    @Test
    fun `主文档永远不拦`() {
        assertFalse(block("$origin/static/live2d/", forMainFrame = true))
        assertFalse(block("$origin/", forMainFrame = true))
    }

    @Test
    fun `开关关掉就全部放行`() {
        assertFalse(block("$origin/static/live2d/catalog.json", enabled = false))
    }

    @Test
    fun `站外地址不拦`() {
        assertFalse(block("https://evil.example/static/live2d/catalog.json"))
        assertFalse(block("https://example.com/static/live2d/catalog.json"))
    }

    @Test
    fun `前缀陷阱：我们的域名是他人的子域时不拦`() {
        assertFalse(block("https://8.153.145.175.evil.example/static/live2d/catalog.json"))
    }

    @Test
    fun `同源但不在白名单前缀里的资源不拦`() {
        assertFalse(block("$origin/static/main.abcdef.js"))
        assertFalse(block("$origin/static/admin/js/core.js"))
        assertFalse(block("$origin/messaging/conversations/unread_count/"))
        assertFalse(block("$origin/media/news_covers/x.jpg"))
        assertFalse("前缀必须完整匹配，`/static/live2dX` 不算", block("$origin/static/live2dX/catalog.json"))
    }

    @Test
    fun `空值与畸形输入一律放行`() {
        assertFalse(block(null))
        assertFalse(block(""))
        assertFalse(block("   "))
        assertFalse(block("not a url"))
        assertFalse(block("/static/live2d/catalog.json")) // 相对地址：壳只认绝对 URL
    }

    @Test
    fun `siteOrigin 带尾斜杠或空格也仍然正确`() {
        assertTrue(
            HeavyAssetPolicy.shouldBlock(
                url = "$origin/static/live2d/catalog.json",
                forMainFrame = false,
                siteOrigin = "$origin/ ",
                prefixes = prefixes,
                enabled = true,
            ),
        )
    }

    @Test
    fun `空白前缀不会命中一切`() {
        assertFalse(
            HeavyAssetPolicy.shouldBlock(
                url = "$origin/api/whatever",
                forMainFrame = false,
                siteOrigin = origin,
                prefixes = listOf(""),
                enabled = true,
            ),
        )
    }
}
