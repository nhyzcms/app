#!/usr/bin/env bash
# 区块 7（签名与发版）专项静态预检 —— 不需要 Secrets，不需要 JDK，只需 bash + grep/python3。
#
# 它保护的是一条**只有在发版那一刻才会崩**的链路：
#   Secrets 被误改 / 登记指纹被手滑改坏 / 发版闸门被后来的重构删掉 /
#   口令被顺手写进文档或工作流。
# 这些错误在 PR 阶段完全看不出来（CI 也不会失败），所以固化成本地+CI 都能跑的静态规则。
#
# 用法： bash scripts/preflight-signing.sh
# 它会被 scripts/preflight-feel.sh 的 "preflight-*.sh 连带执行" 自动带上，无需改 workflow。
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
fail=0
err() { echo "[预检-signing][失败] $*"; fail=1; }

FINGERPRINT="deploy/signing-fingerprint.txt"
WORKFLOW=".github/workflows/android.yml"
VERIFIER="scripts/verify-keystore.sh"

# ── 1. 登记指纹文件必须存在、格式正确 ────────────────────────────────────────
#    它是「哪把钥匙算对」的唯一真源；坏了就等于闸门失效。
if [ ! -f "$FINGERPRINT" ]; then
  err "缺少 $FINGERPRINT（发布签名指纹的唯一真源，见 docs/BUILD.md §3.1）"
else
  out=$(python3 - "$FINGERPRINT" 2>&1 <<'PY'
import re, sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
rows = {}
for line in text.splitlines():
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    rows[k.strip()] = v.strip()

sha = rows.get("CERT_SHA256", "")
alias = rows.get("KEY_ALIAS", "")
if not re.fullmatch(r"([0-9A-F]{2}:){31}[0-9A-F]{2}", sha):
    sys.exit(f"CERT_SHA256 不是 32 段十六进制指纹（当前：{sha!r}）")
if not alias:
    sys.exit("KEY_ALIAS 为空")
if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", alias):
    sys.exit(f"KEY_ALIAS 含有可疑字符：{alias!r}")
# 只在「真正的赋值行」里查口令字样：注释里顺口提到 Secrets 不算问题，
# 但如果有人真把口令当数据写进来，就必须拦住（这个文件是公开入库的）。
for key, value in rows.items():
    blob = f"{key}={value}".upper()
    for bad in ("PASSWORD", "PASSWD", "STOREPASS", "KEYPASS", "SECRET"):
        if bad in blob:
            sys.exit(f"登记文件里出现了 {bad} 字样：这个文件是公开入库的，绝不可放口令（问题行：{key}）")
PY
) || err "$FINGERPRINT：$out"
fi

# ── 2. keystore / 口令绝不能入库 ─────────────────────────────────────────────
grep -q '^\*\.jks$' .gitignore || err ".gitignore 必须继续屏蔽 *.jks"
grep -q '^\*\.keystore$' .gitignore || err ".gitignore 必须继续屏蔽 *.keystore"

tracked=$(git ls-files -- '*.jks' '*.keystore' 'keystore.properties' 2>/dev/null)
if [ -n "$tracked" ]; then
  err "这些密钥/配置类文件被 git 跟踪了，必须立刻移除：$tracked"
fi

# ── 3. 发版闸门不能被人删掉 ───────────────────────────────────────────────────
if [ ! -f "$VERIFIER" ]; then
  err "缺少 $VERIFIER（release 前比对签名指纹的闸门脚本）"
else
  grep -q 'CERT_SHA256' "$VERIFIER" \
    || err "$VERIFIER 没有读 CERT_SHA256（登记值），闸门形同虚设"
fi
if [ -f "$WORKFLOW" ]; then
  grep -q 'scripts/verify-keystore.sh' "$WORKFLOW" \
    || err "$WORKFLOW 里没有调用 scripts/verify-keystore.sh：签名指纹闸门被拿掉了（用错密钥发版 = 用户装不上）"
  if ! grep -q 'apksigner' "$WORKFLOW" || ! grep -q 'print-certs' "$WORKFLOW"; then
    err "$WORKFLOW 里没有 apksigner 校验：那道闸门验的是「keystore 对不对」，这道验的是「产物本身是不是这把密钥签的」，不能删"
  fi
fi

