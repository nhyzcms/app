package com.nhyzcms.clubapp.shell.push

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 前台 WebSocket 长连接（wss，与网站 `/ws/messaging/` 对齐）。
 *
 * 生命周期约定（与站点 ADR-0015 一致）：**只在前台保活**——不申请前台服务、不做后台保活。
 * App 退到后台就断，回到前台由 [PushHost] 重新拉起。
 *
 * 线程模型：一个 daemon 线程跑「连接 → 读帧 → 断开 → 退避重连」循环；
 * [stop] 关掉 socket 并唤醒退避等待，让线程立刻退出。[Listener] 回调发生在该线程上，
 * 由上层自己切回主线程（见 [PushHost]）。
 *
 * 本文件**不依赖 Android 框架**（只碰 java.* / javax.net.ssl），因此能进本地编译链
 * （/workspace/kandroidcheck.sh 与 klocaltest.sh），不必等 CI 才发现编译错误。
 *
 * 安全：
 *  · 走 TLS，且**显式开启主机名校验**（endpointIdentificationAlgorithm=HTTPS）——
 *    只验证书链不验"对端是谁"等于给中间人留门；
 *  · 信任链与网页一致（network-security-config 的信任锚，含额外锚 Sectigo R46）；
 *  · 握手必须校验 `Sec-WebSocket-Accept`（见 [WsHandshake]），不把任意响应当成功；
 *  · 单帧与单条消息都有大小上限（见 [WsFrame.MAX_PAYLOAD_BYTES]），防被投毒的对端拖死壳。
 */
