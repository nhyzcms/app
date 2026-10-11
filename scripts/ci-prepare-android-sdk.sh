#!/usr/bin/env bash
# 为 CI 准备 Android SDK。
#
# 为何不用 android-actions/setup-android@v3：
#   1) GitHub 托管的 ubuntu runner 本来就预装了 Android SDK（ANDROID_HOME/ANDROID_SDK_ROOT 已设）；
#   2) 该 action 在 2026-10-11 的首次 CI 运行中失败（“安装 Android SDK”步骤 8 秒内失败）；
#   3) 本仓库的原则是依赖最小化：少一个第三方 action，少一个故障点。
#
# 本脚本做的事：
#   1) 确认 SDK 目录存在，并打印 platforms/build-tools 供排查；
#   2) 接受 SDK 许可（这样 AGP 才能自动下载缺失的 platform 35 / build-tools）；
#   3) 写出 local.properties（避免依赖隐式环境变量）。
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
echo "ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-<empty>}"
echo "ANDROID_HOME=${ANDROID_HOME:-<empty>}"

if [ -z "$SDK" ]; then
  echo "::error::未找到 ANDROID_SDK_ROOT/ANDROID_HOME，runner 镜像可能变了"
  exit 1
fi
if [ ! -d "$SDK" ]; then
  echo "::error::SDK 目录不存在：$SDK"
  exit 1
fi

echo "--- platforms ---"
ls -1 "$SDK/platforms" 2>/dev/null || echo "(空)"
echo "--- build-tools ---"
ls -1 "$SDK/build-tools" 2>/dev/null || echo "(空)"

SDKMGR="$(ls -d "$SDK"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | head -1 || true)"
echo "sdkmanager=${SDKMGR:-<未找到>}"
if [ -n "$SDKMGR" ]; then
  # 许可接受失败不致命：已预装的组件无需许可；失败时后面构建阶段会给出真实报错。
  yes | "$SDKMGR" --licenses > /dev/null 2>&1 && echo "已接受 SDK 许可" || echo "::warning::接受 SDK 许可失败（继续）"
else
  echo "::warning::未找到 sdkmanager，跳过许可接受"
fi

echo "sdk.dir=$SDK" > local.properties
echo "已写入 local.properties：$(cat local.properties)"
