# 区块 5 报告 — 消息与角标

> 分支 `feat/05-messaging-badge` ｜ 基线 `main = 25d6a93` ｜ 完成日期 2026-10-13
> 跨仓库契约：[../PUSH-CONTRACT.md](../PUSH-CONTRACT.md)

---

## 1. 目标与交付物

**目标**：新私信 / 通知能被及时发现（**不要求后台推送**），并把"系统级可见性"补齐 ——
图标角标、系统通知、点通知直达对应会话。

| 交付物 | 实现 |
|---|---|
| 前台 WebSocket 保活 | `shell/push/PushSocket.kt`（TLS + 主机名校验 + 握手校验 + ping 心跳 + 指数退避重连） |
| 协议层（可单测） | `WsFrame.kt`（帧编解码）、`WsHandshake.kt`（握手校验）、`PushProtocol.kt`（事件解析） |
| 未读角标 | `UnreadBadge.kt`（纯逻辑状态机）+ `PushNotifications.kt`（渠道 + 通知 + `setNumber`） |
| 未读计数校准 | `UnreadApi.kt`（`conversations/unread_count/`、`notifications/unread_count/`，地址收口） |
| 点通知直达会话 | PendingIntent 发站内深链 `https://8.153.145.175/#/messages/<id>`，复用现有导航 |
| 生命周期与接线 | `PushHost.kt`（`ActivityLifecycleCallbacks`）+ `ClubApp.kt` 安装（**MainActivity 零改动**） |
| 权限 | 清单声明 `POST_NOTIFICATIONS` + `PermissionsPolicy.POST_NOTIFICATIONS` / `notificationPermission()`；Android 13+ 运行时申请 |
| 资源 | `res/drawable/ic_stat_club.xml`（单色通知小图标，纯矢量）、`strings.xml` 6 条文案 |
| 静态预检 | `scripts/preflight-push.sh`（7 组规则）+ `scripts/preflight-feel.sh` 连带执行同目录脚本 |
| 单测 | 5 个测试文件、**74 个新用例**（合计 189 个用例全绿） |

**新增依赖：0**（沿用 `androidx.core` / `appcompat`，见 ADR-A05）。

---

## 2. 关键设计与取舍

### 2.1 ADR-A11：不做后台保活，只做"前台连接 + 系统通知驱动角标"

- **前台保活**：App 在前台时维持一条 WSS；`onActivityStopped`（完全退到后台）立即断开。
  理由：常驻后台连接需要前台服务（常驻通知 + 耗电 + 国内 ROM 清理），与本项目
  "3 M 带宽、2 G 内存服务器、学生手机"的约束都不划算。ROADMAP 区块 5 的边界即此。
- **角标靠通知驱动**：Android 8.0 起没有"直接设置数字角标"的公开 API，launcher 的数字来自
  **活动的通知**。因此壳发一条**固定 id、`IMPORTANCE_LOW`、静音、无横幅、
  `setShowBadge(true)`** 的通知，"新消息来了"是原地更新同一条，不堆一屏、不打扰。
- **没权限就不装样子**：Android 13+ 用户拒绝通知权限时，壳不发通知、也不显示角标
  （`PushNotifications.canPost()`），网页内的未读徽标照常工作。

### 2.2 ADR-A12：壳自己连 WS（而不是让网页把未读数"告诉"壳）

三种可选数据通道，最后选了"壳自建原生 WebSocket"：

| 方案 | 结果 |
|---|---|
| **壳自建 WSS（采用）** | 与网站仓库**零耦合**（不改网站代码即可上线）；不依赖网页 DOM 结构；不引入 `androidx.webkit` 的 `addWebMessageListener`（需 WebView 88+，老设备会降级）；代价是自写帧/握手（已用 74 个单测钉死） |
| 读网页 DOM 里的徽标 | 依赖网页选择器，UI 一改就断；且只能拿到"数字"，拿不到会话 id |
| 网页 → 壳消息桥 | 需要网站侧改动 + 新依赖 + 老设备降级，跨仓库复杂度更高 |

实测结论（2026-10-13）：`dm` 事件的 payload **自带 `conversation_id`**，
所以"点通知直达会话"也**不需要网站侧配合**——本区块是纯 app 仓库改动。

### 2.3 为什么不动 `MainActivity.kt`

它已 30 KB+（区块 2~4 的接线都在里面），而本块的逻辑自成一体。改法：

- 生命周期：`Application.ActivityLifecycleCallbacks`（**不引入 lifecycle-process**，遵守 A05）；
- 通知点击：PendingIntent 发**普通站内深链**，复用 MainActivity 现成的
  `ACTION_VIEW → NavPolicy → load(url)` 链路（`singleTask` 保证只复用同一实例）。

好处：MainActivity 一处不改 → 回归面为零；坏处：与"只有一个 Activity"的事实耦合（已写在类注释里）。

