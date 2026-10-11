package com.nhyzcms.clubapp.shell.update

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * 拉取 app-manifest.json（**阻塞实现**，调用方负责放到后台线程）。
 *
 * 三条铁律（见 docs/UPDATE-STRATEGY.md §3）：
 *  1. 失败必须静默：拿不到配置就用缓存/默认值，绝不能因为配置拉不到让 App 不可用；
 *  2. 只拉白名单主机上的 https：清单本身能改变壳的行为，不能让中间人改；
 *  3. 显式 no-cache：否则下次协商拿到的还是旧开关。
 */
class ManifestFetcher(
    private val userAgent: String?,
    private val allowedHosts: Set<String>,
) {

    /** 成功返回响应体文本；任何问题（含非法地址）返回 null。 */
    fun fetch(url: String): String? {
        if (!UpdatePolicy.isAllowedHttpsUrl(url, allowedHosts)) return null
        val connection = runCatching {
            (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Accept", "application/json")
                if (!userAgent.isNullOrBlank()) setRequestProperty("User-Agent", userAgent)
            }
        }.getOrNull() ?: return null

        try {
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > MAX_BODY_BYTES) return null
            return connection.inputStream.use { readLimited(it, MAX_BODY_BYTES) }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** 读入上限内的文本；超限返回 null（宁可当作没拉到，也不让服务器把内存打满）。 */
    private fun readLimited(input: InputStream, limit: Long): String? {
        val buffer = ByteArray(BUFFER_BYTES)
        val out = ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000
        const val BUFFER_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 256L * 1024L
    }
}
