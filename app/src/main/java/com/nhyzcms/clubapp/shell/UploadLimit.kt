package com.nhyzcms.clubapp.shell

import kotlin.math.roundToLong

/**
 * 上传体积的本地前置判定（纯函数，可 JVM 单测）。
 *
 * 为什么放在壳里：网页的 tus 客户端是分块上传的，等服务器返回 413 时用户往往已经
 * 等了很久，而且 WebView 里弹出的报错很难看懂。这里在「选完文件、还没开始传」时
 * 就给出提示，避免白传一半。
 *
 * 上限来源：服务器 nginx `client_max_body_size 20M`（见 docs/ARCHITECTURE.md §5.4）。
 * 改服务器上限时必须同步改这里（只影响提示文案，不影响上传本身）。
 */
object UploadLimit {

    /** 站点上限：nginx 的 20M == 20 × 1024 × 1024 字节。 */
    const val MAX_BYTES: Long = 20L * 1024L * 1024L

    /** 判定结果。 */
    sealed interface Verdict {
        /** 在上限内，直接交给网页上传。 */
        object Allowed : Verdict

        /** 超过上限，应当先提示用户（继续上传很可能被服务器拒绝）。 */
        data class Exceeded(val totalBytes: Long, val limitBytes: Long) : Verdict
    }

    /**
     * @param sizes 本次选择的每个文件的字节数；拿不到大小用 null 表示
     *              （某些云盘 provider 不返回 SIZE，此时只能交给网页去发现）。
     */
    fun check(sizes: List<Long?>): Verdict {
        val known = sizes.filterNotNull()
        if (known.isEmpty()) return Verdict.Allowed
        val total = known.sum()
        return if (total > MAX_BYTES) Verdict.Exceeded(total, MAX_BYTES) else Verdict.Allowed
    }

    /** 人类可读的大小，例如 `23.4 MB`、`812 KB`、`512 B`。用于提示文案。 */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "未知"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return round1(kb) + " KB"
        return round1(kb / 1024.0) + " MB"
    }

    /** 保留一位小数，且整数时不补 `.0`（20.0 MB → 20 MB）。 */
    private fun round1(value: Double): String {
        val rounded = (value * 10).roundToLong() / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }
}