### 2.4 线程与安全红线（延续 2026-10-12 的教训）

- `CookieManager` / `WebSettings` **只在主线程**取快照（`SessionCookies`），I/O 线程只拿字符串；
  `preflight-push.sh` 第 6 组规则禁止 push 目录自己碰 `CookieManager` / WebView；
- TLS：`SSLSocket` 显式开 `endpointIdentificationAlgorithm=HTTPS`（只验链不验主机 = 给中间人留门）；
  信任锚与网页同一条（`network_security_config`，含 Sectigo R46 额外锚）；
- 握手必须校验 `Sec-WebSocket-Accept`（不把代理/拦截页当成功）；
- 帧与消息都有大小上限（`MAX_PAYLOAD_BYTES` / `MAX_EVENT_BYTES`），超限即断连；
- 未读接口地址收口（只允许本站 origin 前缀）。

### 2.5 本块**没有**加 `app-manifest.json` 开关（有意）

通知的"总开关"由系统权限承担（用户可随时在系统设置里关闭，效果等于远程关掉）。
若日后需要"远程一键关推送"，建议放在区块 6 一起做：给 `UpdateController` 的 flags 加一个
共享的只读快照（当前 flags 由 MainActivity 传给 FeelHost，PushHost 拿不到，硬接会引入耦合）。

---

## 3. 契约

全部细节见 [../PUSH-CONTRACT.md](../PUSH-CONTRACT.md)：连接与鉴权、三类事件与 payload、
两个未读接口、深链路由、壳的行为约定、变更检查表。

---

## 4. 本地验证（推送前全部跑过）

| 检查 | 结果 |
|---|---|
| `bash scripts/preflight.sh` | ✅ 全绿（资源总数 636） |
| `bash scripts/preflight-feel.sh`（含连带 `preflight-push.sh`） | ✅ 全绿 |
| `/workspace/ktcheck.py`（括号/字符串/注释配平） | ✅ 69 个文件通过 |
| 本地 JDK + kotlinc + JUnit4 真实编译并跑单测 | ✅ **189 个用例全通过**（区块 4 基线 115，本块 +74） |
| 已推内容 vs 本地逐字比对 | ✅ 全部一致（含两次修正） |

### 4.1 反向注入验证（证明新预检规则不是摆设）

对 `preflight-push.sh` 逐条注入违规，8/8 全部被拦住：

`ws://` 明文 · 跳过 `Sec-WebSocket-Accept` 校验 · `setShowBadge(false)` ·
去掉 `FLAG_IMMUTABLE` · push 目录乱用 `CookieManager` · 客户端帧不掩码 ·
清单缺 `POST_NOTIFICATIONS` · 不申请通知权限。

### 4.2 服务器侧前置条件核验（只读探测，未改动生产）

- nginx `location /` 已有 `proxy_http_version 1.1` + `Upgrade/Connection` 转发 → WSS 可穿；
- 真实握手（HTTP/1.1）返回 **403**（未登录被 consumer 拒绝）→ 链路通、鉴权生效；
- `club` 服务 active。**未做任何更改、未跑测试。**

---

## 5. 真机验收清单（需要你操作）

| # | 步骤 | 预期 |
|---|---|---|
| 1 | 首次进入 App（Android 13+） | 约 4 秒后弹一次通知权限询问（只弹一次） |
| 2 | 允许通知后，从另一台设备给本账号发私信 | **5 秒内**：网页内徽标变化 + 通知栏出现/更新"你有 N 条未读私信"（静音、无横幅）+ 图标角标出现数字（视 launcher） |
| 3 | 点该通知 | 直接进入对应会话 `/#/messages/<id>`，且该会话随后被标已读 |
| 4 | 回 App 后（或点完通知） | 未读通知/角标随之下调或消失（HTTP 校准） |
| 5 | 连发 3 条私信 | 通知**始终是同一条**（`setNumber` 递增），不堆 3 条 |
| 6 | 拒绝通知权限（Android 13+） | 不弹通知、无角标；网页内徽标正常；**不再反复弹窗** |
| 7 | 把 App 切到后台 | 连接断开（通知不再新增）；回到前台自动重连（服务器 access.log 可见新的 `/ws/messaging/` 握手） |
| 8 | 在另一台设备登录同一账号（挤号） | 本机推送停止、角标清空（不重连、不刷屏） |
| 9 | 断网 30 秒再恢复 | 自动重连（退避 1 s→30 s），期间无崩溃、无 toast 轰炸 |
| 10 | 登出账号 | 角标与通知清空 |

> 第 7 条若想核对：服务器 `tail -f /var/log/nginx/access.log | grep ws/messaging`，
> UA 里能看到 `NhyzClubApp/…`。

---

## 6. 需要你确认 / 注意

