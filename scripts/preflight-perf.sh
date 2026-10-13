#!/usr/bin/env bash
# 区块 6（弱网与性能）专项静态预检 —— 只需 bash + grep。
#
# 为什么独立成脚本：见 scripts/preflight-feel.sh 末尾的"连带执行"约定 ——
# CI 只显式调用 preflight.sh 与 preflight-feel.sh，新脚本命名为 preflight-*.sh 就会被跑到。
#
# 本文件把区块 6 的**教训**固化成规则（都是"改回去也不会报错、但会悄悄退化"的东西）：
#   ① 资源拦截器不许做网络 I/O、不许碰 WebView/CookieManager（它跑在后台线程）；
#   ② 主文档永远不能被拦（拦了就是白屏）；
#   ③ 拦截白名单只能来自 AppConfig（不许在拦截器里写死路径）；
#   ④ 拦截应答只能是"空体 200"（不要伪造内容、不要制造 3xx/4xx）；
#   ⑤ clearCache 只能出现在两个被审过的位置，且必须走 CacheTrimPolicy 的时机判定；
#   ⑥ 探测间隔不得低于 1500 ms（降频不能被改回去）；
#   ⑦ 举诊断必须配撤销轮询（否则又会出现"浮层一直挂着"的误报）；
#   ⑧ 新开关键名必须同时写进代码与文档。
#
# 用法： bash scripts/preflight-perf.sh
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
fail=0
err() { echo "[预检-perf][失败] $*"; fail=1; }

# 只看"非注释行"，避免我们自己的警示注释触发规则（同 preflight.sh / preflight-feel.sh）。
code_grep() {
  local pattern="$1"
  local path="${2:-app/src/main/java}"
  grep -rn --include='*.kt' -E "$pattern" "$path" 2>/dev/null \
    | grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*)' \
    || true
}

PERF_DIR=app/src/main/java/com/nhyzcms/clubapp/shell/perf
FEEL_HOST=app/src/main/java/com/nhyzcms/clubapp/shell/feel/FeelHost.kt
PROBE=app/src/main/java/com/nhyzcms/clubapp/shell/feel/PageStateProbe.kt

# ── 1. 拦截器跑在后台线程：不许网络 I/O、不许碰 WebView 配置与 Cookie ─────────
if [ -n "$(code_grep 'openConnection\(|HttpURLConnection|java\.net\.URL|new Socket\(' "$PERF_DIR")" ]; then
  err "shell/perf 里禁止网络 I/O：shouldInterceptRequest 由 WebView 在后台线程同步调用，必须立即返回"
fi
if [ -n "$(code_grep 'CookieManager|settings\.|evaluateJavascript\(' "$PERF_DIR")" ]; then
  err "shell/perf 禁止碰 CookieManager / WebSettings / evaluateJavascript（只在主线程可用，见 2026-10-12 闪退事故）"
fi

# ── 2. 主文档永远不拦 ────────────────────────────────────────────────────────
#    用结构化判定而不是"文件里有没有出现过 isForMainFrame"——
#    WebShellClient 的 shouldOverrideUrlLoading 里也有同名判断，
#    只按字符串存在性检查会被它蒙混过关（反向注入验证时就是这样漏掉的）。
if command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY' || fail=1
import pathlib
import re
import sys

failed = False

client = pathlib.Path("app/src/main/java/com/nhyzcms/clubapp/shell/WebShellClient.kt")
text = client.read_text(encoding="utf-8")
start = text.find("shouldInterceptRequest")
if start >= 0:
    nxt = text.find("override fun", start + 1)
    body = text[start : nxt if nxt > 0 else len(text)]
    if "isForMainFrame" not in body:
        print("[预检-perf][失败] WebShellClient.shouldInterceptRequest 里必须先判 isForMainFrame（主文档被拦＝白屏）")
        failed = True

policy = pathlib.Path("app/src/main/java/com/nhyzcms/clubapp/shell/perf/HeavyAssetPolicy.kt")
ptext = policy.read_text(encoding="utf-8")
pstart = ptext.find("fun shouldBlock")
if pstart < 0:
    print("[预检-perf][失败] HeavyAssetPolicy 里找不到 shouldBlock")
    failed = True
