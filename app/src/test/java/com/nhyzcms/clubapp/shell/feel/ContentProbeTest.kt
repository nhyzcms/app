package com.nhyzcms.clubapp.shell.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContentProbe] 是纯逻辑，在此钉死“没画出来”的判定与诊断文本。
 *
 * 事故背景：骨架屏只在 `onPageFinished` 揭开，而该回调要等网页 `load` 事件（含大图），
 * 迟迟不来 → 一直盖着网页 → 黑屏。这里的判定就是“揭开骨架”的依据，判错任何一侧都会出问题：
 *   · 判太松（把“没画出来”当成“画好了”）→ 骨架提前揭开，用户看到白屏闪烁；
 *   · 判太严（把“画好了”当成“没画出来”）→ 骨架一直盖着，用户看到黑屏（就是本次事故）。
 */
class ContentProbeTest {

    /** `evaluateJavascript` 的回调值是“被 JSON 化了一层”的字符串，这里模拟它。 */
    private fun rawJs(arrayJson: String): String = "\"" + arrayJson + "\""

    @Test
    fun `解析两层 JSON 得到快照`() {
        val snapshot = ContentProbe.parse(rawJs("[12,54321,4,1]"))
        assertEquals(12, snapshot?.rootChildren)
        assertEquals(54321, snapshot?.bodyChars)
        assertEquals(4, snapshot?.scriptTags)
        assertEquals(true, snapshot?.hasBundleGlobal)
    }

    @Test
    fun `解析不了时返回 null 而不是抛异常`() {
        assertNull(ContentProbe.parse(null))
        assertNull(ContentProbe.parse("null"))
        assertNull(ContentProbe.parse("\"not-json\""))
        assertNull(ContentProbe.parse(rawJs("{\"root\":1}")))
    }

    @Test
    fun `React 挂载点为空且 body 很短 视为没画出来`() {
        // 站点初始 HTML 就是 <div id="root"></div>：body 长度约 26，#root 无子节点
        assertTrue(ContentProbe.looksUnrendered(ContentProbe.Snapshot(0, 26, 4, false)))
    }

    @Test
    fun `React 已挂载 视为已画出内容`() {
        assertFalse(ContentProbe.looksUnrendered(ContentProbe.Snapshot(1, 8000, 4, true)))
        assertFalse(ContentProbe.looksUnrendered(ContentProbe.Snapshot(35, 120000, 5, true)))
    }

    @Test
    fun `取不到 body 长度时不下结论`() {
        // -1 = 探测结果不可信（页面可能正在导航）；此时绝不能判成“没画出来”
        assertFalse(ContentProbe.looksUnrendered(ContentProbe.Snapshot(-1, -1, 0, false)))
    }

    @Test
    fun `诊断文本带上定位所需的四项`() {
        val text = ContentProbe.diagnose(
            snapshot = ContentProbe.Snapshot(0, 26, 4, false),
            url = "https://8.153.145.175/#/m",
            javaScriptEnabled = true,
            webViewVersion = "114.0.5735.196",
        )
        assertTrue(text.contains("https://8.153.145.175/#/m"))
        assertTrue(text.contains("已开启"))
        assertTrue(text.contains("114.0.5735.196"))
        assertTrue(text.contains("#root 子节点 0"))
        assertTrue(text.contains("站点打包全局 无"))
    }

    @Test
    fun `拿不到探测结果时诊断文本也能读`() {
        val text = ContentProbe.diagnose(
            snapshot = null,
            url = "",
            javaScriptEnabled = false,
            webViewVersion = "",
        )
        assertTrue(text.contains("(空)"))
        assertTrue(text.contains("已关闭"))
        assertTrue(text.contains("内容探测：拿不到结果"))
    }
}
