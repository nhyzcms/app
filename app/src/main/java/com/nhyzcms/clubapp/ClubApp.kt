package com.nhyzcms.clubapp

import android.app.Application
import com.nhyzcms.clubapp.shell.CrashReporter
import com.nhyzcms.clubapp.shell.push.PushHost
import com.nhyzcms.clubapp.shell.push.PushNotifications

/**
 * 壳的 Application。
 *
 * 职责只有两件（都必须在 Activity 之前就位）：
 *  1. **崩溃自记录**（见 [CrashReporter]）——用户手机上既没有 adb、也读不到 logcat，
 *     一次闪退如果不落到磁盘上，事后只能靠读代码反推（2026-10-12 的启动闪退正是如此）；
 *  2. **消息与角标**（区块 5，见 [PushHost]）——前台 WebSocket 保活 + 系统通知/图标角标。
 *     它靠 ActivityLifecycleCallbacks 自己感知前后台，所以不需要 MainActivity 配合。
 *
 * 这里刻意保持极简：Application 里任何自找的复杂度都会变成"所有异常都更难查"。
 */
class ClubApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)

        val host = PushHost(this, PushNotifications(this))
        host.install()
        pushHost = host
    }

    companion object {
        /**
         * 进程内唯一的推送接线器。
         *
         * 谁需要它：前台服务（进程被系统重启时唤醒看门狗，见 shell/push/PushService）
         * 与 MainActivity（把「未读变化」转成网页事件，让站内红点即时刷新）。
         */
        var pushHost: PushHost? = null
            private set
    }
}
