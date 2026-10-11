#!/usr/bin/env bash
# 为 CI 准备 Android SDK。
#
# 为何不用 android-actions/setup-android@v3：
#   1) GitHub 托管的 runner 本来就预装了 Android SDK（ANDROID_HOME/ANDROID_SDK_ROOT 已设）；
#   2) 该 action 在 2026-10-11 的首次 CI 运行中失败（“安装 Android SDK”步骤 8 秒内失败）；
#   3) 依赖最小化原则：少一个第三方 action，少一个故障点。
#
# 为何直接写 licenses 文件：
#   `sdkmanager --licenses` 在 runner 上会失败（实测报 warning），
#   而 AGP 想自动下载缺失的 platform / build-tools 就必须先有已接受的许可。
#   直接写这几行既不需要网络、也不需要写权限试探，结果确定。
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
echo "ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-<empty>}"
echo "ANDROID_HOME=${ANDROID_HOME:-<empty>}"
echo "当前用户：$(id -un)"

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

# 1) 先尝试正规途径（失败不致命）
SDKMGR="$(ls -d "$SDK"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | head -1 || true)"
echo "sdkmanager=${SDKMGR:-<未找到>}"
if [ -n "$SDKMGR" ]; then
  if yes | "$SDKMGR" --sdk_root="$SDK" --licenses > /tmp/licenses.log 2>&1; then
    echo "sdkmanager --licenses 成功"
  else
    echo "::warning::sdkmanager --licenses 失败（继续，下面用文件方式兜底）"
    tail -n 5 /tmp/licenses.log || true
  fi
fi

# 2) 兜底：直接写 licenses（AGP 自动下载组件所需）
mkdir -p "$SDK/licenses"
printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n' \
  > "$SDK/licenses/android-sdk-license"
printf '\n84831b9409646a918e30573bab4c9c91346d8abd\n' \
  > "$SDK/licenses/android-sdk-preview-license"
echo "--- licenses ---"
ls -l "$SDK/licenses"

# 3) 写出 local.properties（避免依赖隐式环境变量）
echo "sdk.dir=$SDK" > local.properties
echo "已写入 local.properties：$(cat local.properties)"

# 4) 打印我们真正需要的组件是否就位（缺失时 AGP 会自己下载）
for p in "platforms/android-35" "build-tools/35.0.0" "platform-tools"; do
  if [ -d "$SDK/$p" ]; then echo "✅ $p"; else echo "⏳ $p 缺失（构建时由 AGP 自动下载）"; fi
done