# ── 4. 4 个 Secrets 的**名字**必须一字不差 ────────────────────────────────────
#    名字写错的表现是「Secrets 明明是配好的，CI 却说空」——很难查。
for s in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
  grep -q "$s" "$WORKFLOW" || err "$WORKFLOW 里没有引用 $s（名字写错会让 CI 认为没配）"
done

# ── 5. 口令 / keystore 内容不得被打印到 CI 日志 ───────────────────────────────
if [ -f "$WORKFLOW" ]; then
  out=$(python3 - "$WORKFLOW" 2>&1 <<'PY'
import re, sys
path = sys.argv[1]
bad = []
for i, line in enumerate(open(path, encoding="utf-8"), 1):
    s = line.strip()
    if s.startswith("#"):
        continue
    # 只抓「把变量本身展开输出」（$VAR / ${VAR}），
    # 报错文案里顺口提到变量名（echo "::error::缺少 ANDROID_KEY_PASSWORD"）不算。
    if re.search(r"echo\s+[^\n]*\$\{?[A-Z_]*ANDROID_KEY(STORE)?_PASSWORD", line):
        bad.append(f"{i}: 把口令 echo 出来：{s}")
    if re.search(r"echo\s+[^\n]*\$\{?(KEYSTORE_B64|ANDROID_KEYSTORE_BASE64)", line) and "base64 -d" not in line:
        bad.append(f"{i}: 把 keystore 内容 echo 出来（除非当行就 base64 -d 到文件）：{s}")
if bad:
    sys.exit("\n      ".join(bad))
PY
) || err "$WORKFLOW 疑似会把秘密写进日志：$out"
fi

# ── 6. 脚本/工作流里禁止出现明文口令（必须是变量引用或占位符）──────────────────
out=$(python3 - 2>&1 <<'PY'
import glob, re, sys
bad = []
paths = sorted(set(glob.glob("scripts/*.sh") + glob.glob("scripts/*.conf")
                   + glob.glob(".github/workflows/*.yml") + glob.glob("deploy/**/*.md", recursive=True)))
for path in paths:
    # 跳过本检查脚本自己：它源码里就带着 "-storepass" 这个规则字面量，
    # 扫自己必然误报（这是规则文本，不是口令）。
    if path.endswith("preflight-signing.sh"):
        continue
    for i, line in enumerate(open(path, encoding="utf-8", errors="replace"), 1):
        s = line.strip()
        if s.startswith("#"):
            continue
        m = re.search(r"-storepass\s+(\S+)", line)
        if m:
            val = m.group(1)
            if "$" not in val and "<" not in val:
                bad.append(f"{path}:{i}: -storepass 后面像是明文口令：{s}")
if bad:
    sys.exit("\n      ".join(bad))
PY
) || err "疑似明文口令入库：$out"

# ── 7. 签名配置里禁止「自己赋给自己」──────────────────────────────────────────
#    2026-10-17 首个 Release（tag v0.1.0）构建失败的真因：
#    `keyPassword = keyPassword` 在带接收者的 lambda 里两侧都解析成接收者自身的属性，
#    等于没赋值 → 打包时 NullPointerException（missing required property）。
#    这类错误静态看很像正常代码，只有真跑 assembleRelease 才会暴露，必须机器来记。
out=$(python3 - 2>&1 <<'PY'
import glob, re, sys
bad = []
for path in sorted(set(glob.glob("*.gradle.kts") + glob.glob("app/*.gradle.kts") + glob.glob("buildSrc/**/*.kts", recursive=True))):
    try:
        lines = open(path, encoding="utf-8").read().splitlines()
    except OSError:
        continue
    for i, line in enumerate(lines, 1):
        m = re.match(r"^\s*(storeFile|storePassword|keyAlias|keyPassword)\s*=\s*([A-Za-z_][A-Za-z0-9_]*)\s*$", line)
        if m and m.group(1) == m.group(2):
            bad.append(f"{path}:{i}: 自赋值（自己赋给自己，值仍是 null）：{line.strip()}")
if bad:
    sys.exit("\n      ".join(bad))
PY
) || err "签名配置疑似自赋值：$out"

# ── 结果 ─────────────────────────────────────────────────────────────────────
if [ "$fail" -eq 0 ]; then
  echo "[预检-signing] 全部通过 ✅"
else
  echo "[预检-signing] 存在失败项 ❌"
fi
exit "$fail"
