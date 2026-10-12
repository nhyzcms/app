package com.nhyzcms.clubapp.shell

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃报告的文本格式（**纯逻辑**：不 import 任何 android.*，可在 JVM 上单测）。
 *
 * ## 为什么要有“崩溃自记录”
 * 壳是 WebView 应用，出问题时用户手上只有一台手机：
 *   · 读不到 logcat（Android 4.1 起普通应用没有 READ_LOGS，只能看到自己的日志）；
 *   · 手机上跑不了 adb（除非专门开无线调试并配对）；
 *   · 系统“应用已停止运行”的弹窗一闪而过，用户抄不下堆栈。
 * 2026-10-12 那次“一进 App 就闪退”就是因此只能靠读代码反推根因。
 * 所以：未捕获异常先落盘（[CrashReporter]），下次启动时弹出来，用户点一下就能复制/分享。
 *
 * 本文件只负责“把一次崩溃排版成一段人能读、能直接发回来的文本”，因此可以单测。
 */
object CrashText {

    /**
     * 报告长度上限：崩溃路径上不做任何可能失败的事，栈太深时截断，
     * 免得把磁盘写爆或让分享面板塞进几百 KB 文本。
     */
    const val MAX_CHARS = 16 * 1024

    /**
     * 排版一次崩溃。
     *
     * 前四行是**定位必需**的四要素（少了任何一项，用户发回来的东西就不好排查）：
     * 时间、版本、设备（含 API 级别）、线程名；异常与堆栈放在后面。
     */
    fun format(
        timeMs: Long,
        threadName: String,
        appVersion: String,
        device: String,
        error: Throwable,
    ): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))
        val text = buildString {
            append("=== 南汇一中传媒社 App 崩溃报告 ===\n")
            append("时间：").append(time).append('\n')
            append("版本：").append(appVersion).append('\n')
            append("设备：").append(device).append('\n')
            append("线程：").append(threadName).append('\n')
            append("异常：").append(error.javaClass.name).append(": ")
            append(error.message.orEmpty()).append('\n')
            append("堆栈：\n")
            append(stackOf(error))
        }
        return if (text.length > MAX_CHARS) {
            text.take(MAX_CHARS) + "\n（报告超长，已截断）"
        } else {
            text
        }
    }

    /** 打印整条 cause 链：崩溃常被包装过，只看最外层往往看不到真正的原因。 */
    private fun stackOf(error: Throwable): String {
        val buffer = StringWriter()
        PrintWriter(buffer).use { writer -> error.printStackTrace(writer) }
        return buffer.toString()
    }
}
