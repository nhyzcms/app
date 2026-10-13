package com.nhyzcms.clubapp.shell.push

import com.nhyzcms.clubapp.shell.update.MiniJson
import java.net.HttpURLConnection
import java.net.URL

/**
 * 未读总数的 HTTP 校准。
 *
 * 为什么还要 HTTP：socket 只推"有新东西"（站点 ADR-0015 明确 socket 只提示刷新、
 * HTTP 才是事实源），未读**条数**得问接口。端点与网页 `messagingApi.unreadCount` 同源：
 * ```
 * GET /messaging/conversations/unread_count/  → {"total": N}
 * GET /messaging/notifications/unread_count/  → {"total": N}
 * ```
 *
 * 安全与稳健：
 *  1. [fetch] 只接受 [isAllowed] 放行的地址（本站 origin 前缀）——不访问任意 URL；
 *  2. 只发 GET、不带请求体；带 Cookie 与 UA 标记；
 *  3. 401/403（未登录 / 被挤号）→ [Result.unauthorized]，由上层停连并清角标；
 *  4. 任何异常都吞掉并返回失败：**校准失败不该影响任何用户体验**（角标退化为事件推断值）；
 *  5. TLS 与网页同一条信任链：走 network-security-config 的信任锚
 *     （含额外锚 Sectigo R46，见 docs/FIX-20261012-CERT-R46.md）。
 */
object UnreadApi {

    const val DEFAULT_TIMEOUT_MS = 8000

    class Result private constructor(val ok: Boolean, val total: Int, val unauthorized: Boolean) {
        companion object {
            fun success(total: Int): Result = Result(true, total, false)
            fun unauthorized(): Result = Result(false, 0, true)
            fun failed(): Result = Result(false, 0, false)
        }
    }

    /** 纯逻辑：地址必须落在给定前缀之下（前缀形如 https://8.153.145.175）。 */
    fun isAllowed(url: String, prefix: String): Boolean {
        if (prefix.isEmpty()) return false
        if (!url.startsWith(prefix)) return false
        return url.substring(prefix.length).startsWith("/")
    }

    /** 纯逻辑：解析 {"total": N}；解析不出来（非 JSON / 缺字段 / 负数）返回 null。 */
    fun parseTotal(text: String?): Int? {
        val root = MiniJson.parse(text) as? Map<*, *> ?: return null
        val value = root["total"] ?: return null
        val number = when (value) {
            is Double -> if (value.isFinite() && value >= 0.0) value.toInt() else null
            is String -> value.toIntOrNull()
            else -> null
        }
        return number?.coerceAtLeast(0)
    }

    /** 阻塞式拉取（调用方必须在后台线程）。 */
    fun fetch(
        url: String,
        allowedPrefix: String,
        cookie: String?,
        userAgent: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Result {
        if (!isAllowed(url, allowedPrefix)) return Result.failed()
        var connection: HttpURLConnection? = null
        return try {
            val opened = URL(url).openConnection() as HttpURLConnection
            connection = opened
            opened.requestMethod = "GET"
            opened.connectTimeout = timeoutMs
            opened.readTimeout = timeoutMs
            opened.instanceFollowRedirects = false
            opened.setRequestProperty("Accept", "application/json")
            opened.setRequestProperty("User-Agent", userAgent)
            if (!cookie.isNullOrBlank()) opened.setRequestProperty("Cookie", cookie)
            val status = opened.responseCode
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
                return Result.unauthorized()
            }
            if (status != HttpURLConnection.HTTP_OK) return Result.failed()
            val body = opened.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return parseTotal(body)?.let { Result.success(it) } ?: Result.failed()
        } catch (e: Exception) {
            Result.failed()
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
