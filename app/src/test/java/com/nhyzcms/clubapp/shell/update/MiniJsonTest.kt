package com.nhyzcms.clubapp.shell.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MiniJson] 的单测：正常的 JSON 要能读，坏输入一律 null 而不是抛异常。 */
class MiniJsonTest {

    @Test
    fun parsesObjectArrayAndScalars() {
        val root = MiniJson.parse("""{"a":1,"b":[true,false,null],"c":"x"}""") as? Map<*, *>
            ?: error("应能解析顶层对象")
        assertEquals(1.0, root["a"] as Double, 0.0)
        assertEquals("x", root["c"])
        val list = root["b"] as? List<*> ?: error("b 应是数组")
        assertEquals(3, list.size)
        assertEquals(true, list[0])
        assertEquals(false, list[1])
        assertNull(list[2])
    }

    @Test
    fun parsesEscapesAndUnicodeEscapes() {
        val value = MiniJson.parse(""""line\n\"q\"\u0041\u4e2d""")
        assertEquals("line\n\"q\"A中", value)
    }

    @Test
    fun parsesNegativeAndExponentNumbers() {
        val root = MiniJson.parse("""{"n":-12,"f":1.5e3}""") as? Map<*, *> ?: error("应能解析")
        assertEquals(-12.0, root["n"] as Double, 0.0)
        assertEquals(1500.0, root["f"] as Double, 0.0)
    }

    @Test
    fun toleratesWhitespaceAndEmptyContainers() {
        val root = MiniJson.parse(" \n { \"a\" : { } , \"b\" : [ ] } \t") as? Map<*, *>
            ?: error("应能解析")
        assertTrue((root["a"] as Map<*, *>).isEmpty())
        assertTrue((root["b"] as List<*>).isEmpty())
    }

    @Test
    fun rejectsBrokenJson() {
        assertNull(MiniJson.parse(null))
        assertNull(MiniJson.parse(""))
        assertNull(MiniJson.parse("{"))
        assertNull(MiniJson.parse("""{"a":}"""))
        assertNull(MiniJson.parse("""{"a":1,}"""))
        assertNull(MiniJson.parse("""{"a" 1}"""))
        assertNull(MiniJson.parse("""[1,2"""))
        assertNull(MiniJson.parse("""{} trailing"""))
        assertNull(MiniJson.parse("""{"a":"未闭合}"""))
    }

    @Test
    fun rejectsTooDeepNesting() {
        val deep = "[".repeat(MiniJson.MAX_DEPTH + 5) + "]".repeat(MiniJson.MAX_DEPTH + 5)
        assertNull(MiniJson.parse(deep))
    }

    @Test
    fun rejectsOverlongInput() {
        val long = "x".repeat(MiniJson.MAX_INPUT_CHARS + 1)
        assertTrue(long.length > MiniJson.MAX_INPUT_CHARS)
        assertNull(MiniJson.parse(long))
    }

    @Test
    fun nonObjectTopLevelIsStillParsed() {
        // 解析层不管顶层是什么类型；"顶层必须是对象"由 UpdateManifestCodec 判定。
        assertEquals("x", MiniJson.parse("\"x\""))
        assertEquals(1.0, MiniJson.parse("1") as Double, 0.0)
    }
}
