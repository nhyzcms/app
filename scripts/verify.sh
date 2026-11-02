#!/usr/bin/env bash
# 区块 9：一条命令验证本仓库（本地与 CI 共用同一个入口）。
#
# 为什么需要：之前"验证"散在 CI 的 5 个 step 和 6 个 preflight 脚本里，
# 本地想复现 CI 得自己拼命令；而拼错的验证 = 没验证。本脚本是唯一入口：
#
#   bash scripts/verify.sh            # 静态预检 + 单元测试（本地无 JDK/Gradle 时自动跳过单测）
#   bash scripts/verify.sh --static   # 只跑静态预检（CI 的 fail-fast 阶段，5 秒内出结果）
#   bash scripts/verify.sh --unit     # 只跑单元测试（CI 装好 Android SDK 之后）
#
# 环境变量：
#   VERIFY_REQUIRE_GRADLE=1  找不到 gradle 时判为失败（CI 用：宁可红，不可假绿）
#
# 注意：本脚本**不**在服务器上跑（服务器只有 2 GB 内存，跑不动 Android 构建）。
# 单测在 GitHub Actions 的 runner 上跑，见 .github/workflows/android.yml。
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

mode="all"
case "${1:-}" in
  ""|--all) mode="all" ;;
  --static) mode="static" ;;
  --unit) mode="unit" ;;
  -h|--help)
    sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  *)
    echo "未知参数：$1（可用：--static / --unit / --all）" >&2
    exit 2
    ;;
esac

fail=0
step() {
  echo
  echo "======== $* ========"
}

# ── 静态预检：只需要 bash + python3 ─────────────────────────────────────────
if [ "$mode" = "all" ] || [ "$mode" = "static" ]; then
  step "静态预检（preflight.sh）"
  bash scripts/preflight.sh || fail=1

  # preflight-feel.sh 会连带执行同目录的 preflight-*.sh
  # （perf / push / scan / signing / regress），所以这里只调它一个。
  step "静态预检（preflight-feel.sh + 全部专项脚本）"
  bash scripts/preflight-feel.sh || fail=1
fi

# ── 单元测试：需要 JDK 17 + Android SDK + Gradle ─────────────────────────────
if [ "$mode" = "all" ] || [ "$mode" = "unit" ]; then
  if command -v gradle >/dev/null 2>&1; then
    step "单元测试（含 MainActivityShellTest 壳级启动测试）"
    set +e
    gradle :app:testDebugUnitTest --stacktrace 2>&1 | tee /tmp/clubapp-unit-test.log
    code=${PIPESTATUS[0]}
    set -e
    if [ "$code" -ne 0 ]; then
      echo "[verify][失败] 单元测试未通过，详见 /tmp/clubapp-unit-test.log" >&2
      fail=1
    else
      echo "[verify] 单元测试全部通过"
    fi
  else
    echo
    echo "[verify] 未找到 gradle：跳过单元测试。"
    echo "         本地要跑单测需 JDK 17 + Android SDK（见 docs/BUILD.md）；CI 会跑。"
    if [ "${VERIFY_REQUIRE_GRADLE:-0}" = "1" ]; then
      echo "[verify][失败] VERIFY_REQUIRE_GRADLE=1 但找不到 gradle" >&2
      fail=1
    fi
  fi
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "[verify] 全部通过 ✅（模式：$mode）"
else
  echo "[verify] 存在失败项 ❌（模式：$mode）" >&2
fi
exit "$fail"
