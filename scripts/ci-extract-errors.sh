#!/usr/bin/env bash
# 从 Gradle 构建日志中抽出真正有用的报错，供 CI 失败时贴到 PR 评论。
#
# 为何需要它：
#   `tail -n 120 build.log` 里 95% 是 Gradle 内部堆栈帧，真正的原因只有一行
#   （`Caused by: ...`），既难读又容易把评论挤爆。
#   这里先按关键模式过滤，再附日志尾部，保证一屏就能看到问题。
#
# 用法：bash scripts/ci-extract-errors.sh build.log
set -uo pipefail

LOG="${1:-build.log}"
if [ ! -f "$LOG" ]; then
  echo "(未找到构建日志：$LOG)"
  exit 0
fi

# 关键报错模式：Kotlin/Javac 编译错误、Gradle 失败点、AAPT/资源错误、依赖解析失败
PATTERN='(^e: |: error: |^Error: |Error: |FAILURE: Build failed|What went wrong|Execution failed for task|Caused by:|Android resource|AAPT|Could not (resolve|find|determine|download)|Unsupported |Duplicate class|Invalid |SDK location)'

echo '#### 关键报错行（已滤掉堆栈帧）'
if grep -aE "$PATTERN" "$LOG" | grep -av '^[[:space:]]*at ' | tail -n 25 | cut -c1-300 | grep -q .; then
  grep -aE "$PATTERN" "$LOG" | grep -av '^[[:space:]]*at ' | tail -n 25 | cut -c1-300
else
  echo '(未匹配到典型报错行，请看下方日志尾部)'
fi

echo
echo '#### 日志末尾 15 行'
tail -n 15 "$LOG" | cut -c1-300
