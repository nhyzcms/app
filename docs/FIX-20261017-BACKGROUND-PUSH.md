# 故障与补强：退到后台完全收不到提醒（2026-10-17）

> 关联：区块 5（消息与角标）· PR #25 · 上一份故障报告 [FIX-20261013-PUSH-NOT-FIRING.md](FIX-20261013-PUSH-NOT-FIRING.md)

## 1. 现象（管理员真机反馈）

- 有人发私信 / 产生通知时，App **没有任何实时提醒**：没有通知、没有声音、没有角标；
- 只有主动打开「私信」页，才看到新消息已经在列表里；
- 站内（手机版 `/#/m`）也没有从首页到私信/通知页的**红点/数字引导**；
- 深色模式在 App 里**没有切换按钮**，也**不跟随系统**。

## 2. 根因（前两条是 App 侧的，后两条在网站侧）

| # | 根因 | 位置 |
|---|---|---|
| 1 | **退到后台就主动断连**：`onActivityStopped` 里 `stopWatchdog()` + `stopPush("退到后台")` | `shell/push/PushHost.kt` |
| 2 | 断连后没有任何恢复路径：用户停在前台/后台时 `onActivityStarted` 不再触发，只能等下次打开 App | 同上 |
| 3 | 手机版 `/m` 不用桌面 `AppShell`，而**未读徽标只存在于 AppShell** | 网站 `frontend/src/components/AppShell.tsx` |
| 4 | 手机版没有任何主题入口；`getTheme()` 无偏好时默认 `light`，永远跟不上系统 | 网站 `frontend/src/theme.ts` / `pages/MobileMePage.tsx` |

> 注意：PR #19 修的是「冷启动时 Cookie 还没就绪 → 第一条连接直接 return」，
> 与本条**不是同一个问题**。本次是「即使连上了，一退到后台就被我们自己断掉」。

## 3. 修复（App 侧，PR #25）

### 3.1 常驻前台服务

新增 `shell/push/PushService.kt`：

- 登录后（`PushHost.syncLoginState()` 看到已登录）即启动；
- `onCreate` 里立刻 `startForeground(...)`（Android 12+ 要求 5 秒内进前台）；
- `onStartCommand` 返回 `START_STICKY`，`stopWithTask=false`（清单）—— 进程被系统杀掉/被划掉任务后由系统重启；
- 重启时 `ClubApp.onCreate` 会重建 `PushHost`，`onServiceStarted()` 把 Cookie 快照、登录态、看门狗重新接上；
- 服务通知走**独立渠道** `AppConfig.SERVICE_CHANNEL_ID`（`IMPORTANCE_MIN` + `setShowBadge(false)`），
  绝不复用「新消息」渠道 —— 否则 launcher 的数字角标会被这条常驻通知污染、永远清不掉。

`PushHost.onActivityStopped` **只清 Activity 引用，不再停看门狗/连接**；退出登录或被挤号时才 `stopService`。

### 3.2 省电与权限

- 新增权限：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；
- 登录后 6 秒弹一次说明框 → 拉起系统「忽略电池优化」对话框（只问一次，记在 `SharedPreferences("push")`）；
- 后台的兜底刷新从 90 s 放慢到 5 min（`SAFETY_REFRESH_TICKS_BACKGROUND`）：WS 通着就不白拉接口，
  也让 2 核 / 2 GB 的服务器少一点无谓请求。

### 3.3 站内红点即时刷新

- `PushHost` 新增 `onUnreadChanged` 回调，未读数变化时在主线程触发；
- `MainActivity.bindPushBridge()` 注册它，用 `evaluateJavascript` 派发**一个常量字符串**：
  `window.dispatchEvent(new Event('club:unread'))`；
- 不做 `addJavascriptInterface`（安全文档 §4 禁止），也不拼接任何变量；
- 网页侧（网站仓库 PR #157）监听该事件立即重拉未读计数。

### 3.4 静态预检

`scripts/preflight-push.sh` 新增第 10 组规则：

- `PushService` 必须 `startForeground` / `START_STICKY`；
- 清单必须有 `foregroundServiceType="dataSync"` 与 `FOREGROUND_SERVICE_DATA_SYNC`；
- **`onActivityStopped` 里不得出现 `stopPush(`**（本次故障的根因，防止回退）；
- 用了 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 就必须声明对应权限。

## 4. 真机验收（10 条）

1. 用 Release 包登录 → 通知栏出现常驻的「消息提醒已开启」（系统通知设置里能看到「消息提醒」渠道，静音、无角标）；
2. 首次登录 6 秒后弹「允许后台运行」→ 点是 → 系统对话框选「允许」（`Settings → 电池 → 不受限制`）；
3. 退到桌面（不要划掉任务），另一台设备/账号发一条私信 → **10 秒内**收到通知；
4. 锁屏状态下再发一条 → 同样收到；
5. 通知栏的未读通知带数字，桌面图标的数字角标同步（取决于 launcher 是否支持）；
6. 点通知 → 直接进入对应会话，且该会话已标记已读、通知与角标清零；
7. 回到 App，手机版首页立即出现「x 条未读私信 · y 条未读通知」提示条（无需等 30 s）；
8. 底部「我的」tab 有数字红点，「我的」页私信/通知右侧分别是各自条数；
9. 从最近任务划掉 App（`stopWithTask=false`）后再发私信 → 服务被系统重启后仍能收到（部分 ROM 需要 30~60 s）；
10. 退出登录 → 常驻通知消失，服务停止，不再收到任何提醒。

> 观察手段：服务器 `access.log` 里原生请求带 `NhyzClubShell/<version> (native; <code>)` 标记；
> debug 包的「推送状态（调试）」通知会显示未读拉取结果。

## 5. 已知限制（无法用代码绕过的）

- **国产 ROM 的后台管控**（MIUI / EMUI / ColorOS / OriginOS 等）可能仍然杀进程。需要在系统设置里
  给本应用开「自启动 / 后台运行 / 不受限制」，这一步只能由用户手动做，App 无法代劳；
- 没有接入厂商推送（小米/华为/OPPO 等）与 FCM：国内无 Google 服务，FCM 不可用；
  厂商推送需要在各家后台登记应用并引入 SDK，会显著增大 APK 与维护成本，当前**不做**；
- 一个前台服务 + 一条 WS 连接会带来额外耗电（约每小时 1~3% 视网络而定）。
  如果不想要，可以在系统通知里关掉「消息提醒」渠道 —— 但那时后台提醒也就不再可靠。
- 网站侧红点在浏览器里仍是 30 s 轮询的「准实时」；App 内因为原生事件桥才是即时的。

## 6. 相关链接

- 网站侧配套：[`nhyzcms/club-management-website` PR #157](https://github.com/nhyzcms/club-management-website/pull/157)
- 连接契约：[PUSH-CONTRACT.md](PUSH-CONTRACT.md)
- 上一份推送故障：[FIX-20261013-PUSH-NOT-FIRING.md](FIX-20261013-PUSH-NOT-FIRING.md)
