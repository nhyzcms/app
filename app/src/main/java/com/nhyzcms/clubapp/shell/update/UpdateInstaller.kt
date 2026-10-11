package com.nhyzcms.clubapp.shell.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nhyzcms.clubapp.shell.AppConfig
import java.io.File

/**
 * 启动系统安装器安装更新包。
 *
 * 安全要点：
 *  - 只通过 [FileProvider] 交出**那一个文件**的 `content://` URI（不是 file URI，也不是目录），
 *    并带 `FLAG_GRANT_READ_URI_PERMISSION`；
 *  - 调用前必须已经通过 sha256 + 签名校验（见 [ApkDownloader] 与 [SignatureCheck]）；
 *  - Android 8.0+ 需要用户先授权"安装未知应用"，这里只负责**引导**，不绕过系统限制。
 */
class UpdateInstaller(private val activity: Activity) {

    /** 是否已获准安装未知来源应用（API 26+ 走 canRequestPackageInstalls）。 */
    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳到本应用的"安装未知应用"授权页。 */
    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:" + activity.packageName))
        runCatching { activity.startActivity(intent) }
    }

    /** 拉起安装器；返回 false 表示没有可用的安装器或 URI 生成失败。 */
    fun install(apk: File): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(activity, AppConfig.FILE_PROVIDER_AUTHORITY, apk)
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
