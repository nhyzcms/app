package com.nhyzcms.clubapp.shell.push

import java.security.SecureRandom

/**
 * WebSocket 帧编解码（RFC 6455 的客户端子集；纯逻辑，可 JVM 单测）。
 *
 * 为什么自己写：壳的运行期依赖被刻意压到 4 个 androidx 库（ADR-A05），
 * 不引入 OkHttp / Java-WebSocket；而 `HttpURLConnection` 根本不支持 WebSocket。
 * 真正需要的并不多：客户端掩码 + 三种长度编码 + 心跳与关闭帧 + 分片重组。
 *
 * 角色约定（RFC 6455 第 5.3 节）：**客户端发出的帧必须置掩码位**，
 * 服务端发出的帧不带掩码。两边都按这个写，不做宽容兼容。
 *
 * 安全上限：单帧载荷不超过 [MAX_PAYLOAD_BYTES]；超长长度或最高位为 1 的长度
 * 一律抛 [WsProtocolException]，由读循环立即断连——防止被投毒的端点喂一个天文数字长度。
 */
object WsFrame {

    const val OP_CONTINUATION = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    /** 单帧载荷上限。服务端事件只有几百字节，64 KB 已非常宽松。 */
    const val MAX_PAYLOAD_BYTES = 64 * 1024

    private val random = SecureRandom()

    /** 一帧。ByteArray 不是值语义，所以手写 equals / hashCode 供单测断言。 */
    class Frame(val opcode: Int, val payload: ByteArray, val fin: Boolean) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return opcode == other.opcode && fin == other.fin && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = opcode
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + fin.hashCode()
            return result
        }

        override fun toString(): String =
            "Frame(opcode=" + opcode + ", fin=" + fin + ", bytes=" + payload.size + ")"
    }

    /** 解析结果：一帧 + 它消耗的字节数（缓冲区里可能还接着下一帧）。 */
    class Decoded(val frame: Frame, val consumed: Int)

    class WsProtocolException(message: String) : RuntimeException(message)

    fun randomMask(): ByteArray = ByteArray(4).also { random.nextBytes(it) }

    /** 编码一帧（客户端方向：掩码位恒为 1）。 */
    fun encode(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
        mask: ByteArray = randomMask(),
    ): ByteArray {
        if (payload.size > MAX_PAYLOAD_BYTES) throw WsProtocolException("payload too large")
        if (mask.size != 4) throw WsProtocolException("mask must be 4 bytes")
        val len = payload.size
        val extra = when {
            len < 126 -> 0
            len < 65536 -> 2
            else -> 8
        }
        val out = ByteArray(2 + extra + 4 + len)
        var p = 0
        out[p++] = ((if (fin) 0x80 else 0x00) or (opcode and 0x0F)).toByte()
        when (extra) {
            0 -> out[p++] = (0x80 or len).toByte()
            2 -> {
                out[p++] = (0x80 or 126).toByte()
                out[p++] = ((len shr 8) and 0xFF).toByte()
                out[p++] = (len and 0xFF).toByte()
            }
            else -> {
                out[p++] = (0x80 or 127).toByte()
                val longLen = len.toLong()
                for (i in 7 downTo 0) out[p++] = ((longLen shr (8 * i)) and 0xFF).toByte()
            }
        }
        System.arraycopy(mask, 0, out, p, 4)
        p += 4
        for (i in 0 until len) {
            out[p + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return out
    }

    /**
     * 尝试解析一帧；缓冲区里数据不足时返回 null（调用方继续读 socket）。
     *
     * @param length 缓冲区里**有效**的字节数（可能小于 buf.size）。
     */
    fun decode(buf: ByteArray, length: Int): Decoded? {
        val available = if (length > buf.size) buf.size else length
        if (available < 2) return null
        val b0 = buf[0].toInt() and 0xFF
        val b1 = buf[1].toInt() and 0xFF
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        var p = 2
        if (len == 126L) {
            if (available < p + 2) return null
            len = ((buf[p].toInt() and 0xFF).toLong() shl 8) or (buf[p + 1].toInt() and 0xFF).toLong()
            p += 2
        } else if (len == 127L) {
            if (available < p + 8) return null
            if ((buf[p].toInt() and 0x80) != 0) throw WsProtocolException("negative length")
            var value = 0L
            for (i in 0 until 8) {
                value = (value shl 8) or (buf[p + i].toInt() and 0xFF).toLong()
            }
            len = value
            p += 8
        }
        if (len > MAX_PAYLOAD_BYTES) throw WsProtocolException("frame too large")
        val mask: ByteArray?
        if (masked) {
            if (available < p + 4) return null
            mask = buf.copyOfRange(p, p + 4)
            p += 4
        } else {
            mask = null
        }
        val end = p + len.toInt()
        if (available < end) return null
        val payload = buf.copyOfRange(p, end)
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return Decoded(Frame(opcode, payload, fin), end)
    }
}
