package com.nhyzcms.clubapp.shell

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.nhyzcms.clubapp.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 拍照直传：相机 App 把照片写进我们自己的 FileProvider 临时文件，
 * 壳拿到 `content://` URI 后交回网页（网页的 tus 客户端负责上传）。
 *
 * 为什么用 FileProvider：不申请任何存储权限，文件只对本 App 与临时授权的相机 App 可见
 * （docs/SECURITY.md §2）。
 *
 * 临时文件生命周期：
 *  - 建在 `cacheDir/capture/`（系统可回收的缓存目录，不占用户存储）；
 *  - 选择器返回后若确定没走相机 → 立刻删除；
 *  - 走了相机 → 保留（网页还要读它上传），下次启动时由 [cleanStaleFiles] 清掉过期残留。
 */
class CameraCapture(private val activity: Activity) {

    private var outputUri: Uri? = null
    private var outputFile: File? = null

    /**
     * 构建相机 Intent（作为文件选择器的额外入口），失败返回 null
     * （此时调用方应退化成「只能选文件」，不要打断网页）。
     */
    fun createCaptureIntentOrNull(): Intent? {
        discardPending()
        val uri = runCatching {
            val file = createTempFile()
            outputFile = file
            FileProvider.getUriForFile(activity, AUTHORITY, file)
        }.getOrNull() ?: return null

        outputUri = uri
        grantToCameraApps(uri)

        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            // 三重授权（不同 ROM 生效点不同）：flags + clipData + 逐个包显式授权
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("capture", uri)
        }
    }

    /**
     * 判断这次返回是不是「相机拍的照片」，是则把 URI 交出去，否则清掉临时文件。
     *
     * 约定：`ACTION_IMAGE_CAPTURE` + `EXTRA_OUTPUT` 成功时**不回传 data**，
     * 所以「data 为空」或「data 里的 URI 就是我们的输出 URI」都算相机返回。
     */
    fun consumeCaptureResult(resultCode: Int, data: Intent?): Uri? {
        val uri = outputUri
        val emptyData = data == null || (data.data == null && data.clipData == null)
        if (uri != null && resultCode == Activity.RESULT_OK && (emptyData || data?.data == uri)) {
            // 照片归网页使用：这里只放开引用，不删文件（删了网页就传不了）。
            outputUri = null
            outputFile = null
            return uri
        }
        discardPending()
        return null
    }

    /** 删除尚未交出去的临时文件（用户取消、选择器构建失败、放弃请求时调用）。 */
    fun discardPending() {
        outputFile?.let { file -> runCatching { file.delete() } }
        outputFile = null
        outputUri = null
    }

    /** 清理上次会话残留的临时文件（进程启动时调用一次）。 */
    fun cleanStaleFiles() {
        val dir = captureDir() ?: return
        val deadline = System.currentTimeMillis() - STALE_AFTER_MILLIS
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < deadline) file.delete()
            }
        }
    }

    private fun createTempFile(): File {
        val dir = captureDir() ?: error("无法创建拍照缓存目录")
        return File.createTempFile(FILE_PREFIX, FILE_SUFFIX, dir)
    }

    private fun captureDir(): File? {
        val dir = File(activity.cacheDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) return null
        return dir
    }

    /**
     * 逐个相机 App 显式授权。
     * Android 11+ 需要 AndroidManifest 里的 `<queries>` 才能查到相机 App（已声明）。
     */
    private fun grantToCameraApps(uri: Uri) {
        val probe = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val targets = runCatching {
            activity.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        for (target in targets) {
            runCatching {
                activity.grantUriPermission(
                    target.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    companion object {
        /**
         * FileProvider authority，必须与 AndroidManifest 里 provider 的
         * `android:authorities` 完全一致，否则 getUriForFile 直接抛异常。
         *
         * 用 `BuildConfig.APPLICATION_ID` 而不是写死包名：debug 变体带 `.debug` 后缀，
         * 写死包名会让 debug 包的拍照在运行时炸掉（清单里也是 ${applicationId}.fileprovider）。
         */
        val AUTHORITY: String = BuildConfig.APPLICATION_ID + ".fileprovider"

        private const val DIR_NAME = "capture"
        private const val FILE_PREFIX = "capture_"
        private const val FILE_SUFFIX = ".jpg"
        private val STALE_AFTER_MILLIS: Long = TimeUnit.HOURS.toMillis(24)
    }
}
