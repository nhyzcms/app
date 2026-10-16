#!/usr/bin/env bash
# 校验「环境里的发布签名 keystore」就是「登记在册的那一把」。
#
# 为什么需要它（真实风险，不是形式主义）：
#   签名是更新路径的信任根。发版时若用错 keystore，构建会成功、Release 会发出，
#   但已装用户装不上（INSTALL_FAILED_UPDATE_INCOMPATIBLE），更新链断掉且很难回滚：
#   一旦有用户装了新包，回到旧密钥也覆盖不了。所以把比对前置到构建之前。
#
# 期望值来自 deploy/signing-fingerprint.txt（该文件入库、无秘密，是唯一真源）。
#
# 用法（CI 里由 .github/workflows/android.yml 注入环境变量）：
#   ANDROID_KEYSTORE_PATH=/path/release.jks \
#   ANDROID_KEYSTORE_PASSWORD='***' \
#   [ANDROID_KEY_ALIAS=clubapp] \
#   bash scripts/verify-keystore.sh
#
#   也支持只给 base64（脚本自己解码到临时文件，退出时删除）：
#   ANDROID_KEYSTORE_BASE64='...' ANDROID_KEYSTORE_PASSWORD='***' bash scripts/verify-keystore.sh
#
# 也可给命令行参数：--keystore --storepass --alias
#
# 退出码：0 = 通过；非 0 = 不通过（CI 视为发版阻断）。
# 安全：本脚本**从不打印口令**；keystore 内容也不会被输出。
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

EXPECTED_FILE="deploy/signing-fingerprint.txt"
KS="${ANDROID_KEYSTORE_PATH:-}"
KS_PASS="${ANDROID_KEYSTORE_PASSWORD:-}"
ALIAS="${ANDROID_KEY_ALIAS:-}"
TMP_KS=""

while [ $# -gt 0 ]; do
  case "$1" in
    --keystore) KS="${2:-}"; shift 2 ;;
    --storepass) KS_PASS="${2:-}"; shift 2 ;;
    --alias) ALIAS="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

cleanup() { [ -n "$TMP_KS" ] && rm -f "$TMP_KS"; }
trap cleanup EXIT

die() { echo "[签名校验][失败] $*" >&2; exit 1; }
warn() { echo "[签名校验][警告] $*"; }

# ── 1. 读期望值（用 grep 逐行取，不 source 仓库里的文件）─────────────────────
[ -f "$EXPECTED_FILE" ] || die "缺少 $EXPECTED_FILE（登记指纹的唯一真源）"
field() { grep -E "^$1=" "$EXPECTED_FILE" | head -1 | cut -d= -f2- | tr -d ' \r'; }
EXPECTED_SHA="$(field CERT_SHA256 | tr 'a-f' 'A-F')"
EXPECTED_ALIAS="$(field KEY_ALIAS)"
EXPECTED_UNTIL="$(field VALID_UNTIL)"

[ -n "$EXPECTED_SHA" ] || die "$EXPECTED_FILE 里没有 CERT_SHA256"
[ -n "$EXPECTED_ALIAS" ] || die "$EXPECTED_FILE 里没有 KEY_ALIAS"
case "$EXPECTED_SHA" in
  [0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]:*) : ;;
  *) die "$EXPECTED_FILE 的 CERT_SHA256 不是指纹格式：$EXPECTED_SHA" ;;
esac

# ── 2. 拿到 keystore 文件 ─────────────────────────────────────────────────────
if [ -z "$KS" ]; then
  if [ -n "${ANDROID_KEYSTORE_BASE64:-}" ]; then
    TMP_KS="$(mktemp "${TMPDIR:-/tmp}/verify-keystore-XXXXXX.jks")"
    printf '%s' "$ANDROID_KEYSTORE_BASE64" | tr -d '\n\r' | base64 -d > "$TMP_KS" \
      || die "ANDROID_KEYSTORE_BASE64 解码失败（值被截断或不是 base64？）"
    KS="$TMP_KS"
  else
    die "未提供 keystore：请设置 ANDROID_KEYSTORE_PATH 或 ANDROID_KEYSTORE_BASE64"
  fi
