package com.nhyzcms.clubapp.shell

/**
 * 长按命中的判定（纯函数，可 JVM 单测）。
 *
 * WebView 只给一个 `HitTestResult`（一个 int + 一个 extra 字符串），本类把它翻译成
 * 「图片 / 链接 / 不管」，并做 scheme 白名单 —— `javascript:`、`blob:`、`data:` 这类
 * 都不能交给系统浏览器或下载器。
 */
object LongPressPolicy {

    /*
      与 android.webkit.WebView.HitTestResult 的常量值一一对应。
      这里镜像取值（而不是直接引用 Android 类）是为了让本类保持纯 JVM 可测；
      int 常量在编译期内联，所以取值必须与平台一致，改动前先核对文档。
     */
    const val TYPE_UNKNOWN = 0
    const val TYPE_ANCHOR = 1
    const val TYPE_IMAGE = 5
    const val TYPE_IMAGE_ANCHOR = 6
    const val TYPE_SRC_ANCHOR = 7
    const val TYPE_SRC_IMAGE_ANCHOR = 8

    sealed interface Target {
        /** 长按在图片上（url = 图片地址）。 */
        data class Image(val url: String) : Target

        /** 长按在链接上（url = 链接地址）。 */
        data class Link(val url: String) : Target

        /** 交回系统默认行为（文本选择、输入框等）——不要吃掉这种情况。 */
        object None : Target
    }

    fun classify(hitTestType: Int, extra: String?): Target {
        val url = extra?.trim().orEmpty()
        if (!isHttpUrl(url)) return Target.None
        return when (hitTestType) {
            TYPE_IMAGE, TYPE_IMAGE_ANCHOR, TYPE_SRC_IMAGE_ANCHOR -> Target.Image(url)
            TYPE_ANCHOR, TYPE_SRC_ANCHOR -> Target.Link(url)
            else -> Target.None
        }
    }

    /** 只认 http/https（能否真正下载由 DownloadPolicy 再判一次）。 */
    fun isHttpUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
}
