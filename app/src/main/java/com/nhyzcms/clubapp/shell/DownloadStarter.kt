package com.nhyzcms.clubapp.shell

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager

/**
 * 把网页里的下载请求交给系统 DownloadManager。
 *
 * 为什么用系统下载器：① 下载在系统进程里跑，通知栏可见进度、可暂停/重试；
 * ② WebView 内自己做下载要处理内存、后台、断点一堆事。
 *
 * ⚠️ **必须手动带 Cookie**：DownloadManager 不共享 WebView 的 CookieJar，
 * 少了它，受权限保护的文件（审核导出、成绩表、附件）会直接拿到 403。
 *
 * ⚠️ 必须在主线程调用：`CookieManager.getInstance()` 不是线程安全的。
 *
 * ⚠️ 落盘位置由调用方（MainActivity）根据“SDK 版本 + 是否持有写权限”算好后传进来，
 * 见 [DownloadPolicy.destination]：Android 8/9 上没有写权限时写公共目录会 SecurityException。
 */
class DownloadStarter(private val context: Context) {

    /**
     * 入队下载。返回 DownloadManager 的任务 id；返回 null 表示未能入队（调用方负责提示）。
     *
     * @param contentLength 来自 DownloadListener，仅用于通知栏描述；-1 表示未知。
     */
    fun start(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        destination: DownloadPolicy.Destination,
    ): Long? {
        val fileName = DownloadPolicy.resolveFileName(url, contentDisposition, mimeType)
        val request = runCatching {
            buildRequest(url, fileName, userAgent, mimeType, contentLength, destination)
        }.getOrNull() ?: return null
        return runCatching { manager().enqueue(request) }.getOrNull()
    }

    private fun buildRequest(
        url: String,
        fileName: String,
        userAgent: String?,
        mimeType: String?,
        contentLength: Long,
        destination: DownloadPolicy.Destination,
    ): DownloadManager.Request = DownloadManager.Request(Uri.parse(url)).apply {
        setTitle(fileName)
        setDescription(describe(contentLength))
        setMimeType(DownloadPolicy.mimeTypeOf(mimeType, fileName))
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        // 3M 带宽 + 校园流量：不要在漫游时偷偷下大文件。
        setAllowedOverRoaming(false)
        if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
        cookieFor(url)?.let { addRequestHeader("Cookie", it) }

        when (destination) {
            DownloadPolicy.Destination.PUBLIC_DOWNLOADS ->
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            DownloadPolicy.Destination.APP_EXTERNAL_FILES ->
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        }
    }

    /** 通知栏副标题：有大小就写上（弱网下用户能判断值不值得等）。 */
    private fun describe(contentLength: Long): String =
        if (contentLength > 0) UploadLimit.formatBytes(contentLength) else "来自南汇一中传媒社"

    private fun cookieFor(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun manager(): DownloadManager = context.getSystemService(DownloadManager::class.java)
}