1. **通知权限**：Android 13+ 会弹一次询问（在进 App 约 4 秒后）。这是本块唯一新增的运行时权限。
2. **无远程开关**：见 2.5。若你希望"远程一键关推送"，我下一块补。
3. **证书**：站点证书 **2026-11-26 到期**。换证/换链时要同步复核
   `network_security_config.xml` 的额外信任锚（App 侧）与 `PUSH-CONTRACT.md` 第 6 节。
4. **文档同步的客观限制**（工具链）：`docs/ARCHITECTURE.md`（14 KB）与
   `docs/ROADMAP.md`（17 KB）超出单次推送上限（约 1 万字符），本 PR 内**未能改写**。
   契约已完整写进 `docs/PUSH-CONTRACT.md`；下面给出可直接粘贴的片段。
   > ⚠️ **2026-10-14 更新**：该"单次写入/推送上限"**已解除**（实测 2 MiB 二进制一次推送、
   > 1.1 万+ 字符文档一次写入均无损），长文档不再需要规避或走临时通道。
   > 详见 [`../ENGINEERING-ENV.md`](../ENGINEERING-ENV.md) §1。

### 6.1 可粘贴片段（ARCHITECTURE.md 的 ADR 表，追加两行）

```markdown
| **A11** | 推送只在前台保活，角标由"固定 id 的静音通知"驱动 | 后台常驻连接需要前台服务（常驻通知 + 耗电 + 国内 ROM 清理），与 3 M 带宽/2 G 内存/学生手机的约束不划算。Android 8+ 没有"设置数字角标"的公开 API，launcher 数字来自活动通知，故用一条 `IMPORTANCE_LOW` + `setShowBadge(true)` + `setNumber(未读数)` 的通知驱动。见 chunks/CHUNK-05-REPORT.md 2.1。 |
| **A12** | 壳自建原生 WebSocket，而非读网页 DOM / 走 WebMessage 桥 | 与网站仓库零耦合、不依赖网页选择器、不引入 `addWebMessageListener`（老设备会降级）；代价是自写帧与握手，已用 74 个单测钉死。实测 `dm` 事件自带 `conversation_id`，连"点通知直达会话"也无需网站侧配合。见 PUSH-CONTRACT.md。 |
```

### 6.2 可粘贴片段（ROADMAP.md 的区块 5 小节，替换原文）

```markdown
## 区块 5 — 消息与角标（✅ 代码完成，待真机验收 2026-10-13）

- **目标**：新私信/通知能被及时发现（不要求后台推送）。
- **交付物**（实际落地）：前台 WSS 保活（`shell/push/PushSocket.kt`，自写帧+握手+心跳+退避）、
  未读角标（`UnreadBadge.kt` + 由 `IMPORTANCE_LOW` 静音通知驱动的 launcher 角标）、
  未读计数 HTTP 校准（`UnreadApi.kt`）、点通知直达会话（站内深链 `/#/messages/:id`）、
  通知权限（Android 13+ 运行时申请）、专项预检 `scripts/preflight-push.sh`。
- **边界**：不在本块做常驻后台保活（耗电 + 国内推送生态），留待后续评估。
- **状态**：代码/单测（189 用例）/静态预检完成，**纯 app 仓库改动**（未改网站仓库）；
  跨仓库约定见 `docs/PUSH-CONTRACT.md`；待管理员真机验收（见 CHUNK-05 报告第 5 节的 10 条）。
```

### 6.3 可粘贴片段（FEATURE-MATRIX.md）

```markdown
| 私信 / 通知的实时提醒 | ✅ 实测通过（2026-10-__，机型：____） | App 前台：5 秒内网页徽标 + 静音通知 + 图标角标；点通知直达会话 |
```

---

## 7. 未做 / 后续

- 后台保活 / 真推送（厂商推送 SDK）：不在本块；
- 远程开关（见 2.5）；会话列表页的壳内红点（网页已有，不重复做）；
- 通知聚合细节（"谁发来的"文案里带昵称）：需要网站侧在 payload 里加昵称，
  当前只用条数（`payload` 里没有昵称字段，见 PUSH-CONTRACT 第 2 节）。

---

## 8. 推送批次说明（为什么是 10 次）

GitHub MCP 单次调用的载荷上限约 1 万字符，而本块源码/文档约 60 KB，无法"一次推完"。
因此按"**每一批单独看都是可编译、可运行、CI 必绿**"的顺序拆批（协议层 → 测试 → 通知/配置
→ 清单/文案 → 连接器 → 接线 → 预检 → 文档），**没有产生任何红色 run**；
CI 的 `concurrency.cancel-in-progress` 也会把前一批仍在跑的 run 取消掉，不会刷屏。

> ⚠️ **2026-10-14 更新**：上述"约 1 万字符上限"**已解除**。本节描述的是历史做法，
> 现在**不必**再为了体积拆批；但"每批单独可编译、CI 必绿"的纪律依然有效。
> 详见 [`../ENGINEERING-ENV.md`](../ENGINEERING-ENV.md) §1。
