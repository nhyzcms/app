#!/usr/bin/env bash
# 区块 9（测试与验收）专项静态预检：把 docs/ROADMAP.md 里的三条历史红线固化成规则。
#
# 由来：区块 1~4 的三起真机故障，单测与 CI 当时**全绿**，因为它们都不是"函数算错了"，
# 而是"接线方式错了"。这类错误只有在真机上才暴露，而管理员手上没有 adb/logcat，
# 一次回归的代价极高。所以把每条红线写成一条能自动跑的断言：
#
#   红线 1：WebView 只能在主线程访问（区块 1 闪退）
#   红线 2：任何全屏浮层都必须有一条撤除路径（区块 1 黑屏）
#   红线 3：兜底必须纯计时，不许押在 WebView 的异步回调上（区块 1 黑屏）
#
# 规则刻意写成"定点断言"而不是"通用代码分析"：本项目没有静态分析工具链，
# grep + python 足够，误报也容易判断。
#
# 用法： bash scripts/preflight-regress.sh
# 说明：被 scripts/preflight-feel.sh 的「preflight-*.sh 连带执行」自动带进 CI。
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
fail=0

python3 - <<'PY' || fail=1
import pathlib
import re
import sys

ROOT = pathlib.Path('.')
MAIN = ROOT / 'app/src/main/java/com/nhyzcms/clubapp'
problems = []


def need(cond, msg):
    if not cond:
        problems.append(msg)


def read(path):
    p = ROOT / path
    if not p.exists():
        problems.append(f'缺文件：{path}')
        return ''
    return p.read_text(encoding='utf-8')


def code_only(text):
    """去掉块注释与行注释，避免我们自己的警示注释把规则点着。"""
    no_block = re.sub(r'/\*[\s\S]*?\*/', '', text)
    return '\n'.join(line.split('//')[0] for line in no_block.splitlines())


def block(text, start_marker, max_len=2000):
    """从 start_marker 起截一段，给"某函数/某 val 里必须有什么"这类断言用。"""
    i = text.find(start_marker)
    if i < 0:
        return ''
    return text[i:i + max_len]


# ── 红线 1：WebView 只能在主线程访问 ─────────────────────────────────────────
# 后台线程模块里出现 webView 标识符 = 迟早会在后台读 settings（区块 1 的闪退）。
# 注意只看 `webView`（小写开头）：注释里写的 `WebView.getSettings()` 不算。
for sub in ('shell/push', 'shell/update'):
    d = MAIN / sub
    if not d.exists():
        continue
    for f in sorted(d.rglob('*.kt')):
        text = code_only(f.read_text(encoding='utf-8'))
        if re.search(r'\bwebView\b', text):
            problems.append(
                f'{f.relative_to(ROOT)}：后台线程模块里出现 webView。'
                'WebView 只能在主线程访问（AOSP 在后台线程会直接抛异常）。'
            )

main_act = read('app/src/main/java/com/nhyzcms/clubapp/MainActivity.kt')
need(
    not re.search(r'\bThread\s*[({]', code_only(main_act)),
    'MainActivity 里不许自己起 Thread：需要后台做的事抽到 shell/** 里，'
    '并在主线程取好 WebView 快照再传进去（区块 1 闪退的根因）。',
)
# UA 必须先在主线程取快照：UpdateController 的注释里写明了这个坑。
uc = read('app/src/main/java/com/nhyzcms/clubapp/shell/update/UpdateController.kt')
need(
    'userAgentOnMainThread' in uc,
    'UpdateController 必须保留 userAgentOnMainThread()：后台线程读 webView.settings 会崩。',
)
need(
    uc.count('userAgentOnMainThread') >= 2,
    'UpdateController 里 userAgentOnMainThread() 定义了却没人用？请检查是否有人把 UA 取值挪回后台线程。',
)

# ── 红线 2：浮层必须有一条撤除路径 ───────────────────────────────────────────
feel = read('app/src/main/java/com/nhyzcms/clubapp/shell/feel/FeelHost.kt')
layout = read('app/src/main/res/layout/activity_main.xml')

