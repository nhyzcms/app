package com.nhyzcms.clubapp.shell.push

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.nhyzcms.clubapp.ClubApp

/**
 * 常驻后台的前台服务（区块 5 补强，2026-10-17）。
 *
 * **为什么需要它**：管理员反馈「有私信时 App 没反应，只有打开私信页才看到」。
 * 旧实现（区块 5 第一版）在 `onActivityStopped` 里主动 `stopPush("退到后台")`，
 * 连接一退到后台就断，之后只能靠下次打开 App 重新连 —— 从根本上不可能「实时」。
 *
 * 现在改成：界面退到后台时由本服务进入前台，持有「消息提醒已开启」这条常驻通知，
 * 让进程不被系统回收，PushHost 的 WS 连接与 15 s 看门狗继续跑。
 *
 * 几个必须这么写的细节：
 *  1. `startForeground` 要在 `onCreate` 里就调用（Android 12+ 要求启动后 5 秒内进前台）；
 *  2. `START_STICKY`：进程被系统杀掉后由系统重启服务，重启时 `ClubApp.onCreate` 会重建
 *     PushHost，`onServiceStarted()` 再把它接回登录态；
 *  3. 通知走**独立渠道**（AppConfig.SERVICE_CHANNEL_ID）且 `setShowBadge(false)`，
 *     否则会污染「未读数字角标」的语义（角标应由未读通知驱动）；
 *  4. `stopWithTask=false`（见 AndroidManifest）：从最近任务划掉也不停，
 *     真正想停只在「退出登录 / 被挤号」时由 PushHost 主动 stopService。
 */
class PushService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 必须先在 5 秒内进入前台，再谈别的；通知内容只有「正在收消息」这一层语义。
        startForeground(
            PushNotifications.SERVICE_ID,
            PushNotifications(this).buildServiceNotification(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务可能在「进程刚被系统重启、还没有任何 Activity」的情况下起来，
        // 此时 PushHost 只装了 ActivityLifecycleCallbacks、看门狗还没转 —— 这里把它唤醒。
        ClubApp.pushHost?.onServiceStarted()
        return START_STICKY
    }

    override fun onDestroy() {
        PushNotifications(this).hideService()
        super.onDestroy()
    }
}