class PushSocket(
    private val host: String,
    private val port: Int,
    private val path: String,
    private val origin: String,
    private val userAgent: String,
    private val cookie: String?,
    private val listener: Listener,
) {

    interface Listener {
        /** 握手通过、连接可用了。 */
        fun onOpen()

        /** 收到一条可解析的推送事件。 */
        fun onEvent(event: PushProtocol.Event)

        /** 服务端认为未登录 / 会话失效（401 / 403）：不要重连，等下次前台再试。 */
        fun onUnauthorized()

        /** 连接结束的原因（诊断用）。 */
        fun onClosed(reason: String)
    }

    enum class State { IDLE, CONNECTING, OPEN, WAITING_RETRY, STOPPED }

    @Volatile
    var state: State = State.IDLE
        private set

    private val running = AtomicBoolean(false)
    private val gate = Object()
    private val random = Random()

    @Volatile
    private var socket: SSLSocket? = null

    @Volatile
    private var output: OutputStream? = null

    /**
     * 连接循环是否还活着。
     *
     * ❗ 宿主必须看它而不是只看"引用非空"：连接线程可能已经退出
     * （未登录 / 会话失效 / 连续失败），此时旧对象已是死尸，
     * 只看引用就会"永久再也连不上"（2026-10-13 真机事故的第二个坑）。
     */
    val isRunning: Boolean get() = running.get()

    /** 启动（幂等）。 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        state = State.CONNECTING
        val thread = Thread({ loop() }, "push-socket")
        thread.isDaemon = true
        thread.start()
    }

    /** 停止（幂等）：关 socket + 唤醒退避等待。 */
    fun stop(reason: String) {
        if (!running.getAndSet(false)) return
        closeQuietly()
        synchronized(gate) { gate.notifyAll() }
        state = State.STOPPED
        listener.onClosed(reason)
    }

    // ── 主循环 ─────────────────────────────────────────────────────────────

    private fun loop() {
        var backoff = MIN_BACKOFF_MS
        while (running.get()) {
            if (!PushSession.isLoggedIn(cookie)) {
                state = State.IDLE
                listener.onClosed("未登录，不连推送")
                return
            }
            try {
                state = State.CONNECTING
                connectAndRead()
                state = State.WAITING_RETRY
            } catch (e: Unauthorized) {
                running.set(false)
                state = State.STOPPED
                closeQuietly()
                listener.onUnauthorized()
                return
            } catch (e: Exception) {
                state = State.WAITING_RETRY
                listener.onClosed("连接断开：" + (e.message ?: e.javaClass.simpleName))
            } finally {
                closeQuietly()
            }
            if (!running.get()) return
            awaitBackoff(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun awaitBackoff(ms: Long) {
        val wait = ms + random.nextInt(JITTER_MS)
        synchronized(gate) {
            if (!running.get()) return
            try {
                gate.wait(wait)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    // ── 连接 + 读帧 ────────────────────────────────────────────────────────

    private fun connectAndRead() {
        val key = WsHandshake.randomKey()
        val plain = Socket()
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tls = factory.createSocket(plain, host, port, true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.soTimeout = READ_TIMEOUT_MS
        tls.startHandshake()
        socket = tls

        val out = tls.outputStream
        output = out
        out.write(
            WsHandshake.request(host, path, origin, cookie, userAgent, key)
                .toByteArray(Charsets.US_ASCII),
        )
        out.flush()

        val input = tls.inputStream
        val head = readHead(input) ?: throw IOException("握手响应为空")
        val verdict = WsHandshake.verify(head, key)
        if (!verdict.ok) {
            if (head.contains(" 401") || head.contains(" 403")) throw Unauthorized()
            throw IOException("握手失败：" + verdict.reason)
        }
        state = State.OPEN
        listener.onOpen()
        readLoop(input)
    }

    /** 逐字节读到空行（握手响应头只有几百字节）。 */
    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(512)
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            matched = when {
                b == CR && (matched == 0 || matched == 2) -> matched + 1
                b == LF && matched == 1 -> 2
                b == LF && matched == 3 -> 4
                else -> 0
            }
            if (matched == 4) break
            if (buffer.size() > MAX_HEAD_BYTES) throw IOException("握手响应头过大")
        }
        return String(buffer.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readLoop(input: InputStream) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var filled = 0
        val message = ByteArrayOutputStream(1024)
        var messageOpcode = -1
        var lastActivity = System.currentTimeMillis()

        while (running.get()) {
            // 先把缓冲区里已经完整的帧全部吃掉
            while (true) {
                val decoded = try {
                    WsFrame.decode(buffer, filled)
                } catch (e: WsFrame.WsProtocolException) {
                    throw IOException("协议错误：" + (e.message ?: "frame"))
                } ?: break
                System.arraycopy(buffer, decoded.consumed, buffer, 0, filled - decoded.consumed)
                filled -= decoded.consumed
                val frame = decoded.frame
                lastActivity = System.currentTimeMillis()
                when (frame.opcode) {
                    WsFrame.OP_TEXT, WsFrame.OP_BINARY -> {
                        message.reset()
                        message.write(frame.payload)
                        messageOpcode = frame.opcode
                        if (frame.fin) {
                            deliver(messageOpcode, message)
                            messageOpcode = -1
                        }
                    }

                    WsFrame.OP_CONTINUATION -> {
                        if (messageOpcode < 0) throw IOException("意外的续帧")
                        message.write(frame.payload)
                        if (frame.fin) {
                            deliver(messageOpcode, message)
                            messageOpcode = -1
                        }
                    }

                    WsFrame.OP_PING -> send(WsFrame.OP_PONG, frame.payload)

                    WsFrame.OP_CLOSE -> {
                        send(WsFrame.OP_CLOSE, ByteArray(0))
                        return
                    }

                    else -> Unit
                }
            }
            if (filled >= buffer.size) throw IOException("帧超出读缓冲")

            val read: Int = try {
                input.read(buffer, filled, buffer.size - filled)
            } catch (e: SocketTimeoutException) {
                // 空闲时发心跳保活；长时间既没数据也没回应就重连（防止 NAT 悄悄掐断）
                send(WsFrame.OP_PING, ByteArray(0))
                if (System.currentTimeMillis() - lastActivity > IDLE_RECONNECT_MS) {
                    throw IOException("长时间没有数据，重连")
                }
                0
            }
            if (read < 0) return
            if (read == 0) continue
            filled += read
        }
    }

    private fun deliver(opcode: Int, message: ByteArrayOutputStream) {
        if (opcode != WsFrame.OP_TEXT) return
        val bytes = message.toByteArray()
        if (bytes.size > MAX_EVENT_BYTES) return
        val event = PushProtocol.parse(String(bytes, Charsets.UTF_8)) ?: return
        listener.onEvent(event)
    }

    private fun send(opcode: Int, payload: ByteArray) {
        val out = output ?: return
        val bytes = try {
            WsFrame.encode(opcode, payload)
        } catch (e: Exception) {
            return
        }
        synchronized(gate) {
            try {
                out.write(bytes)
                out.flush()
            } catch (e: Exception) {
                // 写失败：读循环随后会因 IO 异常退出并走重连
            }
        }
    }

    private fun closeQuietly() {
        val current = socket
        socket = null
        output = null
        if (current != null) runCatching { current.close() }
    }

    private class Unauthorized : Exception()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000
        const val IDLE_RECONNECT_MS = 150_000L
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val JITTER_MS = 400
        const val READ_BUFFER_BYTES = 16 * 1024
        const val MAX_HEAD_BYTES = 8 * 1024
        const val MAX_EVENT_BYTES = 8 * 1024
        const val CR = 13
        const val LF = 10
    }
}