need(
    re.search(r'skeleton\.visibility = View\.GONE', feel),
    'FeelHost.hideSkeleton 必须真的把 skeleton 设为 GONE（否则浮层永远盖着网页）。',
)
need(
    re.search(r'fun hideSkeleton\(\)', feel),
    'FeelHost 必须保留 hideSkeleton()：骨架屏的唯一撤除入口。',
)
need(
    re.search(r'private val revealTick[\s\S]{0,1500}?Verdict\.REVEAL[\s\S]{0,200}?hideSkeleton\(\)', feel),
    'revealTick 的 REVEAL 分支必须撤掉骨架（内容画出来了却不撤 = 黑屏）。',
)
need(
    re.search(r'skeleton[\s\S]{0,900}?android:visibility="gone"', layout),
    'activity_main.xml 里的 skeleton 默认必须是 gone：否则一进 App 就盖住网页。',
)

# 诊断浮层（空白页提示）同理：出现之后必须在内容出现时自动撤销。
need(
    re.search(r'private fun reportBlank\(\)[\s\S]{0,800}?startClearPoll\(\)', feel),
    'reportBlank() 必须启动 clearPoll：诊断浮层不许"弹出来就不走"。',
)
need(
    re.search(r'private val clearPoll[\s\S]{0,2000}?dismissDiagnostics\(\)', feel),
    'clearPoll 必须调用 dismissDiagnostics()：内容一出现诊断就该消失。',
)

# ── 红线 3：兜底必须纯计时 ───────────────────────────────────────────────────
# 硬看门狗是"网页连文档都没建"时的唯一出路：它只能靠时间，不能靠 onPageFinished
# （那个回调要等 load 事件，服务器 3 M 带宽下迟迟不来）。
need(
    re.search(
        r'fun showSkeleton\(\)[\s\S]{0,1800}?removeCallbacks\(hardWatchdog\)[\s\S]{0,200}?postDelayed\(hardWatchdog',
        feel,
    ),
    'showSkeleton() 必须挂纯计时的 hardWatchdog（到点无条件揭开/诊断）。',
)
watchdog = block(feel, 'private val hardWatchdog')
need(bool(watchdog), '找不到 hardWatchdog：纯计时兜底被删了？')
need(
    'onPageFinished' not in watchdog,
    'hardWatchdog 里不许依赖 onPageFinished：那个回调可能永远不来（区块 1 黑屏的真因）。',
)
need(
    re.search(r'Verdict\.REVEAL[\s\S]{0,300}?hideSkeleton\(\)', watchdog),
    'hardWatchdog 判出 REVEAL 时必须撤骨架，否则到点了还盖着。',
)
need(
    re.search(r'fun showSkeleton\(\)[\s\S]{0,1500}?startRevealWatch\(\)', feel),
    'showSkeleton() 必须启动探测式揭开（startRevealWatch）：不能只等 load 事件。',
)
need(
    re.search(r'feel\.showSkeleton\(\)[\s\S]{0,300}?load\(intentUrlOrNull', main_act),
    '冷启动必须先 showSkeleton() 再 load()：反过来的话首帧会白闪。',
)

# ── 区块 9 的验收资产必须存在（防止"文档被误删，检查表形同虚设"）────────────
for asset in (
    'docs/REGRESSION.md',
    'docs/RELEASE-CHECKLIST.md',
    'scripts/verify.sh',
    'app/src/test/java/com/nhyzcms/clubapp/MainActivityShellTest.kt',
):
    need((ROOT / asset).exists(), f'缺验收资产：{asset}（区块 9 交付物）')

if problems:
    for p in problems:
        print(f'[预检-regress][失败] {p}')
    sys.exit(1)

print('[预检-regress] 三条红线规则全部通过 ✅')
PY

if [ "$fail" -eq 0 ]; then
  echo "[预检-regress] 全部通过 ✅"
else
  echo "[预检-regress] 存在失败项 ❌"
fi
exit "$fail"
