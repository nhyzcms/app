package com.nhyzcms.clubapp.shell.update

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * SHA-256 计算与比对（纯 JVM，可单测）。
 *
 * 为什么不用"下载完再算一遍"的粗暴写法：更新包可能有好几 MB，
 * 而下载线程本来就在逐块读字节，顺手喂给 MessageDigest 不额外增加 I/O。
 */
object ApkDigest {

    const val ALGORITHM = "SHA-256"

    /** 一次读取的块大小（64 KB，和服务器 3 M 带宽下的典型分片接近）。 */
    const val BUFFER_BYTES = 64 * 1024

    private val HEX = "0123456789abcdef".toCharArray()

    fun newDigest(): MessageDigest = MessageDigest.getInstance(ALGORITHM)

    /** 字节数组 → 小写十六进制。 */
    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val value = b.toInt() and 0xFF
            sb.append(HEX[value ushr 4])
            sb.append(HEX[value and 0x0F])
        }
        return sb.toString()
    }

    /** 单个文件的 sha256；读失败返回 null。 */
    fun sha256Of(file: File): String? = runCatching {
        val digest = newDigest()
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        toHex(digest.digest())
    }.getOrNull()

    /** 文本的 sha256（单测与日志用）。 */
    fun sha256Of(text: String): String = toHex(newDigest().digest(text.toByteArray(Charsets.UTF_8)))

    /**
     * 摘要是否一致。
     *
     * 用 [MessageDigest.isEqual] 而不是 `==`：前者是常数时间比较，
     * 虽然这里不涉及密钥，但保持"比摘要就用它"的习惯更不容易在别处写错。
     */
    fun matches(expected: String?, actual: String?): Boolean {
        val a = expected?.trim()?.lowercase(Locale.ROOT) ?: return false
        val b = actual?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (!UpdatePolicy.isValidSha256(a) || !UpdatePolicy.isValidSha256(b)) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
