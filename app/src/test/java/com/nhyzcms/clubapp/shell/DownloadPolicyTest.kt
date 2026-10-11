package com.nhyzcms.clubapp.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DownloadPolicy] 的单测：落盘位置、文件名解析与清洗、可下载性判定。 */
class DownloadPolicyTest {

    @Test
    fun android_10_及以上不需要存储权限() {
        assertEquals(DownloadPolicy.Destination.PUBLIC_DOWNLOADS, DownloadPolicy.destination(29, false))
        assertEquals(DownloadPolicy.Destination.PUBLIC_DOWNLOADS, DownloadPolicy.destination(34, false))
        assertFalse(DownloadPolicy.needsWritePermission(29))
        assertFalse(DownloadPolicy.needsWritePermission(34))
    }

    @Test
    fun android_8_9_没授权时退回应用目录() {
        assertEquals(DownloadPolicy.Destination.APP_EXTERNAL_FILES, DownloadPolicy.destination(26, false))
        assertEquals(DownloadPolicy.Destination.APP_EXTERNAL_FILES, DownloadPolicy.destination(28, false))
        assertTrue(DownloadPolicy.needsWritePermission(26))
        assertTrue(DownloadPolicy.needsWritePermission(28))
    }

    @Test
    fun android_8_9_授权后写公共下载目录() {
        assertEquals(DownloadPolicy.Destination.PUBLIC_DOWNLOADS, DownloadPolicy.destination(26, true))
        assertEquals(DownloadPolicy.Destination.PUBLIC_DOWNLOADS, DownloadPolicy.destination(28, true))
    }

    @Test
    fun 文件名优先取_content_disposition() {
        val header = "attachment; filename=\"成绩表.xlsx\""
        assertEquals(
            "成绩表.xlsx",
            DownloadPolicy.resolveFileName("https://8.153.145.175/media/a/b/x.bin", header, null),
        )
    }

    @Test
    fun 支持_rfc5987_编码的文件名() {
        val header = "attachment; filename*=UTF-8''%E6%88%90%E7%BB%A9%E8%A1%A8.xlsx"
        assertEquals("成绩表.xlsx", DownloadPolicy.resolveFileName("https://8.153.145.175/x", header, null))
    }

    @Test
    fun 没有响应头时用_url_最后一段() {
        assertEquals(
            "report.pdf",
            DownloadPolicy.resolveFileName("https://8.153.145.175/media/report.pdf?token=abc#frag", null, null),
        )
    }

    @Test
    fun url_尾部做百分号解码() {
        assertEquals(
            "新闻.jpg",
            DownloadPolicy.resolveFileName("https://8.153.145.175/media/%E6%96%B0%E9%97%BB.jpg", null, null),
        )
    }

    @Test
    fun 清洗掉路径穿越() {
        assertEquals("passwd", DownloadPolicy.resolveFileName("https://x/../../etc/passwd", null, null))
        val header = "attachment; filename=\"../../evil.sh\""
        assertEquals("evil.sh", DownloadPolicy.resolveFileName(null, header, null))
    }

    @Test
    fun 全是非法字符时退回兜底名() {
        assertEquals("", DownloadPolicy.sanitize(".."))
        assertEquals("", DownloadPolicy.sanitize("/"))
        assertEquals("download", DownloadPolicy.resolveFileName(null, null, null))
        assertEquals("download", DownloadPolicy.resolveFileName("https://x/", null, null))
    }

    @Test
    fun 超长文件名截断但保留扩展名() {
        val long = "a".repeat(200) + ".pdf"
        val safe = DownloadPolicy.sanitize(long)
        assertEquals(DownloadPolicy.MAX_NAME_LENGTH, safe.length)
        assertEquals("pdf", DownloadPolicy.extensionOf(safe))
    }

    @Test
    fun mime_优先用服务器声明否则按扩展名推() {
        assertEquals("application/pdf", DownloadPolicy.mimeTypeOf("application/pdf", "x.bin"))
        assertEquals("image/png", DownloadPolicy.mimeTypeOf(null, "a.png"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            DownloadPolicy.mimeTypeOf(null, "学习笔记.docx"),
        )
        assertEquals("application/octet-stream", DownloadPolicy.mimeTypeOf(null, "mystery"))
    }

    @Test
    fun 仅接受_https_直链() {
        assertNull(DownloadPolicy.rejectionOf("https://8.153.145.175/media/x.pdf"))
        assertEquals(
            DownloadPolicy.REASON_UNSUPPORTED_SCHEME,
            DownloadPolicy.rejectionOf("http://8.153.145.175/media/x.pdf"),
        )
        assertEquals(DownloadPolicy.REASON_BLOB, DownloadPolicy.rejectionOf("blob:https://8.153.145.175/uuid"))
        assertEquals(DownloadPolicy.REASON_BLOB, DownloadPolicy.rejectionOf("data:image/png;base64,AAAA"))
        assertEquals(DownloadPolicy.REASON_MISSING, DownloadPolicy.rejectionOf(null))
        assertEquals(DownloadPolicy.REASON_MISSING, DownloadPolicy.rejectionOf("   "))
    }
}
