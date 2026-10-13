# 故障复盘：区块 5 上线后真机"完全没反应"（2026-10-13）

> 现象：区块 5（PR #16）装上真机后，**没有通知、没有角标、App 里也没有任何提示**，
> 只有在私信页里才看得到新消息。属于"功能写了但一次都没跑起来"。

---

## 1. 怎么定位的（用户端只有手机，没有 adb/logcat）

排查靠的是**服务器 access.log 的反推** —— 因为区块 5 之前的更新检查（区块 3）会由**原生代码**
请求 `/app-update/manifest.json`，它正好成了"原生层到底有没有跑"的探针。

| 观察 | 结论 |
|---|---|
| 当天 4 次冷启动（22:05:41 / 22:07:33 / 22:07:50 / 22:07:53），每次都有一条 `GET /app-update/manifest.json HTTP/1.1 ... Referer "-"` | 原生层**在跑**（区块 3 的更新检查每次都执行）→ 不是"装错包" |
| `unread_count` 共 296 次，**全部是 `HTTP/2.0` + `Referer: https://8.153.145.175/`** | 这些都是**网页**发的；壳的 `HttpsURLConnection` 只会是 `HTTP/1.1` + 无 Referer |
| **原生发出的 `unread_count` 请求：0 条** | 区块 5 的 `PushHost` 在**第一步就返回了**，未读接口一次都没被调用 |
| 服务器：3 个 uvicorn worker + `REDIS_URL` 已配（channels_redis）、`club` active、nginx 的 `Upgrade` 转发正常、`/ws/messaging/` 有大量 `101` | 服务器侧健康，推送链路可用 → **问题在客户端** |

## 2. 根因

`PushHost` 第一版只在 `onActivityStarted` 里取**一次** Cookie 快照：

```kotlin
override fun onActivityStarted(activity: Activity) {
    cookie = SessionCookies.snapshot(AppConfig.SITE_ORIGIN)   // ← 冷启动时可能拿到 null
    startPush()                                              // ← null 就直接 return
    scheduleRefresh(0)                                       // ← null 又直接 return
}
```

而 **`CookieManager.getCookie()` 在冷启动刚开始时可能返回空**（Cookie 库是异步加载的）。
于是：拿到空 → 连 WS 也不会建、未读接口也不会拉 → **整条链路静默放弃**；
又因为用户一直把 App 停在前台，`onActivityStarted` 不再触发 → **永远不会自己恢复**。
表现就是"完全没反应"，而且不留任何痕迹（这也解释了为什么日志里一条原生请求都没有）。

> 附带教训（已修）：`startPush()` 原来用 `if (socket != null) return` 判重，
> 但连接线程可能已经退出（未登录 / 会话失效 / 连续失败）——那样会**永久连不上**。
> 现在改成看 `socket.isRunning`。

## 3. 修复（本 PR）

1. **看门狗**（主线程，每 15 s）：重新取 Cookie 快照 + 依登录态连/断。
   登录后即使不切后台也会自动把推送拉起来；登出也会自动停并清角标。
2. **兜底刷新**（每 90 s 一次未读接口）：即使 WS 一条事件都没到，**有未读也会亮**。
3. **可观测**（只对 debug 包，集中在 `PushDiagnostics`）：
   - 状态变化时最多弹 3 条 toast；
   - 最新状态**留在通知栏**（独立"推送自检"渠道，`setShowBadge(false)` 不污染角标语义），
     用户可以慢慢看/截屏；
   - 同时写进运行轨迹 `DiagLog`（`filesDir/diag/last-run.txt`，区块 6 引入的设施）；
   - 首次运行发一条**一次性自检通知**：能看到它 = 通知这条路通；看不到 = 系统把通知拦了。
4. **原生请求独立 UA 标记** `NhyzClubShell/<ver> (native; <code>)`：下次从 access.log
   一眼就能分清"网页发的 / 壳发的"，不用再做 HTTP/1.1 + Referer 的推断。
5. **本地编译校验**（工具链，见 §5）：把"依赖 Android 的文件"也纳入本地真编译。

顺便把两个大文件拆开（更内聚、也绕开工具单次推送上限）：
`PushHost`（连接/生命周期）· `PushNotifier`（通知渲染）· `PushDiagnostics`（诊断）。

## 4. 怎么验证修好了（用户侧）

1. 装**本 PR 最新一次运行**产出的 `app-debug-apk`；
2. 打开 App，应在几秒内看到 toast「推送自检：启动：有会话/无会话，连接=…」；
3. 通知栏应出现「推送自检」通知（一次性）与「推送状态（调试）」通知（内容随状态更新）：
   - `启动：无会话` → App 里还没登录（登录后看门狗会自动接上，15 秒内状态变化）；
   - `已连上服务器` → WS 通了；
   - `未读拉取：私信=N，通知=M` → 接口通了，N/M 就是真实未读数；
   - `没有通知权限…` → 系统把通知拦了（去「设置 → 通知」里允许）。
4. 另一台设备发私信 → 5 秒内通知/角标应出现；点通知直达对应会话。

## 5. 工具链副产品：本地也能编译 Android 依赖层了

本机没有 Android SDK，区块 5 的 `PushNotifications` 漏 import 就是这么溜到 CI 才红的。
现在加了：

- `/workspace/androidstub/`：最小 Android/androidx stub（`Notification*`、`Context`、`Handler`、
  `WebSettings`、`CookieManager`、`NotificationCompat`、`R`、`BuildConfig` …）；
- `/workspace/kandroidcheck.sh`：用 stub **真编译** `shell/push/*` + `AppConfig` + `PermissionsPolicy`
  + `MiniJson` + `DiagLog`。

已验证它有效：故意删掉 `PushNotifications` 的 `PermissionsPolicy` import，
本地立刻报出与 CI 一模一样的那条 `unresolved reference`；复原后通过。
（局限：stub 只覆盖我们用到的那部分 API，CI 仍是最终判据；但“未解析引用/类型不匹配/漏 override”这类
错误已经能在推送前拦住。）