fi
[ -f "$KS" ] || die "keystore 文件不存在：$KS"
[ -s "$KS" ] || die "keystore 文件是空的：$KS"

# ── 3. alias 必须与登记一致（配错 alias 也是一种「用错钥匙」）─────────────────
if [ -n "$ALIAS" ] && [ "$ALIAS" != "$EXPECTED_ALIAS" ]; then
  die "ANDROID_KEY_ALIAS='$ALIAS' 与登记的 '$EXPECTED_ALIAS' 不一致"
fi
[ -n "$ALIAS" ] || ALIAS="$EXPECTED_ALIAS"

[ -n "$KS_PASS" ] || die "未提供 keystore 口令（ANDROID_KEYSTORE_PASSWORD）"

command -v keytool >/dev/null 2>&1 || die "找不到 keytool（需要 JDK）；CI 里请先 actions/setup-java"

# ── 4. 读出实际指纹 ───────────────────────────────────────────────────────────
if ! OUT="$(keytool -list -v -keystore "$KS" -storepass "$KS_PASS" -alias "$ALIAS" 2>&1)"; then
  # keytool 的报错里不含口令；只回显前几行，便于区分「口令错」与「alias 不存在」。
  echo "$OUT" | head -6 | sed 's/^/    /' >&2
  die "keytool 读不出这个 keystore（口令错 / alias 不存在 / 文件损坏）"
fi

ACTUAL_SHA="$(printf '%s\n' "$OUT" | grep -m1 -E '^[[:space:]]*SHA256:' \
  | sed 's/.*SHA256:[[:space:]]*//; s/[[:space:]]*$//' | tr 'a-f' 'A-F')"
[ -n "$ACTUAL_SHA" ] || die "解析不出证书 SHA-256 指纹（keytool 输出格式变了？）"

# ── 5. 比对 ───────────────────────────────────────────────────────────────────
if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
  {
    echo "[签名校验][失败] 这把 keystore 与登记的不是同一把！"
    echo "  实际（Secrets 里的）: $ACTUAL_SHA"
    echo "  期望（$EXPECTED_FILE）: $EXPECTED_SHA"
    echo
    echo "  用错密钥发版 = 已装用户无法覆盖安装（且难以回滚）。已阻断。"
    echo "  处理：要么把 Secrets 换成正确的那把 keystore，"
    echo "        要么（确实要轮换密钥时）按 docs/BUILD.md §3.4 更新登记指纹。"
  } >&2
  exit 1
fi

# ── 6. 有效期（KeyStore 过期会直接导致后续版本装不上）────────────────────────
if UNTIL_RAW="$(printf '%s\n' "$OUT" | grep -m1 -E 'until:' | sed 's/.*until:[[:space:]]*//')"; then
  if UNTIL_EPOCH="$(date -u -d "$UNTIL_RAW" +%s 2>/dev/null)"; then
    DAYS=$(( (UNTIL_EPOCH - $(date -u +%s)) / 86400 ))
    if [ "$DAYS" -le 0 ]; then
      die "证书已过期（until: $UNTIL_RAW）——过期的密钥签出来的包会被系统拒绝安装"
    fi
    if [ "$DAYS" -lt 3650 ]; then
      warn "证书剩余有效期只有 $DAYS 天（< 10 年）。登记文件写的 VALID_UNTIL=$EXPECTED_UNTIL，请核对是否该规划换钥。"
    else
      echo "证书剩余有效期：$DAYS 天"
    fi
  fi
fi

echo "== 发布签名 keystore 校验通过 ✅ =="
echo "  文件         : $KS"
echo "  别名         : $ALIAS"
echo "  证书 SHA-256 : $ACTUAL_SHA"
echo "  （与 $EXPECTED_FILE 登记值一致）"
exit 0
