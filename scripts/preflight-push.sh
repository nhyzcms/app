#!/usr/bin/env bash
# 区块 5（消息与角标）专项静态预检 —— 只需 bash + grep/python3，与 preflight.sh 同风格。
#
# 为什么独立成脚本：preflight.sh 已经很长（区块 1~3 的规则），区块 5 的规则只关心
# shell/push/ 这一小块（WebSocket 协议、通知、权限、线程红线），独立成文件好读也好查。
# 它由 scripts/preflight-feel.sh 连带执行（CI 无需改工作流）。
#
# 用法： bash scripts/preflight-push.sh
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
fail=0
err() { echo "[预检-push][失败] $*"; fail=1; }

PUSH_DIR="app/src/main/java/com/nhyzcms/clubapp/shell/push"

# 只看“非注释行”，避免我们自己的警示注释触发规则（同 preflight.sh 的做法）。
code_grep() {
  local pattern="$1"
  local path="${2:-$PUSH_DIR}"
  grep -rn --include='*.kt' -E "$pattern" "$path" 2>/dev/null \
    | grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*)' \
    || true
}

if [ ! -d "$PUSH_DIR" ]; then
  echo "[预检-push] 未发现 $PUSH_DIR，跳过"
  exit 0
fi

# ── 1. 传输层：只允许 wss（明文 ws 会被 network-security-config 与导航策略双重拦截）──
if [ -n "$(code_grep 'ws://')" ]; then
  err "推送代码里出现明文 ws:// 地址：必须走 wss（站点已全量 HTTPS + HSTS）"
fi

# ── 2. 握手必须校验 Sec-WebSocket-Accept ──────────────────────────────
if [ ! -f "$PUSH_DIR/WsHandshake.kt" ]; then
  err "缺少 WsHandshake.kt：握手校验（101 / Upgrade / Sec-WebSocket-Accept）不得省"
else
  grep -q 'acceptFor' "$PUSH_DIR/WsHandshake.kt" \
    || err "WsHandshake 必须用 acceptFor(...) 校验 Sec-WebSocket-Accept，否则代理响应当成握手成功"
  grep -q 'sec-websocket-accept' "$PUSH_DIR/WsHandshake.kt" \
    || err "WsHandshake 必须解析 sec-websocket-accept 头"
  if [ -f "$PUSH_DIR/PushSocket.kt" ]; then
    grep -q 'WsHandshake.verify(' "$PUSH_DIR/PushSocket.kt" \
      || err "PushSocket 必须调用 WsHandshake.verify(...)，不得只看 HTTP 状态码"
    grep -q 'Origin:' "$PUSH_DIR/WsHandshake.kt" \
      || err "握手必须带 Origin 头（站点用 AllowedHostsOriginValidator 校验，缺了会 403）"
  fi
fi

# ── 3. 客户端帧必须置掩码位（RFC 6455：客户端不掩码会被服务端直接断连）──
if [ -f "$PUSH_DIR/WsFrame.kt" ]; then
  grep -qE '0x80 or len' "$PUSH_DIR/WsFrame.kt" \
    || err "WsFrame.encode 必须置掩码位（长度字节按 0x80 or 处理），否则服务端会立刻断连"
fi

# ── 4. 通知：必须能驱动角标、必须静音、必须 FLAG_IMMUTABLE ─────────────
NOTIF="$PUSH_DIR/PushNotifications.kt"
if [ -f "$NOTIF" ]; then
  grep -q 'setShowBadge(true)' "$NOTIF" \
    || err "通知渠道必须 setShowBadge(true)，否则 launcher 不显示数字角标"
  grep -q 'IMPORTANCE_LOW' "$NOTIF" \
    || err "未读通知必须用 IMPORTANCE_LOW（静音、无横幅），否则前台看页面时会被横幅打扰"
  grep -q 'FLAG_IMMUTABLE' "$NOTIF" \
    || err "PendingIntent 必须声明 FLAG_IMMUTABLE（Android 12+ 强制要求显式声明可变性）"
  grep -q 'ic_stat_club' "$NOTIF" \
    || err "通知必须用单色小图标 R.drawable.ic_stat_club"
  grep -q 'canPost' "$NOTIF" \
    || err "必须暴露 canPost()（Android 13+ 没权限时不发通知，不要装作有角标）"
else
  err "缺少 PushNotifications.kt"
fi

# ── 5. 权限：清单声明 + 策略常量 + 运行时申请，三处齐全 ─────────────
MANIFEST="app/src/main/AndroidManifest.xml"
grep -q 'android.permission.POST_NOTIFICATIONS' "$MANIFEST" \
  || err "AndroidManifest 必须声明 POST_NOTIFICATIONS"
grep -q 'POST_NOTIFICATIONS' "app/src/main/java/com/nhyzcms/clubapp/shell/PermissionsPolicy.kt" \
  || err "PermissionsPolicy 必须有 POST_NOTIFICATIONS 常量（权限唯一真源）"
if [ -f "$PUSH_DIR/PushHost.kt" ]; then
  grep -q 'requestPermissions' "$PUSH_DIR/PushHost.kt" \
    || err "Android 13+ 必须在运行时申请通知权限（ActivityCompat.requestPermissions）"
fi

# ── 6. 线程红线：push 目录不得自己碰 WebView / CookieManager ───────────
#    CookieManager 属于 WebView，只能在主线程访问；统一走 SessionCookies.snapshot 取快照
#    （SessionCookies.kt 就是那个唯一允许碰 CookieManager 的地方，规则里把它排除）。
if [ -n "$(code_grep 'CookieManager' | grep -v 'SessionCookies\.kt')" ]; then
  err "push 目录只有 SessionCookies.kt 可以碰 CookieManager（其余一律经 SessionCookies.snapshot 取快照）"
fi
if [ -n "$(code_grep 'webView|WebView\b')" ]; then
  err "push 目录不得持有或访问 WebView 实例（后台线程访问会直接抛 RuntimeException）"
fi

# ── 7. 未读接口必须地址收口（不得直接 new URL 任意地址）───────────────
if [ -n "$(code_grep 'UnreadApi\.fetch\(' )" ]; then
  grep -rq 'AppConfig.SITE_ORIGIN' "$PUSH_DIR/PushHost.kt" \
    || err "调用 UnreadApi.fetch 必须传 AppConfig.SITE_ORIGIN 作为允许前缀（地址收口）"
fi

if [ "$fail" -eq 0 ]; then
  echo "[预检-push] 全部通过 ✅"
else
  echo "[预检-push] 存在失败项 ❌"
fi
exit "$fail"
