package com.nhyzcms.clubapp.shell

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.nhyzcms.clubapp.BuildConfig
import com.nhyzcms.clubapp.R
import java.io.File

/**
 * 崩溃自记录：把未捕获异常写进 App 私有目录，**下次启动**时弹出来给用户看（可复制/分享）。
 *
 * 为什么这样做（而不是“上传到服务器”）：
 *  1. 崩溃时进程马上就要死，任何网络请求都不可靠；落盘是唯一能做完的事；
 *  2. 不上传 = 不引入新的上报接口与隐私面（本站只有一台 2C2G/3M 的服务器，也没有日志收集后端）；
 *  3. 用户点一下“分享日志”就能发到群里/发给管理员，比让他去找 adb 现实得多。
 * 位置：`filesDir/crash/last-crash.txt`（进程重启也不会丢），另存一份到外部私有目录
 * `getExternalFilesDir(null)/last-crash.txt`，方便用户用系统「文件管理」直接找到这个文件。
 *
 * 安全边界：只记录我们自己进程的异常文本（不采集账号、不采集页面内容）；
 * 文件留在 App 私有目录，只有用户主动“分享”才会外传。
 */
object CrashReporter {

    private const val TAG = "ClubApp"
    private const val DIR_NAME = "crash"
    private const val FILE_NAME = "last-crash.txt"

    /** 弹窗里最多显示多少字符（全文仍可通过“复制日志/分享日志”完整拿到）。 */
    private const val DIALOG_CHARS = 3000

    /**
     * 装配：接管未捕获异常 + 首个 Activity 前台时弹出上次的报告。重复调用无副作用。
     *
     * 异常处理里**只做落盘**，然后把控制权交回系统原来的处理器 ——
     * 这样“应用已停止运行”的系统行为与进程终止语义都保持不变。
     */
    fun install(application: Application) {
        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { write(application, thread, error) }
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }

            var shown = false
            application.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: Activity) {
                        if (shown) return
                        shown = true
                        val report = read(activity) ?: return
                        clear(activity)
                        show(activity, report)
                    }

                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                    override fun onActivityStarted(activity: Activity) = Unit

                    override fun onActivityPaused(activity: Activity) = Unit

                    override fun onActivityStopped(activity: Activity) = Unit

                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                    override fun onActivityDestroyed(activity: Activity) = Unit
                },
            )
        }.onFailure { Log.w(TAG, "崩溃自记录装配失败：" + it) }
    }

    /** 上次留下的报告（没有则返回 null）。 */
    fun read(context: Context): String? {
        val file = crashFile(context)
        return runCatching {
            if (file.isFile && file.length() > 0L) file.readText() else null
        }.getOrNull()
    }

    /** 清掉报告（弹过一次就不再弹）。 */
    fun clear(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val text = CrashText.format(
            timeMs = System.currentTimeMillis(),
            threadName = thread.name,
            appVersion = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
            device = Build.MODEL + " / Android " + Build.VERSION.RELEASE +
                " (API " + Build.VERSION.SDK_INT + ")",
            error = error,
        )
        // 进程马上就会死，必须同步写完（不能用异步/apply）。
        crashFile(context).writeText(text)
        runCatching {
            context.getExternalFilesDir(null)?.let { dir -> File(dir, FILE_NAME).writeText(text) }
        }.onFailure { Log.w(TAG, "崩溃报告外置副本写入失败：" + it) }
    }

    private fun crashFile(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) return File(context.filesDir, FILE_NAME)
        return File(dir, FILE_NAME)
    }

    private fun show(activity: Activity, report: String) {
        if (activity.isFinishing) return
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle(R.string.crash_report_title)
                .setMessage(report.take(DIALOG_CHARS))
                .setCancelable(true)
                .setPositiveButton(R.string.action_crash_copy) { _, _ -> copy(activity, report) }
                .setNeutralButton(R.string.action_crash_share) { _, _ -> share(activity, report) }
                .setNegativeButton(R.string.action_ok, null)
                .show()
        }.onFailure { Log.w(TAG, "崩溃报告弹窗失败：" + it) }
    }

    private fun copy(context: Context, report: String) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("crash", report))
            Toast.makeText(context, R.string.toast_crash_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(context: Context, report: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report)
            }
            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.dialog_crash_share)),
            )
        }
    }
}
