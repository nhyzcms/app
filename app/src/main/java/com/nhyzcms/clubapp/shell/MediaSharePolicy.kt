package com.nhyzcms.clubapp.shell

/**
 * 分享图片「文件本体」的前置判定（纯函数，可 JVM 单测）。
 *
 * 为什么需要：长按图片时用户想要的是把**图片本身**发给好友，而不是一行链接。
 * 壳的做法是先把图片下到缓存目录，再交给系统分享（见 [MediaSharer]）。
 * 但这一步有代价（耗流量、占存储），所以先判两件事：
 *  ① 地址能不能下（只认 https 直链，blob:/data: 一律不行）；
 *  ② 已知大小是不是超限（未知大小则交给下载过程边下边判）。
 */
object MediaSharePolicy {

    /** 分享图片的体积上限：与上传上限同量级，避免弱网下等很久或把缓存写满。 */
    const val MAX_SHARE_BYTES: Long = 20L * 1024L * 1024L

    /** 未知大小（由下载过程自己控制上限）。 */
    const val UNKNOWN_SIZE: Long = -1L

    sealed interface Verdict {
        object Allowed : Verdict

        /** 地址本身不能分享（原因见 [DownloadPolicy.rejectionOf]）。 */
        data class Rejected(val reason: String) : Verdict

        /** 已知过大。 */
        data class TooLarge(val bytes: Long) : Verdict
    }

    fun check(url: String?, sizeBytes: Long): Verdict {
        DownloadPolicy.rejectionOf(url)?.let { return Verdict.Rejected(it) }
        if (sizeBytes > MAX_SHARE_BYTES) return Verdict.TooLarge(sizeBytes)
        return Verdict.Allowed
    }
}
