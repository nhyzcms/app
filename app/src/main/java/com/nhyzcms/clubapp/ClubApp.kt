package com.nhyzcms.clubapp

import android.app.Application
import com.nhyzcms.clubapp.shell.CrashReporter

/**
 * 壳的 Application。
 *
 * 唯一的职责：装上「崩溃自记录」（见 [CrashReporter]）。
 *
 * 为什么需要它：壳的界面全在网页里，用户手机上既没有 adb、也读不到 logcat；
 * 一次闪退如果不落到磁盘上，事后就只能靠读代码反推（2026-10-12 的启动闪退正是如此）。
 * 这里刻意保持极简：Application 里任何自找的复杂度都会变成“所有异常都更难查”。
 */
class ClubApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
