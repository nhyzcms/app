package com.nhyzcms.clubapp.shell.push

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * WebSocket 握手（RFC 6455 第 4 节；纯逻辑，可 JVM 单测）。
 *
 * 三件必须做对的事：
 *  1. `Sec-WebSocket-Key` 是 16 字节随机数的 base64，对端回的
 *     `Sec-WebSocket-Accept` 必须等于 base64(sha1(key + GUID))。**必须校验它**：
 *     wss 之上什么响应都可能是代理/拦截页，不校验就会把一段 HTML 当成握手成功；
 *  2. **必须带 `Origin`**：站点用 `AllowedHostsOriginValidator` 校验它，
 *     缺失或 host 不在 `ALLOWED_HOSTS` 里会被直接 403（实测未登录连接就是 403）；
 *  3. 必须带 `Cookie`（登录态，服务端 `AuthMiddlewareStack` 认它）与 `User-Agent`
 *     （网站靠 UA 标记识别 App，契约见 ARCHITECTURE 第 5.1 节）。
 */
object WsHandshake {

    /** RFC 6455 规定的魔数串。 */
    const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    private val random = SecureRandom()

    /** 16 字节随机数的 base64（24 个字符）。 */
    fun randomKey(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** base64(sha1(key + GUID))。 */
    fun acceptFor(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    /** 构造握手请求（HTTP/1.1 文本，以空行结束）。cookie 为空则不发送 Cookie 头。 */
    fun request(
        host: String,
        path: String,
        origin: String,
        cookie: String?,
        userAgent: String,
        key: String,
    ): String {
        val sb = StringBuilder(256)
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(host).append("\r\n")
        sb.append("Upgrade: websocket\r\n")
        sb.append("Connection: Upgrade\r\n")
        sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n")
        sb.append("Sec-WebSocket-Version: 13\r\n")
        sb.append("Origin: ").append(origin).append("\r\n")
        if (!cookie.isNullOrBlank()) sb.append("Cookie: ").append(cookie).append("\r\n")
        sb.append("User-Agent: ").append(userAgent).append("\r\n")
        sb.append("\r\n")
        return sb.toString()
    }

    /**
     * 校验握手响应（头部文本，规范化为小写比较，容错多余空格）。
     */
    fun verify(head: String, key: String): Result {
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Result(false, "空响应")
        val status = lines[0]
        if (!status.contains(" 101")) return Result(false, "状态行不是 101：" + status.take(64))
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val upgrade = headers["upgrade"]
        if (upgrade == null || !upgrade.equals("websocket", ignoreCase = true)) {
            return Result(false, "缺少 Upgrade: websocket")
        }
        val connection = headers["connection"].orEmpty()
        if (!connection.lowercase().contains("upgrade")) {
            return Result(false, "缺少 Connection: Upgrade")
        }
        val expected = acceptFor(key)
        val actual = headers["sec-websocket-accept"]
        if (actual != expected) return Result(false, "Sec-WebSocket-Accept 不匹配")
        return Result(true, "ok")
    }

    class Result(val ok: Boolean, val reason: String)
}
