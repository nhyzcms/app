package com.nhyzcms.clubapp.shell

import android.content.Context
import java.io.File

/**
 * 运行轨迹落盘（壳侧唯一的"日志"）。
 *
 * 为什么需要：用户端只有一台手机 —— 读不到 logcat（无 READ_LOGS）、也用不了 adb。
 * 「崩溃自记录 + 诊断浮层 + 轨迹文件」是排查三件套（见 docs/ROADMAP.md 区块 10）。
 *
 * 约束：
 *  · 只追加、不轮转，超过 [MAX_BYTES] 就整体停写（宁可丢新日志，也不把用户存储写满）；
 *  · 任何异常都吞掉：写日志失败绝不能让 App 崩掉。
 *
 * 区块 6 起由 [com.nhyzcms.clubapp.shell.feel.FeelHost] 与
 * [com.nhyzcms.clubapp.shell.perf.PerfHost] 共用（原先这段逻辑写在 FeelHost 里）。
 */
object DiagLog {

    /** 轨迹文件体积上限：64 KB 足够装下几次冷启动的关键事件。 */
    const val MAX_BYTES = 64L * 1024L

    private const val DIR_NAME = "diag"
    private const val FILE_NAME = "last-run.txt"

    fun append(context: Context, line: String) {
        runCatching {
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.isDirectory && !dir.mkdirs()) return
            val file = File(dir, FILE_NAME)
            if (file.length() > MAX_BYTES) return
            file.appendText("[" + System.currentTimeMillis() + "] " + line + "\n")
        }
    }
}
