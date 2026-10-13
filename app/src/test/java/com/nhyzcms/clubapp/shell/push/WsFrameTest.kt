package com.nhyzcms.clubapp.shell.push

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 帧编解码是"真机上才会暴露"的部分里最该被测的一段：掩码、长度、分片、超限。 */
class WsFrameTest {

    private val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44)

    /** 服务端方向的帧（无掩码）。 */
    private fun serverFrame(opcode: Int, payload: ByteArray, fin: Boolean = true): ByteArray {
        val out = ByteArray(2 + payload.size)
        out[0] = ((if (fin) 0x80 else 0x00) or opcode).toByte()
        out[1] = payload.size.toByte()
        System.arraycopy(payload, 0, out, 2, payload.size)
        return out
    }

    @Test
    fun encodeSetsFinAndMaskBits() {
        val bytes = WsFrame.encode(WsFrame.OP_TEXT, "hi".toByteArray(), mask = mask)
        assertEquals(0x81, bytes[0].toInt() and 0xFF)
        assertEquals(0x82, bytes[1].toInt() and 0xFF)
        assertEquals(2 + 4 + 2, bytes.size)
    }

    @Test
    fun encodeMasksPayload() {
        val payload = "hello".toByteArray()
        val bytes = WsFrame.encode(WsFrame.OP_TEXT, payload, mask = mask)
        for (i in payload.indices) {
            val expected = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            assertEquals(expected, bytes[6 + i])
        }
    }

    @Test
    fun encodeCarriesFinFalse() {
        val bytes = WsFrame.encode(WsFrame.OP_TEXT, "part".toByteArray(), fin = false, mask = mask)
        assertEquals(0x01, bytes[0].toInt() and 0xFF)
    }

    @Test
    fun encodeRejectsOversizedPayload() {
        val big = ByteArray(WsFrame.MAX_PAYLOAD_BYTES + 1)
        try {
            WsFrame.encode(WsFrame.OP_TEXT, big, mask = mask)
            throw AssertionError("应当拒绝超长载荷")
        } catch (expected: WsFrame.WsProtocolException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }

    @Test
    fun decodeRoundTripShortFrame() {
        val bytes = WsFrame.encode(WsFrame.OP_TEXT, "hi".toByteArray(), mask = mask)
        val decoded = WsFrame.decode(bytes, bytes.size)!!
        assertEquals(WsFrame.OP_TEXT, decoded.frame.opcode)
        assertTrue(decoded.frame.fin)
        assertArrayEquals("hi".toByteArray(), decoded.frame.payload)
        assertEquals(bytes.size, decoded.consumed)
    }

    @Test
    fun decodeServerFrameWithoutMask() {
        val bytes = serverFrame(WsFrame.OP_TEXT, "pong".toByteArray())
        val decoded = WsFrame.decode(bytes, bytes.size)!!
        assertArrayEquals("pong".toByteArray(), decoded.frame.payload)
    }

    @Test
    fun decodeEmptyPingIsValid() {
        val decoded = WsFrame.decode(serverFrame(WsFrame.OP_PING, byteArrayOf()), 2)!!
        assertEquals(WsFrame.OP_PING, decoded.frame.opcode)
        assertEquals(0, decoded.frame.payload.size)
    }

    @Test
    fun decodeReturnsNullWhenIncomplete() {
        val bytes = WsFrame.encode(WsFrame.OP_TEXT, "hello".toByteArray(), mask = mask)
        assertNull(WsFrame.decode(bytes, bytes.size - 1))
        assertNull(WsFrame.decode(ByteArray(1), 1))
        assertNull(WsFrame.decode(ByteArray(0), 0))
    }

    @Test
    fun decodeClampsLengthBeyondBuffer() {
        // 传入比缓冲区还大的 length 不能越界；按缓冲区实际大小处理（防御式钳制）。
        val bytes = WsFrame.encode(WsFrame.OP_TEXT, "hello".toByteArray(), mask = mask)
        val decoded = WsFrame.decode(bytes, bytes.size + 500)!!
        assertEquals(bytes.size, decoded.consumed)
    }

    @Test
    fun decodeHandlesSixteenBitLength() {
        val payload = ByteArray(300) { (it % 127).toByte() }
        val encoded = WsFrame.encode(WsFrame.OP_BINARY, payload, mask = mask)
        assertEquals(0x80 or 126, encoded[1].toInt() and 0xFF)
        val decoded = WsFrame.decode(encoded, encoded.size)!!
        assertArrayEquals(payload, decoded.frame.payload)
        assertEquals(encoded.size, decoded.consumed)
    }

    @Test
    fun decodeHandlesSixtyFourBitLengthHeader() {
        val payload = ByteArray(100) { it.toByte() }
        val out = ByteArray(2 + 8 + payload.size)
        out[0] = (0x80 or WsFrame.OP_BINARY).toByte()
        out[1] = 127.toByte()
        out[9] = 100
        System.arraycopy(payload, 0, out, 10, payload.size)
        val decoded = WsFrame.decode(out, out.size)!!
        assertEquals(100, decoded.frame.payload.size)
        assertEquals(out.size, decoded.consumed)
    }

    @Test
    fun decodeRejectsOversizedFrame() {
        val header = ByteArray(10)
        header[0] = (0x80 or WsFrame.OP_BINARY).toByte()
        header[1] = 127.toByte()
        header[7] = 0x0F
        header[8] = 0x42
        header[9] = 0x40
        try {
            WsFrame.decode(header, header.size)
            throw AssertionError("应当拒绝超长帧")
        } catch (expected: WsFrame.WsProtocolException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }

    @Test
    fun decodeRejectsNegativeLength() {
        val header = ByteArray(10)
        header[0] = (0x80 or WsFrame.OP_BINARY).toByte()
        header[1] = 127.toByte()
        header[2] = 0x80.toByte()
        try {
            WsFrame.decode(header, header.size)
            throw AssertionError("应当拒绝最高位为 1 的长度")
        } catch (expected: WsFrame.WsProtocolException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }

    @Test
    fun decodeTwoFramesFromOneBuffer() {
        val first = WsFrame.encode(WsFrame.OP_TEXT, "a".toByteArray(), mask = mask)
        val second = WsFrame.encode(WsFrame.OP_TEXT, "bc".toByteArray(), mask = mask)
        val buf = first + second
        val d1 = WsFrame.decode(buf, buf.size)!!
        assertEquals(1, d1.frame.payload.size)
        val rest = buf.copyOfRange(d1.consumed, buf.size)
        val d2 = WsFrame.decode(rest, rest.size)!!
        assertEquals(2, d2.frame.payload.size)
    }

    @Test
    fun frameEqualityIsByContent() {
        val a = WsFrame.Frame(WsFrame.OP_TEXT, byteArrayOf(1, 2, 3), true)
        val b = WsFrame.Frame(WsFrame.OP_TEXT, byteArrayOf(1, 2, 3), true)
        val c = WsFrame.Frame(WsFrame.OP_TEXT, byteArrayOf(1, 2, 4), true)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
        assertTrue(a.toString().contains("opcode=1"))
    }
}