else:
    pend = ptext.find("\n    }", pstart)
    pbody = ptext[pstart : pend if pend > 0 else len(ptext)]
    if "forMainFrame" not in pbody:
        print("[预检-perf][失败] HeavyAssetPolicy.shouldBlock 必须接受 forMainFrame 参数")
        failed = True
    elif not re.search(r"if\s*\([^)]*forMainFrame[^)]*\)\s*return false", pbody):
        print("[预检-perf][失败] HeavyAssetPolicy.shouldBlock 必须在 forMainFrame 为真时直接返回 false")
        failed = True

sys.exit(1 if failed else 0)
PY
fi

# ── 3. 白名单唯一真源在 AppConfig（不许在拦截器里写死路径）──────────────────
if [ -n "$(code_grep '"/static|"/media|"/app-update' "$PERF_DIR")" ]; then
  err "拦截白名单只能来自 AppConfig（BLOCKED_ASSET_PREFIXES），不要在 shell/perf 里写死站点路径"
fi
grep -q 'BLOCKED_ASSET_PREFIXES' app/src/main/java/com/nhyzcms/clubapp/shell/AppConfig.kt \
  || err "AppConfig 缺少 BLOCKED_ASSET_PREFIXES（大资源白名单的唯一真源）"
grep -q 'BLOCKED_ASSET_PREFIXES' "$PERF_DIR/PerfHost.kt" \
  || err "PerfHost 必须从 AppConfig 取白名单，不许自己拼路径"

# ── 4. 拦截应答只能是空体 200 ───────────────────────────────────────────────
#    状态码单独成行（WebResourceResponse 的第三个参数），所以按行匹配；
#    注意不能只 grep "有没有 302" —— 只按存在性检查会被 `"utf-8",\n 302,` 这种跨行写法绕过。
if grep -rq 'WebResourceResponse(' "$PERF_DIR" 2>/dev/null; then
  grep -rq 'ByteArrayInputStream(' "$PERF_DIR" \
    || err "拦截应答必须是空体（ByteArrayInputStream 0 字节）：伪造内容会让网页状态错乱"
  if command -v python3 >/dev/null 2>&1; then
    python3 - <<'PY' || fail=1
import pathlib
import re
import sys

failed = False
for path in sorted(pathlib.Path("app/src/main/java/com/nhyzcms/clubapp/shell/perf").rglob("*.kt")):
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if re.match(r"^\s*(//|\*|/\*)", line):
            continue
        hit = re.match(r"^\s*(\d{3}),\s*$", line)
        if hit and hit.group(1) != "200":
            print(f"[预检-perf][失败] {path}:{number} 拦截应答的状态码是 {hit.group(1)}，只允许 200")
            failed = True
sys.exit(1 if failed else 0)
PY
  fi
fi

# ── 5. clearCache 收口：只在两个位置，且必须走时机判定 ──────────────────────
#    只看"非注释行"：CacheTrimPolicy 的注释里会提到 clearCache(true)/(false)，
#    那是文档而不是调用点。
allowed=" app/src/main/java/com/nhyzcms/clubapp/MainActivity.kt $PERF_DIR/PerfHost.kt "
while IFS= read -r file; do
  [ -n "$file" ] || continue
  case "$allowed" in
    *" $file "*) ;;
    *) err "clearCache 只允许出现在 MainActivity（forceRefreshEntry）与 shell/perf（收口 + 节流）：$file" ;;
  esac
done < <(code_grep 'clearCache\(' app/src/main/java | cut -d: -f1 | sort -u)
grep -q 'CacheTrimPolicy' "$PERF_DIR/PerfHost.kt" \
  || err "清理缓存必须走 CacheTrimPolicy 的时机判定，不许无条件清（长缓存是弱网下不重复下载的依靠）"
grep -q 'scheduleCacheMaintenance' app/src/main/java/com/nhyzcms/clubapp/MainActivity.kt \
  || err "MainActivity 必须在冷启动时调用 PerfHost.scheduleCacheMaintenance()"

# ── 6. 探测降频不能被改回去 ─────────────────────────────────────────────────
grep -qE 'MIN_INTERVAL_MS *= *1_500L' "$PROBE" \
  || err "PageStateProbe.MIN_INTERVAL_MS 不得低于 1500 ms（探测太勤会与渲染抢主线程）"
grep -q 'safeInterval()' "$PROBE" \
  || err "PageStateProbe 排下一轮必须走 safeInterval()（间隔收口 + 异常兜底）"
