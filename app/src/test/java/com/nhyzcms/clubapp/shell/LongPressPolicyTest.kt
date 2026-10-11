package com.nhyzcms.clubapp.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LongPressPolicy] 的单测：命中类型映射 + scheme 白名单。 */
class LongPressPolicyTest {

    @Test
    fun 图片命中归为图片() {
        assertEquals(
            LongPressPolicy.Target.Image("https://8.153.145.175/media/a.jpg"),
            LongPressPolicy.classify(LongPressPolicy.TYPE_IMAGE, "https://8.153.145.175/media/a.jpg"),
        )
        assertTrue(
            LongPressPolicy.classify(LongPressPolicy.TYPE_IMAGE_ANCHOR, "https://x/a.png") is LongPressPolicy.Target.Image,
        )
        assertTrue(
            LongPressPolicy.classify(LongPressPolicy.TYPE_SRC_IMAGE_ANCHOR, "https://x/a.png") is LongPressPolicy.Target.Image,
        )
    }

    @Test
    fun 链接命中归为链接() {
        assertEquals(
            LongPressPolicy.Target.Link("https://8.153.145.175/news/1"),
            LongPressPolicy.classify(LongPressPolicy.TYPE_ANCHOR, "https://8.153.145.175/news/1"),
        )
        assertTrue(
            LongPressPolicy.classify(LongPressPolicy.TYPE_SRC_ANCHOR, "http://x/y") is LongPressPolicy.Target.Link,
        )
    }

    @Test
    fun 非_http_一律不接管() {
        assertEquals(LongPressPolicy.Target.None, LongPressPolicy.classify(LongPressPolicy.TYPE_IMAGE, "javascript:alert(1)"))
        assertEquals(LongPressPolicy.Target.None, LongPressPolicy.classify(LongPressPolicy.TYPE_IMAGE, "blob:https://x/y"))
        assertEquals(LongPressPolicy.Target.None, LongPressPolicy.classify(LongPressPolicy.TYPE_IMAGE, "data:image/png;base64,AA"))
        assertEquals(LongPressPolicy.Target.None, LongPressPolicy.classify(LongPressPolicy.TYPE_IMAGE, null))
        assertEquals(LongPressPolicy.Target.None, LongPressPolicy.classify(LongPressPolicy.TYPE_IMAGE, "  "))
    }

    @Test
    fun 其它命中类型交回系统() {
        assertEquals(LongPressPolicy.Target.None, LongPressPolicy.classify(LongPressPolicy.TYPE_UNKNOWN, "https://x/y"))
        // 2=电话 3=地图 4=邮箱 9=输入框：都不该被我们吃掉
        for (type in listOf(2, 3, 4, 9)) {
            assertEquals(LongPressPolicy.Target.None, LongPressPolicy.classify(type, "https://x/y"))
        }
    }

    @Test
    fun 大小写不敏感地识别_http() {
        assertTrue(LongPressPolicy.isHttpUrl("HTTPS://8.153.145.175/x"))
        assertTrue(LongPressPolicy.isHttpUrl("http://x"))
        assertFalse(LongPressPolicy.isHttpUrl("httpx://x"))
    }
}
