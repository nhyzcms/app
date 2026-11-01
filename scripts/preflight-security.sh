#!/usr/bin/env bash
# 安全对齐专项静态预检（2026-11，对齐网站 2026-10 那批安全审计补丁）。
#
# 为什么独立成脚本：这些规则保护的是"壳里绝对不能退让的几条边界"，
# 与手感/性能无关，挂掉时必须一眼看出是安全问题而不是样式问题。
# 被 preflight-feel.sh 的通配自动带进 CI（scripts/preflight-*.sh）。
#
# 用法： bash scripts/preflight-security.sh
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
fail=0
err() { echo "[预检-安全][失败] $*"; fail=1; }

# 只看“非注释行”，避免我们自己的警示注释触发规则（同 preflight.sh 的做法）。
code_grep() {
  local pattern="$1"
  local path="${2:-app/src/main/java}"
  grep -rn --include='*.kt' -E "$pattern" "$path" 2>/dev/null \
    | grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*)' \
    || true
}

# ── 1. /media 活跃内容的顶层导航闸门不能被摘掉 ─────────────────────────────
#    网站 a092a26 让服务端按真实内容判定上传类型；壳里这道是纵深防御
#    （见 shell/security/ActiveContentPolicy.kt）。
if [ -z "$(code_grep 'ActiveContentPolicy\.blockReason\(')" ]; then
  err "WebShellClient 必须在顶层导航里调用 ActiveContentPolicy.blockReason（/media 下的 html/svg 不得顶层打开）"
fi
if [ -n "$(code_grep 'isForMainFrame' app/src/main/java/com/nhyzcms/clubapp/shell/WebShellClient.kt | grep -c ActiveContentPolicy)" ]; then
  : # 提一下即可，不做强校验（行内同时出现是理想写法，但不是唯一写法）
fi

# ── 2. 网页权限请求必须走 WebPermissionPolicy，禁止整包转交 ────────────────
#    翻车方式：onPermissionRequest 里直接 request.grant(request.resources)，
#    等于"网页要什么给什么"，包括麦克风。
if [ -z "$(code_grep 'WebPermissionPolicy\.decide\(')" ]; then
  err "网页 getUserMedia 的裁决必须走 WebPermissionPolicy.decide（只认本站 Origin + 只给视频采集）"
fi
if [ -n "$(code_grep 'grant\(request\.resources')" ]; then
  err "禁止整包授予网页权限（request.grant(request.resources)）：必须只授予白名单里的资源"
fi
if [ -n "$(code_grep 'RES_AUDIO_CAPTURE' app/src/main/java | grep -v 'WebPermissionPolicy.kt')" ]; then
  err "麦克风（RES_AUDIO_CAPTURE）只允许在 WebPermissionPolicy 里作为拒绝理由出现，不得参与授予"
fi

# ── 3. 限流/拒绝不能变成白屏：主文档 429/403 必须有分类 ─────────────────────
#    网站 2026-10 加了限流（登录、找回密码、公开投票），nginx 的 limit_req 会把
#    主文档直接打成 429；不映射就是一个空白页。
if [ -z "$(code_grep 'onReceivedHttpError')" ]; then
  err "WebShellClient 必须实现 onReceivedHttpError，把主文档的 429/403 映射成可读错误（否则限流时白屏）"
fi
if [ -z "$(code_grep '429')" ]; then
  err "必须显式处理 HTTP 429（服务端限流）"
fi

# ── 4. 安全类错误必须仍然走错误面板，不能静默吞掉 ─────────────────────────
if [ -n "$(code_grep 'handler\.proceed\(')" ]; then
  err "onReceivedSslError 里禁止调用 handler.proceed()（TLS 失败必须显式失败）"
fi

# ── 5. 明文与桥接红线（与 preflight.sh 第 4 节重复也留着：这道闸门单独跑时也要拦住）──
if [ -n "$(code_grep 'addJavascriptInterface')" ]; then
  err "禁止 addJavascriptInterface（SECURITY §4）"
fi

if [ "$fail" -ne 0 ]; then
  echo "[预检-安全] 有规则未通过，见上面的失败项。"
  exit 1
fi
echo "[预检-安全] 全部规则通过。"
