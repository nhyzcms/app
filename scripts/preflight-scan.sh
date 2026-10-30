#!/usr/bin/env bash
# 区块 8 增补（原生扫码）专项静态预检。
#
# 为什么单独成文件：扫码把「相机」和「壳内导航」两条敏感路径接在了一起，
# 规则很集中（认哪几种码 / 只认本站 / 不往 JS 里塞文本 / 取景页只有本应用能拉起），
# 独立出来比塞进已经很长的 preflight.sh 好读；命名成 preflight-*.sh 会被
# preflight-feel.sh 自动连带执行，CI 不用改。
#
# 用法： bash scripts/preflight-scan.sh
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
fail=0
err() { echo "[预检-scan][失败] $*"; fail=1; }

# 只看“非注释行”，避免我们自己的警示注释触发规则（同 preflight.sh 的做法）。
code_grep() {
  local pattern="$1"
  local path="${2:-app/src/main/java}"
  grep -rn --include='*.kt' -E "$pattern" "$path" 2>/dev/null \
    | grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*)' \
    || true
}

MANIFEST=app/src/main/AndroidManifest.xml
MAIN=app/src/main/java/com/nhyzcms/clubapp/MainActivity.kt
SCAN_DIR=app/src/main/java/com/nhyzcms/clubapp/shell/scan

# ── 1. 取景页必须在清单里，且只有本应用能拉起 ─────────────────────────────
if ! grep -q '\.shell\.scan\.ScanActivity' "$MANIFEST"; then
  err "清单里没有声明 .shell.scan.ScanActivity（扫码页起不来）"
else
  # 取出 ScanActivity 那个 activity 节点的属性行，逐项核对。
  node=$(python3 - "$MANIFEST" <<'PY'
import re, sys
raw = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"<activity\b[^>]*\.shell\.scan\.ScanActivity[^>]*>", raw, re.S)
print(m.group(0) if m else "")
PY
)
  [ -n "$node" ] || err "ScanActivity 的 <activity> 节点解析不出来（写法可能变了）"
  case "$node" in
    *'android:exported="false"'*) ;;
    *) err "ScanActivity 必须 android:exported=\"false\"：取景页不该被外部应用拉起" ;;
  esac
  case "$node" in
    *'android:screenOrientation="portrait"'*) ;;
    *) err "ScanActivity 必须锁 portrait：库自带 CaptureActivity 是 sensorLandscape，横屏扫码很别扭" ;;
  esac
  case "$node" in
    *'android:theme="@style/Theme_ClubApp_Scan"'*) ;;
    *) err "ScanActivity 必须用 Theme_ClubApp_Scan（黑底 + 浅色系统栏图标）" ;;
  esac
  if grep -q "\.shell\.scan\.ScanActivity" "$MANIFEST" &&
     python3 - "$MANIFEST" <<'PY'
import re, sys
raw = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"<activity\b[^>]*\.shell\.scan\.ScanActivity.*?</activity>", raw, re.S)
sys.exit(0 if m and "<intent-filter" in m.group(0) else 1)
PY
  then
    err "ScanActivity 不该有 intent-filter：扫码只能从 App 内的按钮进"
  fi
fi

# ── 2. 只认工牌上的两种码：QR_CODE + CODE_128 ────────────────────────────
#    放宽到 ALL_CODE_TYPES 会让每帧多跑几轮解码（低端机掉帧），
#    也会把 EAN/UPC 之类无关码当成工牌去查，白白消耗查询额度。
formats=$(code_grep 'setDesiredBarcodeFormats' "$MAIN")
if [ -z "$formats" ]; then
  err "MainActivity 没有设置 setDesiredBarcodeFormats：默认会扫全部格式，低端机掉帧"
else
  case "$formats" in
    *QR_CODE*) ;;
    *) err "扫码格式必须包含 QR_CODE（工牌二维码）" ;;
  esac
  case "$formats" in
    *CODE_128*) ;;
    *) err "扫码格式必须包含 CODE_128（工牌条形码）" ;;
  esac
  if echo "$formats" | grep -qE 'ALL_CODE_TYPES|pdf417_417|PDF_417|DATA_MATRIX|AZTEC|UPC_A|EAN_13'; then
    err "扫码格式混进了非工牌格式：工牌只有二维码与 Code128，收敛格式才有低端机帧率"
  fi
fi

# ── 3. 必须用我们自己的 ScanActivity 打开（否则清单里的竖屏/主题都不生效）──
if [ -z "$(code_grep 'setCaptureActivity\(ScanActivity::class\.java\)' "$MAIN")" ]; then
  err "必须 ScanOptions.setCaptureActivity(ScanActivity::class.java)，否则起的是库自带横屏页"
fi

# ── 4. 相机权限必须先问再开（拒了要给中文引导，不能白闪一下）─────────────
if [ -z "$(code_grep 'PermissionsPolicy\.CAMERA' "$MAIN")" ]; then
  err "扫码前必须自己申请相机权限（PermissionsPolicy.CAMERA），别指望取景页里再问"
fi

# ── 5. 扫到的文本只许进「网址」，不许进 JS ───────────────────────────────
#    扫码内容是外部输入（任何人可以打一张二维码）。壳内执行 JS 的边界见
#    docs/SECURITY.md 第 4.1 节：只允许常量脚本，所以结果必须作为 URL 交给 WebView。
if [ -n "$(code_grep 'evaluateJavascript' "$SCAN_DIR")" ]; then
  err "扫码模块里出现了 evaluateJavascript：扫到的文本只能拼成站内网址，不得注入 JS"
fi
if [ -z "$(code_grep 'ScanRouter\.targetUrl\(' "$MAIN")" ]; then
  err "扫码结果必须经 ScanRouter.targetUrl 判落点（直接 loadUrl 会把用户带去任意站点）"
fi

# ── 6. 落点判定的两个安全边界不能少 ──────────────────────────────────────
if [ -z "$(code_grep 'siteHost' "$SCAN_DIR")" ]; then
  err "ScanRouter 必须校验主机名（siteHost）：二维码里的链接只认本站，别的不接管"
fi
if [ -z "$(code_grep 'MAX_TEXT_LEN' "$SCAN_DIR")" ]; then
  err "ScanRouter 必须有文本长度上限（MAX_TEXT_LEN）：不往地址栏里塞任意长内容"
fi
#    只认新版 12 位签名条码：8 位裸识别码在服务端按查询者身份扣额度，
#    扫码走它等于替用户白扣一次；工牌还没印，没有需要兼容的存量（2026-10-30 管理员确认）。
if [ -z "$(code_grep 'BARCODE_PAYLOAD_LEN' "$SCAN_DIR")" ]; then
  err "ScanRouter 必须按 BARCODE_PAYLOAD_LEN（12 位签名载荷）判条码长度"
fi
if [ -n "$(code_grep 'CODE_LEN' "$SCAN_DIR")" ]; then
  err "扫码不要恢复 8 位裸识别码（会白扣查询额度）：条码只认 12 位签名载荷"
fi

# ── 7. 清单开关可远程撤掉按钮（运营兜底）────────────────────────────────
if [ -z "$(code_grep 'showScanEntry' app/src/main/java)" ]; then
  err "扫码按钮必须受清单开关 showScanEntry 控制（默认开，见 docs/APP-MANIFEST.md）"
fi

if [ "$fail" -eq 0 ]; then
  echo "[预检-scan] 全部通过 ✅"
else
  echo "[预检-scan] 存在失败项 ❌"
fi
exit "$fail"
