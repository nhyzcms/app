#!/usr/bin/env bash
# 把一份 APK 发布到自托管更新目录，并同步 manifest.json 里的 latest 字段。
#
# 位置：本文件在仓库里；真正要跑它的地方是服务器（更新器所在的机器），
# 因为它需要写入 /opt/club/data/app-update/。
#
# ⚠️ 目录为什么必须放在 `/opt/club/data/` 下面（2026-10-17 实测）：
#   网站更新器应用发布包时是「rsync -a --delete 等价语义」——/opt/club 下**不在**
#   SYNC_EXCLUDES（.env / db.sqlite3* / media / private_media / data / run / backups /
#   .venv / .git）里的顶层条目会被删掉。最初放在 /opt/club/app-update/，
#   结果 2026-10-17 00:42 的自动更新把它整个删了（/app-update/ 立刻 404）。
#   `data` 是网站侧的「统一数据根」，在排除项里，所以放它下面才不会被更新清掉。
#
# 用法：
#   bash scripts/publish-apk.sh --apk /path/app-release.apk \
#        --version-code 2 --version-name 0.2.0 --notes "新增：文件上传与下载支持"
#   可选：--mandatory（强制更新）、--dir /opt/club/data/app-update（默认就是这个）、
#        --dry-run（只打印不写盘）
#
# 它做的事：
#   1. 校验 APK 存在且扩展名是 .apk；
#   2. 计算 sha256 与体积；
#   3. 复制为 <dir>/latest.apk 并写 <dir>/latest.apk.sha256；
#   4. 读 <dir>/manifest.json（没有就用仓库里的 deploy/app-update/manifest.json 模板），
#      更新 latest / generatedAt，其余字段（flags、notice、startRoute、blockedVersions）保持不动；
#   5. 打印最终 manifest，方便人工核对。
#
# ⚠️ 签名一致性：更新包的签名必须与已发布版本一致，否则壳会拒绝安装（这是设计，不是 bug）。
set -euo pipefail

APK=""
VERSION_CODE=""
VERSION_NAME=""
NOTES=""
MANDATORY="false"
DIR="/opt/club/data/app-update"
DRY_RUN="false"

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="${2:-}"; shift 2 ;;
    --version-code) VERSION_CODE="${2:-}"; shift 2 ;;
    --version-name) VERSION_NAME="${2:-}"; shift 2 ;;
    --notes) NOTES="${2:-}"; shift 2 ;;
    --mandatory) MANDATORY="true"; shift ;;
    --dir) DIR="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN="true"; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

[ -n "$APK" ] || { echo "缺少 --apk" >&2; exit 2; }
[ -f "$APK" ] || { echo "APK 不存在：$APK" >&2; exit 2; }
case "$APK" in
  *.apk) ;;
  *) echo "只接受 .apk 文件：$APK" >&2; exit 2 ;;
esac
[ -n "$VERSION_CODE" ] || { echo "缺少 --version-code" >&2; exit 2; }
[ -n "$VERSION_NAME" ] || { echo "缺少 --version-name" >&2; exit 2; }
case "$VERSION_CODE" in
  ''|*[!0-9]*) echo "--version-code 必须是正整数：$VERSION_CODE" >&2; exit 2 ;;
esac
[ "$VERSION_CODE" -gt 0 ] || { echo "--version-code 必须大于 0" >&2; exit 2; }

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMPLATE="$REPO_ROOT/deploy/app-update/manifest.json"
MANIFEST="$DIR/manifest.json"

echo "== 发布 APK =="
echo "源文件      : $APK"
echo "versionCode : $VERSION_CODE ($VERSION_NAME)"
echo "目标目录    : $DIR"
echo "强制更新    : $MANDATORY"

APK_SHA="$(sha256sum "$APK" | awk '{print $1}')"
APK_SIZE="$(stat -c '%s' "$APK")"
echo "sha256      : $APK_SHA"
echo "size        : $APK_SIZE 字节"

if [ "$DRY_RUN" = "true" ]; then
  echo "（--dry-run：不写盘）"
  exit 0
fi

# 权限：nginx 是以 `nginx` 用户（不是 root）去读文件的，所以这里必须是
# 「目录 0755 + 文件 0644」这种连 other 都可读的组合。
# 2026-10-17 实测踩过：脚本原本写 0750/0640，而 install 出来的文件属主是 root:root，
# nginx 读不到 → `/app-update/latest.apk` 直接 403。
install -d -m 0755 "$DIR"
install -m 0644 "$APK" "$DIR/latest.apk"
printf '%s  latest.apk\n' "$APK_SHA" > "$DIR/latest.apk.sha256"
chmod 0644 "$DIR/latest.apk.sha256"

if [ ! -f "$MANIFEST" ]; then
  [ -f "$TEMPLATE" ] || { echo "找不到模板：$TEMPLATE" >&2; exit 2; }
  install -m 0640 "$TEMPLATE" "$MANIFEST"
  echo "已用模板初始化 manifest.json"
fi

APK_SHA="$APK_SHA" \
APK_SIZE="$APK_SIZE" \
VERSION_CODE="$VERSION_CODE" \
VERSION_NAME="$VERSION_NAME" \
NOTES="$NOTES" \
MANDATORY="$MANDATORY" \
MANIFEST="$MANIFEST" \
python3 - <<'PY'
import json, os, sys, datetime

path = os.environ["MANIFEST"]
with open(path, encoding="utf-8") as fh:
    data = json.load(fh)

data["generatedAt"] = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
data["latest"] = {
    "versionCode": int(os.environ["VERSION_CODE"]),
    "versionName": os.environ["VERSION_NAME"],
    "notes": os.environ["NOTES"],
    "mandatory": os.environ["MANDATORY"] == "true",
    "apk": {
        "url": "https://8.153.145.175/app-update/latest.apk",
        "sha256": os.environ["APK_SHA"],
        "size": int(os.environ["APK_SIZE"]),
    },
}

with open(path, "w", encoding="utf-8") as fh:
    json.dump(data, fh, ensure_ascii=False, indent=2)
    fh.write("\n")

print(json.dumps(data, ensure_ascii=False, indent=2))
PY

echo
echo "== 完成 =="
echo "请核对上面的 latest 字段，然后验证可访问性（三条都要 200）："
echo "  curl -sSI https://8.153.145.175/app-update/manifest.json"
echo "  curl -sSI https://8.153.145.175/app-update/latest.apk"
echo "最后做一次端到端核对（下载回来的包哈希必须等于上面的 APK_SHA，"
echo "这一步同时验证了权限、路由与内容三件事）："
echo "  curl -skSL https://8.153.145.175/app-update/latest.apk | sha256sum"
