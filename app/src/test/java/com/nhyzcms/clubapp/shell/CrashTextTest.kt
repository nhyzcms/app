package com.nhyzcms.clubapp.shell

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CrashText] 是纯逻辑（不依赖 Android），在此钉死报告的必备要素。
 *
 * 为什么值得测：用户是“把弹窗截图发回来”，截图里少了线程名/版本/异常类名，
 * 这份报告就白写了。格式要点必须有断言兜着。
 */
class CrashTextTest {

    private fun sample() = CrashText.format(
        timeMs = 1_760_000_000_000L,
        threadName = "clubapp-update-check",
        appVersion = "0.4.0 (4)",
        device = "HUAWEI ELS-AN10 / Android 12 (API 31)",
        error = IllegalStateException("后台线程访问了 WebView"),
    )

    @Test
    fun `报告包含定位所需的四要素`() {
        val text = sample()
        assertTrue(text.contains("时间："))
        assertTrue(text.contains("版本：0.4.0 (4)"))
        assertTrue(text.contains("设备：HUAWEI ELS-AN10 / Android 12 (API 31)"))
        assertTrue(text.contains("线程：clubapp-update-check"))
    }

    @Test
    fun `报告包含异常类型与消息`() {
        assertTrue(sample().contains("java.lang.IllegalStateException: 后台线程访问了 WebView"))
    }

    @Test
    fun `报告带堆栈（能把调用点还原出来）`() {
        // 断言里出现本测试类名 = printStackTrace 的栈帧确实被写进去了
        assertTrue(sample().contains("CrashTextTest"))
    }

    @Test
    fun `超长报告被截断并给出提示`() {
        val huge = IllegalStateException("x".repeat(CrashText.MAX_CHARS * 2))
        val text = CrashText.format(
            timeMs = 1_760_000_000_000L,
            threadName = "main",
            appVersion = "0.4.0 (4)",
            device = "test",
            error = huge,
        )
        assertTrue(text.contains("已截断"))
        assertTrue(text.length <= CrashText.MAX_CHARS + 32)
    }

    @Test
    fun `异常没有消息时也能排版`() {
        val text = CrashText.format(
            timeMs = 1_760_000_000_000L,
            threadName = "main",
            appVersion = "0.4.0 (4)",
            device = "test",
            error = RuntimeException(),
        )
        assertTrue(text.contains("java.lang.RuntimeException"))
    }
}
