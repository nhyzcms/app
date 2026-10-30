package com.nhyzcms.clubapp.shell.scan

import java.net.URI

/**
 * 扫到的文本 → 站内落点（纯逻辑，有 JVM 单测）。
 *
 * 工牌上有两种码（见 accounts/member_qr.py / accounts/member_barcode.py）：
 *
 * - **二维码**：`{FRONTEND_URL}/#/v/<8 位识别码>?s=<16 位签名>` —— 扫到就直接进校验页；
 * - **条形码（Code128）**：12 位纯数字（6 位唯一键 + 6 位十进制短签名），落到
 *   `/#/v?q=<12 位>` 让网页查一次（签名即证明，**不消耗** 24 小时额度）。
 *
 * **不接管旧的裸 8 位识别码条码**（2026-10-30 与管理员确认：工牌还没印，没有存量）。
 * 8 位码在服务端是按查询者身份分档**扣额度**的，扫码走它等于替用户白扣一次；
 * 手输查询照旧支持 8 位编号，那条路由网页自己管。
 *
 * 这里**只判断落点**，不解析签名、不判断身份——那是网页与后端的事（壳不做业务）。
 * 扫到不是本站的东西就返回 null，由调用方提示「不是本站工牌」。
 */
object ScanRouter {

    /** 编号查询页（MemberLookupPage），见 frontend/src/App.tsx 的 `/v` 路由。 */
    const val LOOKUP_PATH = "/#/v"

    /** 条形码载荷长度（6 位唯一键 + 6 位十进制短签名）。 */
    const val BARCODE_PAYLOAD_LEN = 12

    /** 扫到的文本上限：工牌码最长也就几十字符，超长的直接判为不是本站工牌。 */
    const val MAX_TEXT_LEN = 256

    /**
     * @param siteOrigin 站点 Origin（`https://8.153.145.175`，唯一真源见 AppConfig）
     * @param siteHost   站点主机名（用于确认扫到的链接确实指向本站）
     * @param raw        扫码原始文本
     * @return 可以交给 WebView 的站内地址；不是本站工牌时返回 null
     */
    fun targetUrl(siteOrigin: String, siteHost: String, raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_TEXT_LEN) return null

        if (isHttpUrl(text)) {
            // 二维码里编的是完整链接：只认本站，别的站点一律不接管（不外跳、不吞掉）。
            // http 的写成 https：站点只允许 HTTPS，交给 WebView 也是白屏。
            val candidate = if (text.startsWith("http://", ignoreCase = true)) {
                "https://" + text.substring("http://".length)
            } else {
                text
            }
            val host = runCatching { URI(candidate).host }.getOrNull() ?: return null
            return if (host.equals(siteHost, ignoreCase = true)) candidate else null
        }

        // 条码是纯数字，但扫码枪 / 手抖可能带空格或短横线，先去掉再判长度。
        // 只认 12 位签名载荷（见上面的注释：8 位裸码会扣额度，不接管）。
        val digits = text.filterNot { it == ' ' || it == '\t' || it == '-' }
        if (digits.length != BARCODE_PAYLOAD_LEN) return null
        if (!digits.all { it in '0'..'9' }) return null
        return siteOrigin + LOOKUP_PATH + "?q=" + digits
    }

    private fun isHttpUrl(text: String): Boolean =
        text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
}