grep -q 'ProbeCadence' "$FEEL_HOST" \
  || err "状态探测必须接 ProbeCadence（稳定退避、有变化立刻回快档）"

# ── 7. 举诊断必须配"自动撤销"（区块 6 修的就是"浮层一直挂着"）──────────────
grep -q 'RenderWatchPolicy.nextClearPollDelay' "$FEEL_HOST" \
  || err "诊断浮层必须有自动撤销轮询（RenderWatchPolicy.nextClearPollDelay）"
grep -q 'startClearPoll()' "$FEEL_HOST" \
  || err "reportBlank 必须启动撤销轮询，否则误报的浮层会一直挂在屏幕上"
dismiss_calls=$(grep -c 'dismissDiagnostics(' "$FEEL_HOST" 2>/dev/null || echo 0)
if [ "$dismiss_calls" -lt 3 ]; then
  err "dismissDiagnostics 的调用点太少（$dismiss_calls）：定义处 + onPageRendered + 撤销轮询至少 3 处"
fi

# ── 8. 新开关必须同时进代码与文档 ───────────────────────────────────────────
grep -q '"blockLive2dAssets"' app/src/main/java/com/nhyzcms/clubapp/shell/update/UpdateManifest.kt \
  || err "新开关必须写进 UpdateManifest.DEFAULT_FLAGS（否则清单里写了也不生效）"
grep -q '"blockLive2dAssets"' docs/APP-MANIFEST.md \
  || err "新开关必须写进 docs/APP-MANIFEST.md §3 的开关表（否则没人知道能远程关掉）"

# ── 9. 跨包引用必须显式 import（区块 5 的教训：漏 import 只有 CI 能发现）────────
#    本地没有 Android SDK，依赖 androidx / android.* 的文件编译不了，只能靠静态规则兜。
#    规则：文件里用到了某个"来自别的包的符号"，就必须写出对应的 import。
#    排除：同包引用（不需要 import）。
if command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY' || fail=1
import pathlib
import re
import sys

# 符号 → 它所在的包（只有这些跨包符号需要 import；同包引用会被跳过）
SYMBOLS = {
    # Android / Java 平台
    "WebResourceResponse": "android.webkit",
    "WebResourceRequest": "android.webkit",
    "WebResourceError": "android.webkit",
    "WebViewClient": "android.webkit",
    "ByteArrayInputStream": "java.io",
    "StatFs": "android.os",
    "ActivityManager": "android.app",
    "ComponentCallbacks2": "android.content",
    "HttpURLConnection": "java.net",
    "SwipeRefreshLayout": "androidx.swiperefreshlayout.widget",
    # 本项目的跨包符号（区块 6 新增 / 使用）
    "PerfHost": "com.nhyzcms.clubapp.shell.perf",
    "PerfProfile": "com.nhyzcms.clubapp.shell.perf",
    "DeviceClassifier": "com.nhyzcms.clubapp.shell.perf",
    "DeviceClass": "com.nhyzcms.clubapp.shell.perf",
    "DiagLog": "com.nhyzcms.clubapp.shell",
}

failed = False
for path in sorted(pathlib.Path("app/src/main/java").rglob("*.kt")):
    text = path.read_text(encoding="utf-8")
    package_match = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
    own_package = package_match.group(1) if package_match else ""
    code_lines = [
        line
        for line in text.splitlines()
        if not re.match(r"^\s*(//|\*|/\*)", line)
    ]
    code = "\n".join(code_lines)
    for symbol, package in SYMBOLS.items():
        if not re.search(r"\b" + symbol + r"\b", code):
            continue
        if own_package == package:
            continue
        if re.search(r"^import\s+" + re.escape(package) + r"\." + symbol + r"\s*$", text, re.MULTILINE):
            continue
        print(f"[预检-perf][失败] {path}：用到了 {symbol} 却没写 import {package}.{symbol}")
        failed = True
sys.exit(1 if failed else 0)
PY
else
  echo "[预检-perf][提示] 没有 python3，跳过跨包引用 import 检查"
fi

if [ "$fail" -eq 0 ]; then
  echo "[预检-perf] 全部通过 ✅"
else
  echo "[预检-perf] 存在失败项 ❌"
fi
exit "$fail"
