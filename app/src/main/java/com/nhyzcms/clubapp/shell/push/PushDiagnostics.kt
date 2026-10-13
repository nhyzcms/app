package com.nhyzcms.clubapp.shell.push

import android.app.Application
import android.content.Context
import android.os.Handler
import android.util.Log
import android.widget.Toast
import com.nhyzcms.clubapp.BuildConfig
import com.nhyzcms.clubapp.shell.DiagLog

/**
 * 推送的"可观测性"（只对 debug 包可见 + 轨迹落盘）。
 *
 * 为什么必须存在（2026-10-13 真机事故的教训）：用户手机上既没有 adb、也读不到 logcat，
 * 而区块 5 上线后出现"完全没反应"时，壳里**一点痕迹都没有**，
 * 只能靠服务器 access.log 反推"原生请求一条都没发出"（见 docs/FIX-20261013-PUSH-NOT-FIRING.md）。
 * 所以功能必须自己"说话"：
 *
 *  1. toast：状态变化时最多弹 3 条（一闪而过，但用户当场就能看到）；
 *  2. **通知栏状态**：把最新一条状态留在通知栏（反复更新同一条）——
 *     用户可以慢慢看、顺手截图给管理员；走独立渠道且不驱动角标；
 *  3. 运行轨迹：写进 DiagLog（filesDir/diag/last-run.txt，区块 6 引入的设施）。
 *
 * release 包完全静默：生产用户不该被诊断信息打扰。
 */
class PushDiagnostics(
    private val app: Application,
    private val notifications: PushNotifications,
    private val main: Handler,
) {

    private var toastCount = 0

    /** 记一条推送状态（可在任意线程调用；内部统一切到主线程）。 */
    fun report(message: String) {
        Log.d(TAG, "推送：" + message)
        main.post {
            DiagLog.append(app, "推送：" + message)
            if (!BuildConfig.DEBUG) return@post
            notifications.showDiagnostic(message)
            if (toastCount >= MAX_TOASTS) return@post
            toastCount += 1
            runCatching {
                Toast.makeText(app, "推送自检：" + message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 每次安装只发一次的自检通知：验证"通知这条路在这台设备上通不通"。
     * 看不到它 = 系统把通知拦了（去「设置 → 通知」允许）——那时角标/提醒都不会有。
     */
    fun selfTestOnce() {
        if (!BuildConfig.DEBUG) return
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SELF_TEST_DONE, false)) return
        prefs.edit().putBoolean(KEY_SELF_TEST_DONE, true).apply()
        // 渠道创建后系统可能要一会儿才允许投递；也避开首屏加载高峰。
        main.postDelayed({
            if (!notifications.canPost()) {
                report("没有通知权限，自检通知发不出去")
                return@postDelayed
            }
            notifications.showSelfTest()
        }, SELF_TEST_DELAY_MS)
    }

    private companion object {
        const val TAG = "ClubApp"
        const val PREFS_NAME = "push"
        const val KEY_SELF_TEST_DONE = "self_test_done"
        const val MAX_TOASTS = 3
        const val SELF_TEST_DELAY_MS = 6000L
    }
}
