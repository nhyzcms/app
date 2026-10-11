package com.nhyzcms.clubapp.shell.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * 取签名证书：① 本机已装的应用；② 待安装的 APK 文件。
 *
 * API 差异（minSdk 26 必须两头都写）：
 *  - API 28+：`GET_SIGNING_CERTIFICATES` → `signingInfo`（支持签名轮换）；
 *  - API 26/27：只有 `GET_SIGNATURES`（已废弃但这两个版本只能用它的旧通道）。
 *
 * 取不到签名时返回空列表——调用方（[SignatureCheck]）会把空列表判为"不一致"，
 * 也就是"取不到就不装"，宁可让用户重试，也不放过一个来源不明的包。
 */
object ApkSignatures {

    /** 本机已装版本的签名证书（DER 字节）。 */
    @Suppress("DEPRECATION")
    fun installed(context: Context): List<ByteArray> {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            } else {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES,
                )
            }
        }.getOrNull() ?: return emptyList()
        return of(info)
    }

    /** 磁盘上 APK 文件的签名证书（DER 字节）。 */
    @Suppress("DEPRECATION")
    fun archive(context: Context, apk: File): List<ByteArray> {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageArchiveInfo(
                    apk.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            } else {
                context.packageManager.getPackageArchiveInfo(
                    apk.absolutePath,
                    PackageManager.GET_SIGNATURES,
                )
            }
        }.getOrNull() ?: return emptyList()
        return of(info)
    }

    @Suppress("DEPRECATION")
    private fun of(info: PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptyList()
            // 多签名包用 apkContentsSigners；单签名包用签名历史（覆盖签名轮换场景）。
            val signers = if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
            if (signers == null || signers.isEmpty()) return emptyList()
            return signers.map { it.toByteArray() }
        }
        val legacy = info.signatures ?: return emptyList()
        if (legacy.isEmpty()) return emptyList()
        return legacy.map { it.toByteArray() }
    }
}
