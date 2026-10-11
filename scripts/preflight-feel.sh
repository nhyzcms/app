#!/usr/bin/env bash
# 区块 4（原生手感）专项静态预检 —— 与 preflight.sh 一样只需 bash + grep/python3。
#
# 为什么独立成脚本：preflight.sh 已经很长（区块 1~3 的规则），
# 区块 4 的规则集中在「壳内执行 JS 的边界」与「启动页/下拉刷新的接线一致性」两类，
# 独立成文件后既好读，也便于 CI 单列一步（见 .github/workflows/android.yml）。
#
# 用法： bash scripts/preflight-feel.sh
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
fail=0
err() { echo "[预检-feel][失败] $*"; fail=1; }

# 只看“非注释行”，避免我们自己的警示注释触发规则（同 preflight.sh 的做法）。
code_grep() {
  local pattern="$1"
  local path="${2:-app/src/main/java}"
  grep -rn --include='*.kt' -E "$pattern" "$path" 2>/dev/null \
    | grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*)' \
    || true
}

# ── 1. 壳内执行 JS 的边界（docs/SECURITY.md 第 4.1 节）─────────────────────
#    区块 4 起壳里会执行两段脚本（读 meta theme-color / 滚回顶部）。
#    只允许**写在代码里的常量**：拼接变量等于给网页/远端配置留一个注入口子。
if [ -n "$(code_grep 'evaluateJavascript\([^)]*\+')" ]; then
  err "evaluateJavascript 的脚本必须是常量（检测到字符串拼接），拼接外部字符串等于开放 JS 注入"
fi
if [ -n "$(code_grep 'loadUrl\("[^"]*javascript:')" ]; then
  err '禁止用 loadUrl("javascript:...") 注入脚本，改用常量脚本 + evaluateJavascript'
fi

# ── 2. 启动页三处必须一致（不一致只在真机冷启动时才暴露，代价是白屏）──────
if grep -q '@style/Theme_ClubApp_Splash' app/src/main/AndroidManifest.xml; then
  grep -q 'name="Theme_ClubApp_Splash"' app/src/main/res/values/themes.xml \
    || err "清单引用了 @style/Theme_ClubApp_Splash，但 values/themes.xml 里没有定义"
  grep -q 'splash_background' app/src/main/res/values/themes.xml \
    || err "启动页主题必须用 @drawable/splash_background 作 windowBackground，否则冷启动仍是白屏"
  [ -f app/src/main/res/drawable/splash_background.xml ] \
    || err "缺 app/src/main/res/drawable/splash_background.xml"
fi

# ── 3. 下拉刷新：必须声明「网页已上滚时不响应下拉」 ────────────────────────
if grep -q 'SwipeRefreshLayout' app/src/main/res/layout/activity_main.xml; then
  grep -rq 'setOnChildScrollUpCallback' app/src/main/java/com/nhyzcms/clubapp \
    || err "用了 SwipeRefreshLayout 就必须设 setOnChildScrollUpCallback，否则网页中部下滑会误触发刷新"
  grep -q 'ScrollAwareWebView' app/src/main/res/layout/activity_main.xml \
    || err "下拉刷新必须搭配 ScrollAwareWebView，它提供同步的滚动位置供手势判断"
fi

if [ "$fail" -eq 0 ]; then
  echo "[预检-feel] 全部通过 ✅"
else
  echo "[预检-feel] 存在失败项 ❌"
fi
exit "$fail"
