package com.nhyzcms.clubapp.shell

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * 下载策略（纯函数，可 JVM 单测）：落盘位置、文件名、可下载性判定。
 *
 * 关键取舍：**一个存储权限都不申请**（docs/SECURITY.md §2、§7）。
 *  - Android 10（API 29）起，DownloadManager 写公共「下载」目录不再需要权限；
 *  - Android 8/9（API 26~28）写公共目录需要 WRITE_EXTERNAL_STORAGE，与安全基线冲突，
 *    因此改为落在 App 自己的外部目录（通知栏依旧可见，可从通知打开/分享）。
 */
object DownloadPolicy {

    /** `Environment.DIRECTORY_DOWNLOADS` 的值；写成常量是为了让本类不依赖 Android 类。 */
    const val DOWNLOAD_DIR = "Download"

    /** 拿不到任何文件名线索时的兜底名。 */
    const val FALLBACK_FILE_NAME = "download"

    /** 文件名长度上限（含扩展名）：太长会被部分文件系统/下载器拒绝。 */
    const val MAX_NAME_LENGTH = 96

    const val REASON_MISSING = "missing-url"
    const val REASON_BLOB = "blob-or-data-url"
    const val REASON_UNSUPPORTED_SCHEME = "unsupported-scheme"

    enum class Destination {
        /** 公共「下载」目录（API 29+，不需要权限）。 */
        PUBLIC_DOWNLOADS,

        /** App 自己的外部文件目录（API 26~28，避免申请存储权限）。 */
        APP_EXTERNAL_FILES,
    }

    fun of(sdkInt: Int): Destination =
        if (sdkInt >= 29) Destination.PUBLIC_DOWNLOADS else Destination.APP_EXTERNAL_FILES

    /**
     * 能否交给系统下载器。返回 null 表示可以，否则返回拒绝原因（用于记日志/选文案）。
     *
     * 只接受 https：站点的会话 Cookie 只在 HTTPS 下有效，明文请求也会被
     * network-security-config 拦掉，早点拒绝比让用户看到「下载失败」更好。
     */
    fun rejectionOf(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return REASON_MISSING
        val scheme = trimmed.substringBefore(':', "").lowercase(Locale.ROOT)
        return when {
            scheme == "blob" || scheme == "data" -> REASON_BLOB
            scheme != "https" -> REASON_UNSUPPORTED_SCHEME
            else -> null
        }
    }

    /**
     * 决定下载后的文件名：Content-Disposition → URL 最后一段 → 兜底名。
     * 一律清洗路径分隔符与前导点，避免写到目标目录之外。
     */
    fun resolveFileName(url: String?, contentDisposition: String?, mimeType: String?): String {
        val fromHeader = contentDisposition?.let(::fileNameFromContentDisposition)
        val fromUrl = url?.let(::fileNameFromUrl)
        val safe = sanitize(fromHeader ?: fromUrl ?: FALLBACK_FILE_NAME)
        if (safe.isNotBlank()) return safe
        val ext = extensionOf(sanitize(fromHeader ?: fromUrl ?: ""))
        return if (ext.isEmpty()) FALLBACK_FILE_NAME else FALLBACK_FILE_NAME + "." + ext
    }

    /** MIME：优先用服务器声明的，其次按扩展名推。 */
    fun mimeTypeOf(mimeType: String?, fileName: String): String {
        val declared = mimeType?.trim().orEmpty()
        if (declared.isNotEmpty() && declared != "application/octet-stream") return declared
        return MIME_BY_EXTENSION[extensionOf(fileName)] ?: "application/octet-stream"
    }

    /** 扩展名（小写、不含点）；没有则返回空串。 */
    fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return ""
        return fileName.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /**
     * 文件名清洗：取最后一段 + 去掉控制字符与文件系统非法字符 + 去首尾点/空格 + 限长。
     * 目标是「怎么都不能写到目标目录之外，也不能是隐藏文件」。
     */
    fun sanitize(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        var cleaned = base
            .replace(Regex("[\\:*?\"<>|\\u0000-\\u001F\\u007F]"), "_")
            .trim()
        cleaned = cleaned.trimStart('.')
        cleaned = cleaned.trimEnd('.', ' ')
        if (cleaned.isEmpty()) return ""
        return if (cleaned.length <= MAX_NAME_LENGTH) cleaned else shorten(cleaned)
    }

    /** 超长时保留扩展名截断。 */
    private fun shorten(name: String): String {
        val ext = extensionOf(name)
        val suffix = if (ext.isEmpty()) "" else "." + ext
        val keep = (MAX_NAME_LENGTH - suffix.length).coerceAtLeast(1)
        return name.substring(0, keep) + suffix
    }

    /**
     * 从 Content-Disposition 里取文件名，支持两种写法：
     *  - `filename*=UTF-8''%E6%96%B0%E9%97%BB.jpg`（RFC 5987，中文名通常走这个）
     *  - `filename="news.jpg"` / `filename=news.jpg`
     */
    private fun fileNameFromContentDisposition(header: String): String? {
        val star = header.indexOf("filename*=", ignoreCase = true)
        if (star >= 0) {
            val value = header.substring(star + "filename*=".length)
                .substringBefore(';')
                .trim()
            // 形如 UTF-8''%E4%B8%AD.txt：丢掉 charset 与语言部分
            val encoded = value.substringAfter("''", missingDelimiterValue = value)
            decode(encoded)?.let { if (it.isNotBlank()) return it }
        }
        val plain = header.indexOf("filename=", ignoreCase = true)
        if (plain < 0) return null
        val value = header.substring(plain + "filename=".length)
            .substringBefore(';')
            .trim()
            .trim('"')
        decode(value)?.let { if (it.isNotBlank()) return it }
        return null
    }

    /** 从 URL 取最后一段（丢掉 query 与 fragment），并做一次百分号解码。 */
    private fun fileNameFromUrl(url: String): String? {
        val withoutFragment = url.substringBefore('#')
        val withoutQuery = withoutFragment.substringBefore('?')
        val tail = withoutQuery.substringAfterLast('/')
        if (tail.isBlank()) return null
        return decode(tail) ?: tail
    }

    /** 百分号解码：失败就返回 null（宁可退回兜底名，也不要抛异常打断下载）。 */
    private fun decode(value: String): String? =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()

    private val MIME_BY_EXTENSION: Map<String, String> = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    )
}
