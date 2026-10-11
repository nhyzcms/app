package com.nhyzcms.clubapp.shell.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 更新包下载器（自实现，**不用**系统 DownloadManager）。
 *
 * 为什么不用 DownloadManager：
 *  1. 我们要**边下边算 sha256**，DownloadManager 只给"完成后"的 URI；
 *  2. 更新包应当落在 App 私有缓存目录（不打扰用户、不需要存储权限），
 *     而 DownloadManager 的语义是写公共目录；
 *  3. 验收要求"下载中途杀进程后重进能续传"，只有自己控制 `.part` 文件与偏移量才做得到。
 *
 * 落盘位置：`cacheDir/update/latest.apk.part` → 校验通过后改名 `latest.apk`。
 * 选 cacheDir 的理由：安装包是一次性产物，装完即删；系统回收缓存也不造成损失（重下即可）。
 */
class ApkDownloader(
    private val context: Context,
    private val userAgent: String?,
    private val allowedHosts: Set<String>,
) {

    /** 下载结果。 */
    sealed interface Outcome {
        /** 文件已落盘并通过 sha256 校验。 */
        data class Ok(val file: File, val sha256: String) : Outcome

        /** 失败原因码（UI 据此选文案）。 */
        data class Failed(val reason: String) : Outcome

        /** 用户主动取消（保留 `.part` 以便下次续传）。 */
        object Cancelled : Outcome
    }

    private val cancelled = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun cancel() {
        cancelled.set(true)
    }

    /** 上次未下完的字节数（0 = 没有可续传的残留）。 */
    fun partialLength(): Long {
        val file = partFile() ?: return 0L
        return if (file.isFile) file.length() else 0L
    }

    /**
     * 启动下载（立即返回，进度与结果都回调到主线程）。
     *
     * @param urls           按优先级排好的下载源（自托管在前）。
     * @param expectedSha256 清单里声明的摘要，必填且必须合法。
     * @param expectedSize   清单里声明的体积（0 = 未知）。
     * @param resumeFrom     上次 `.part` 的字节数（0 = 从头开始）。
     */
    fun start(
        urls: List<String>,
        expectedSha256: String,
        expectedSize: Long,
        resumeFrom: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onFinished: (Outcome) -> Unit,
    ) {
        cancelled.set(false)
        thread(name = "clubapp-apk-download") {
            val outcome = try {
                download(urls, expectedSha256, expectedSize, resumeFrom, onProgress)
            } catch (e: Exception) {
                Outcome.Failed(REASON_IO)
            }
            mainHandler.post { onFinished(outcome) }
        }
    }

    /** 删除过期的已下完包（保留 `.part`，它是续传依据）。 */
    fun cleanStaleFiles() {
        val dir = updateDir() ?: return
        val files = dir.listFiles() ?: return
        val deadline = System.currentTimeMillis() - STALE_AFTER_MS
        for (file in files) {
            if (!file.isFile || file.name == PART_NAME) continue
            if (file.lastModified() >= deadline) continue
            runCatching { file.delete() }
        }
    }

    // ── 内部实现 ─────────────────────────────────────────────────────────────

    private fun download(
        urls: List<String>,
        expectedSha256: String,
        expectedSize: Long,
        resumeFrom: Long,
        onProgress: (Long, Long) -> Unit,
    ): Outcome {
        val dir = updateDir() ?: return Outcome.Failed(REASON_NO_DIR)
        if (expectedSize > 0 && dir.usableSpace < expectedSize + SPACE_MARGIN_BYTES) {
            return Outcome.Failed(REASON_NO_SPACE)
        }
        var lastReason = REASON_NO_SOURCE
        for (url in urls) {
            if (cancelled.get()) return Outcome.Cancelled
            if (!UpdatePolicy.isAllowedHttpsUrl(url, allowedHosts)) {
                lastReason = REASON_BAD_SOURCE
                continue
            }
            val attempt = downloadFrom(url, dir, expectedSha256, expectedSize, resumeFrom, onProgress)
            if (attempt is Outcome.Ok) return attempt
            if (attempt is Outcome.Cancelled) return attempt
            if (attempt is Outcome.Failed) lastReason = attempt.reason
        }
        return Outcome.Failed(lastReason)
    }

    private fun downloadFrom(
        url: String,
        dir: File,
        expectedSha256: String,
        expectedSize: Long,
        resumeFrom: Long,
        onProgress: (Long, Long) -> Unit,
    ): Outcome {
        val part = File(dir, PART_NAME)
        var offset = 0L
        val resumable = resumeFrom > 0 &&
            part.isFile &&
            part.length() == resumeFrom &&
            (expectedSize == 0L || resumeFrom < expectedSize)
        if (resumable) {
            offset = resumeFrom
        } else if (part.exists() && !part.delete()) {
            return Outcome.Failed(REASON_NO_DIR)
        }

        val digest = ApkDigest.newDigest()
        if (offset > 0 && !feed(part, digest)) {
            // 残留文件读不动 → 丢掉重来（宁可多下一遍，也不要续在坏数据上）
            part.delete()
            offset = 0L
            digest.reset()
        }

        val connection = runCatching {
            (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", APK_MIME)
                if (offset > 0) setRequestProperty("Range", "bytes=" + offset + "-")
                if (!userAgent.isNullOrBlank()) setRequestProperty("User-Agent", userAgent)
            }
        }.getOrNull() ?: return Outcome.Failed(REASON_IO)

        try {
            val code = connection.responseCode
            if (cancelled.get()) return Outcome.Cancelled
            if (code == HTTP_RANGE_NOT_SATISFIABLE) {
                // 残留文件比服务器上的包还大 → 它是脏数据
                part.delete()
                return Outcome.Failed(REASON_STALE_PART)
            }
            var start = offset
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                if (code !in 200..299) return Outcome.Failed(REASON_HTTP_PREFIX + code)
                // 服务器没按 Range 返回（或不支持）：从头写，别把旧数据当有效
                start = 0L
                digest.reset()
            }
            val declared = connection.contentLengthLong
            val total = if (declared > 0) start + declared else expectedSize

            RandomAccessFile(part, "rw").use { out ->
                if (start == 0L) out.setLength(0)
                out.seek(start)
                var downloaded = start
                var lastReported = start
                connection.inputStream.use { input ->
                    val buffer = ByteArray(ApkDigest.BUFFER_BYTES)
                    while (true) {
                        if (cancelled.get()) return Outcome.Cancelled
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = downloaded
                            report(onProgress, downloaded, total)
                        }
                    }
                }
                report(onProgress, downloaded, total)
            }
        } catch (e: Exception) {
            return Outcome.Failed(REASON_IO)
        } finally {
            runCatching { connection.disconnect() }
        }

        // ── 校验链：体积 → 最小体积 → sha256（任何一步不过就删包，绝不交给安装器）──
        val actualSha256 = ApkDigest.toHex(digest.digest())
        val length = part.length()
        if (expectedSize > 0 && length != expectedSize) {
            part.delete()
            return Outcome.Failed(REASON_SIZE_MISMATCH)
        }
        if (length < UpdatePolicy.MIN_APK_BYTES) {
            part.delete()
            return Outcome.Failed(REASON_TOO_SMALL)
        }
        if (!ApkDigest.matches(expectedSha256, actualSha256)) {
            part.delete()
            return Outcome.Failed(REASON_HASH_MISMATCH)
        }

        val target = File(dir, APK_NAME)
        if (target.exists() && !target.delete()) return Outcome.Failed(REASON_IO)
        if (!part.renameTo(target)) return Outcome.Failed(REASON_IO)
        return Outcome.Ok(target, actualSha256)
    }

    /** 把已有 `.part` 的字节喂给摘要器（续传时的前置步骤）。 */
    private fun feed(file: File, digest: MessageDigest): Boolean = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(ApkDigest.BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        true
    }.getOrDefault(false)

    private fun report(onProgress: (Long, Long) -> Unit, downloaded: Long, total: Long) {
        mainHandler.post { onProgress(downloaded, total) }
    }

    private fun updateDir(): File? {
        val dir = File(context.cacheDir, DIR_NAME)
        if (dir.isDirectory) return dir
        return if (dir.mkdirs()) dir else null
    }

    private fun partFile(): File? = updateDir()?.let { File(it, PART_NAME) }

    companion object {
        const val DIR_NAME = "update"
        const val APK_NAME = "latest.apk"
        const val PART_NAME = "latest.apk.part"
        const val APK_MIME = "application/vnd.android.package-archive"

        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000

        /** 进度回调的最小间隔（字节）：3 M 带宽下约每秒一次，不会刷爆主线程。 */
        const val PROGRESS_STEP_BYTES = 256L * 1024L

        /** 已下完的包保留 3 天（系统清缓存也无所谓，重下即可）。 */
        const val STALE_AFTER_MS = 3L * 24L * 60L * 60L * 1000L

        /** 磁盘余量要求：包体积之外再留 8 MB，避免下载中途写满磁盘。 */
        const val SPACE_MARGIN_BYTES = 8L * 1024L * 1024L

        const val HTTP_RANGE_NOT_SATISFIABLE = 416

        const val REASON_IO = "io"
        const val REASON_NO_DIR = "no-dir"
        const val REASON_NO_SPACE = "no-space"
        const val REASON_NO_SOURCE = "no-source"
        const val REASON_BAD_SOURCE = "bad-source"
        const val REASON_STALE_PART = "stale-part"
        const val REASON_SIZE_MISMATCH = "size-mismatch"
        const val REASON_HASH_MISMATCH = "hash-mismatch"
        const val REASON_TOO_SMALL = "too-small"
        const val REASON_HTTP_PREFIX = "http-"
    }
}
