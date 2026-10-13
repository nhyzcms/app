# 推送契约（PUSH-CONTRACT）

> 本文件是**壳侧推送（区块 5）与网站侧的唯一跨仓库约定**。
> 事实来源：网站仓库 `nhyzcms/club-management-website`（下称"网站仓库"）的
> `config/asgi.py`、`messaging/routing.py`、`messaging/consumers.py`、`messaging/services.py`、
> `messaging/notifications/dispatch.py`、`frontend/src/api/messagingSocket.ts`、
> `frontend/src/api/messaging.ts`。**任何一边改动都要同步本文件。**
>
> 站点侧设计文档：网站仓库 `docs/adr/0015-channels-without-redis.md`。

---

## 1. 连接

| 项 | 值 |
|---|---|
| 地址 | `wss://8.153.145.175/ws/messaging/` |
| 端口 | 443（WSS 默认，壳写在 `AppConfig.WS_PORT`） |
| 鉴权 | **会话 Cookie**（`sessionid`）——服务端 `AuthMiddlewareStack` |
| 必需的 `Origin` 头 | `https://8.153.145.175`（服务端 `AllowedHostsOriginValidator` 校验，缺失/不匹配 → 403） |
| 其它必需头 | `Host: 8.153.145.175`、`Upgrade: websocket`、`Connection: Upgrade`、`Sec-WebSocket-Key`、`Sec-WebSocket-Version: 13` |
| 建议带 | `User-Agent`：网页默认 UA + `NhyzClubApp/<versionName> (Android; <versionCode>)`（站识别 App 用，见 ARCHITECTURE 第 5.1 节） |
| 握手校验 | 必须校验响应 `101` + `Sec-WebSocket-Accept == base64(sha1(key + 258EAFA5-E914-47DA-95CA-C5AB0DC85B11))` |
| 心跳 | 壳在空闲时发 ping（服务端底层 websockets 会自动回 pong） |

**代理层**：nginx 的 `location /` 已配 `proxy_http_version 1.1` + `Upgrade $http_upgrade` +
`Connection $connection_upgrade`，因此 WSS 能穿过 nginx 到 ASGI（daphne/uvicorn）。
2026-10-13 在生产上实测：**未登录时握手返回 `HTTP/1.1 403`**（consumer 在 `connect()` 里
`close()`）——这正是"链路通、鉴权生效"的证据。

---

## 2. 事件（服务端 → 壳）

服务端只在**推送**通道上发这几类事件，形态固定：

```json
{"event": "dm" | "notification" | "comment", "payload": { ... }}
```

| event | 触发点（网站仓库） | payload 字段 | 壳的处理 |
|---|---|---|---|
| `dm` | `services.send_dm()` / `services.retract_dm()` → `push_user(other, "dm", …)` | `conversation_id`、`message_id`、可选 `retracted: true` | 未读私信 +1（撤回不算）；记录会话 id 供点通知直达 |
| `notification` | `notifications/dispatch.py::_push()` | `notification_id`、`category`、`event` | 未读通知 +1（随后以 HTTP 校准） |
| `comment` | `services.post_comment()` / `retract_comment()` / `delete_comment()` → `push_thread()` | `thread_id`、`comment_id`、可选 `retracted`/`deleted` | **壳不处理**（只与当前打开的评论区有关，网页自己刷） |

约定（同一份 ADR-0015）：

1. socket **只提示"有新东西"**，不推业务数据本体；历史与计数一律走 HTTP；
2. 不推输入状态 / 已读回执 / 在线状态；
3. **没有 `unread` 事件**——网页里的 `unread` 是前端本地事件（`emitMessagingEvent`），
   壳不要等它；
4. 未知事件必须被忽略（服务端将来加事件，旧壳不该出错）。

---

## 3. 未读计数（HTTP）

| 用途 | 方法与路径 | 响应 |
|---|---|---|
| 未读私信条数 | `GET /messaging/conversations/unread_count/` | `{"total": N}` |
| 未读通知条数 | `GET /messaging/notifications/unread_count/` | `{"total": N}` |

- 需要登录态（Cookie）；未登录 / 被挤号 → **401**（`reason: session_superseded`）；
- API 前缀就是 `/messaging/`（不是 `/api/messaging/`，见 `config/urls.py`）；
- 壳只在"收到事件后（节流 2 s）+ 回到前台"这两种时机拉，**不做定时轮询**；
- 壳侧地址收口：只允许 `AppConfig.SITE_ORIGIN` 前缀下的地址（`UnreadApi.isAllowed`）。

---

## 4. 深链（通知点击）

站点是 HashRouter，相关路由已存在：

| 路由 | 页面 |
|---|---|
| `/#/messages` | 私信列表 |
| `/#/messages/:id` | 指定会话（`AppShell` 的 USER_MENU / `MessagePage` 都会导航到它） |
| `/#/notifications` | 通知列表 |

壳的未读通知点击时发的是**普通站内深链** `https://8.153.145.175/#/messages/<id>`：
MainActivity 的 `ACTION_VIEW` → `NavPolicy` 判为同站 → 壳内加载。因此通知点击
**不需要网站侧任何配合**（也就没有"网页要给壳暴露会话 id"这一项——`dm` 事件的 payload 自带）。

---

## 5. 壳的行为约定（不改网站也能成立）

| 场景 | 壳的行为 |
|---|---|
| App 在前台 | 保持一条 WS 连接；退到后台（`onActivityStopped`）**立即断开**（区块 5 边界：不做后台保活） |
| 未登录（无 `sessionid`） | 不连 WS、不发未读请求、不显示角标（登录在网页里做） |
| 收到 401/403 | 视为会话失效：停连 + 清角标 + 清通知，**不重连**（等下次前台再试） |
| 网络抖动 | 指数退避重连（1 s → 30 s，带抖动） |
| 通知 | 一条固定 id 的低优先级静音通知（`IMPORTANCE_LOW` + `setShowBadge(true)`）驱动图标角标；数字取未读总数 |
| 未读数未知时 | 用"未读会话数"兜底，**绝不凭空造数字**；HTTP 校准到位后以接口值为准 |

---

## 6. 变更检查表

改任一侧时，逐项确认：

- [ ] 事件名 / payload 字段变了 → 更新本文件第 2 节 + 壳侧 `PushProtocol`（及 `PushProtocolTest`）；
- [ ] 未读端点变了 → 更新第 3 节 + `AppConfig.DM_UNREAD_URL` / `NOTIFICATION_UNREAD_URL`；
- [ ] WS 路径或鉴权方式变了 → 更新第 1 节 + `AppConfig.WS_PATH`（并复核 `Origin` 头）；
- [ ] 会话路由变了 → 更新第 4 节 + `PushProtocol.routeForConversation`；
- [ ] 换域名 / 换证书链 → 同步 `AppConfig` 的 Origin/Host 白名单与
      `network_security_config.xml` 的额外信任锚（见 `docs/FIX-20261012-CERT-R46.md`）。
