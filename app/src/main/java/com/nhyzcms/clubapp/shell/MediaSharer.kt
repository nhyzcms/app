package com.nhyzcms.clubapp.shell

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import com.nhyzcms.clubapp.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 分享图片「文件本体」（长按图片 → 分享图片）。
 *
 * 流程：判定（[MediaSharePolicy]）→ 后台线程带 Cookie/UA 下载到 cacheDir/share/。
 * → 主线程用 FileProvider 的 content URI 发起 ACTION_SEND。
 *
 * 取舍：
 *  - 不引第三方网络库（ADR-A05），用 HttpURLConnection，并在读取时边下边判上限（防大文件写满缓存）；
 *  - 下载失败/过大/没有应用可分享 → 回调失败，由调用方退回「分享链接」（不把功能做死）；
 *  - 文件先留在缓存（接收方可能稍后才读），下次启动清掉超过 24 小时的残留；
 *  - 受权限保护的图片必须带会话 Cookie，否则拿到的是登录页 HTML（会分享出去一个“假图片”）。
 */
class MediaSharer(private val context: Context) {

    enum class Result { Success, TooLarge, Failed }

    /**
     * 下载并分享；`onResult` 在主线程回调。失败时调用方应退回分享链接。
     */
    fun share(url: String, userAgent: String?, onResult: (Result) -> Unit) {
        if (MediaSharePolicy.check(url, MediaSharePolicy.UNKNOWN_SIZE) != MediaSharePolicy.Verdict.Allowed) {
            onResult(Result.Failed)
            return
        }
        thread(name = "clubapp-share-image") {
            val outcome = runCatching { fetch(url, userAgent) }.getOrDefault(Fetch.Failed)
            mainHandler.post {
                when (outcome) {
                    is Fetch.Ok -> onResult(dispatch(outcome.file))
                    Fetch.TooLarge -> onResult(Result.TooLarge)
                    Fetch.Failed -> onResult(Result.Failed)
                }
            }
        }
    }

    /** 清理上次分享残留的临时文件（进程启动时调用一次）。 */
    fun cleanStaleFiles() {
        val dir = shareDir() ?: return
        val deadline = System.currentTimeMillis() - STALE_AFTER_MILLIS
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < deadline) file.delete()
            }
        }
    }

    // ── 主线程：发出系统分享面板 ─────────────────────────────────────────

    private fun dispatch(file: File): Result {
        val uri = runCatching {
            FileProvider.getUriForFile(context, AppConfig.FILE_PROVIDER_AUTHORITY, file)
        }.getOrNull() ?: return Result.Failed

        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            // 部分接收方只从 clipData 取流，两种都给土保险
            clipData = ClipData.newRawUri("share", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.dialog_share_image)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            Result.Success
        } catch (e: ActivityNotFoundException) {
            Result.Failed
        }
    }

    private fun mimeOf(file: File): String {
        val guessed = DownloadPolicy.mimeTypeOf(null, file.name)
        return if (guessed == "application/octet-stream") DEFAULT_MIME else guessed
    }

    // ── 后台线程：把图片拉进缓存 ────────────────────────────────────────

    private sealed interface Fetch {
        data class Ok(val file: File) : Fetch

        object TooLarge : Fetch

        object Failed : Fetch
    }

    private fun fetch(url: String, userAgent: String?): Fetch {
        val parsed = runCatching { URI.create(url).toURL() }.getOrNull() ?: return Fetch.Failed
        val connection = runCatching {
            (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                if (!userAgent.isNullOrBlank()) setRequestProperty("User-Agent", userAgent)
                cookieFor(url)?.let { setRequestProperty("Cookie", it) }
            }
        }.getOrNull() ?: return Fetch.Failed

        try {
            if (connection.responseCode !in 200..299) return Fetch.Failed
            if (connection.contentLengthLong > MediaSharePolicy.MAX_SHARE_BYTES) return Fetch.TooLarge

            val file = newTempFile(url) ?: return Fetch.Failed
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        // 服务器没给 Content-Length 时，靠这里卡住上限
                        if (total > MediaSharePolicy.MAX_SHARE_BYTES) {
                            file.delete()
                            return Fetch.TooLarge
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (file.length() <= 0L) {
                file.delete()
                return Fetch.Failed
            }
            return Fetch.Ok(file)
        } catch (e: Exception) {
            return Fetch.Failed
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun newTempFile(url: String): File? {
        val dir = shareDir() ?: return null
        val extension = DownloadPolicy.extensionOf(DownloadPolicy.resolveFileName(url, null, null))
        val suffix = if (extension.isEmpty()) DEFAULT_SUFFIX else "." + extension
        return runCatching { File.createTempFile(FILE_PREFIX, suffix, dir) }.getOrNull()
    }

    private fun shareDir(): File? {
        val dir = File(context.cacheDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) return null
        return dir
    }

    private fun cookieFor(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private companion object {
        val mainHandler = Handler(Looper.getMainLooper())

        const val DIR_NAME = "share"
        const val FILE_PREFIX = "share_"
        const val DEFAULT_SUFFIX = ".jpg"
        const val DEFAULT_MIME = "image/jpeg"
        const val BUFFER_BYTES = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        val STALE_AFTER_MILLIS: Long = TimeUnit.HOURS.toMillis(24)
    }
}
