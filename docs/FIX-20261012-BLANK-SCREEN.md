# 修复：进 App 后一片黑（2026-10-12 第二起）

> 现象：装上带上一处修复（PR #10）的包后**不再闪退**，但进 App 后屏幕**全黑**，
> 下拉刷新"没反应"，双击返回退出正常。
> 设备：HUAWEI P40 Pro+ / HarmonyOS 4.2 = Android 12 / API 31（系统处于深色模式）。

## 1. 排查过程（先把"不是谁的问题"钉死）

1. **站点是好的**：用本机的系统 WebView（与 App 同一个内核）打开 `https://8.153.145.175/#/m`，
   移动版首页正常渲染（顶栏 / banner / 四个快捷入口 / 最新动态 / 底部导航都在）。
   —— 期间一度怀疑"站点新版分包把 chunk 漏部署了"，实际是我拼 URL 少了 `.chunk` 段，
   真实产物 `/static/9686.<hash>.chunk.js` 等 **全部 200**。教训：**先用真实 URL 复验再下结论**。
2. **壳是问题所在**：同一台设备、同一个引擎，网页渲染正常而 App 里全黑 ⇒ 黑的是壳自己画的东西。

## 2. 根因

**骨架屏浮层盖住了网页，而且一直没被揭开。**

```kotlin
// 修复前（FeelHost）
fun showSkeleton() {
    pageLoaded = false
    webView.visibility = View.INVISIBLE   // ← 把 WebView 藏起来
    skeleton.visibility = View.VISIBLE    // ← 盖上骨架浮层（深色模式下是 #080F17）
}
// 而唯一的揭开点挂在 onPageFinished 上：
fun onPageRendered() { pageLoaded = true; ...; hideSkeleton() }   // ← 由 onPageFinished 调用
```

两条链路一起把人坑了：

1. **`onPageFinished` 来得非常晚**：它要等网页的 `load` 事件，也就是**所有子资源**
   （首页大图、live2d 模型…）都加载完才触发。本站上行只有 3 M，手机网络下这个事件迟迟不来
   → `hideSkeleton()` 一直没被调用。
2. 骨架屏用的是**资源色** `@color/window_background`（深色模式下 = `#080F17`），
   它是编译进布局的固定颜色，不会跟着运行时配色（网页 `meta theme-color` 探测）变
   → 即使配色探测早已把窗口底色改成白色，浮层本身仍然是黑的 → 用户看到"一片黑"。

顺带解释了另一个症状：`pageLoaded` 一直是 false，
`PullToRefreshPolicy.canPull(pageLoaded = false)` 判定"不可用" → **"下拉刷新没反应"**。

## 3. 修复（`shell/feel/FeelHost.kt` + 新增 `shell/feel/ContentProbe.kt`）

| # | 改动 | 为什么 |
| --- | --- | --- |
| ① | **绝不隐藏 WebView、也不对它的 alpha 做动画**；骨架屏只作为"盖在上面的浮层" | WebView 走自己的绘制通道，被设成 INVISIBLE/加硬件层淡化后，部分 ROM 会一直画不出内容 |
| ② | **不再等 `onPageFinished`**：新增首屏看护，每 1.5 s 用固定脚本问网页"画出内容了吗"（`ContentProbe`），一画出就立刻揭开骨架，并把 `pageLoaded` 置为 true | `load` 事件不可靠时，用"DOM 里有没有内容"这个直接信号 |
| ③ | 看门狗：15 s 到点还没内容 → 强制揭开骨架 | 无论如何不能让用户永远盯着黑屏 |
| ④ | 兜底收起：骨架淡出动画之外再挂一个延时收起（带 token 防误收） | 动画被打断时浮层也不会永远盖着 |
| ⑤ | 15 s 仍无内容 → 屏幕底部弹出**诊断浮层**（点一下复制全部诊断文本） | 用户端没有 adb、读不到 logcat，一段可复制的诊断文本就是唯一线索 |

判据（`ContentProbe.looksUnrendered`）：`body.innerHTML` 长度 < 40 且 `#root` 子节点 ≤ 0；
取不到（-1）时**不下结论**，避免误判导致骨架提前揭开。
诊断文本包含定位必需的四项：当前地址、网页脚本开关、**WebView 内核版本**、
以及 `#root 子节点 / body 长度 / script 标签数 / 站点打包全局是否存在`。

## 4. 验证

- 本地：`scripts/preflight.sh` ✅、括号/字符串/注释配平 ✅、
  `/workspace/klocaltest.sh` 真实 Kotlin 编译 + JVM 单测 **127 tests OK**（新增 `ContentProbeTest` 7 例）。
- CI：preflight + `:app:testDebugUnitTest` + `:app:assembleDebug`。
- 真机（验收点）：冷启动进 App，**1~3 秒内看到网页内容**（不再黑屏）；
  下拉刷新可用；再次冷启动仍正常。
  若仍是黑屏：会在 15 秒后出现底部诊断浮层，点一下复制、发回来即可定位。

## 5. 教训

1. **不要用"资源色"去做覆盖整个屏幕的浮层**：它不会跟随运行时配色，
   一旦揭开失败就是"黑了整个屏"。骨架屏这类浮层要保证"一定会被撤掉"。
2. **`onPageFinished` 不是"页面可以看了"的信号**，它绑定 `load` 事件（含所有子资源）。
   要判断"画出来了没"，直接问 DOM（本 PR 的 `ContentProbe`），或用 `onPageCommitVisible`。
3. **不要让 WebView 参与 alpha/可见性动画**（隐蔽 ROM 差异，且一旦出问题表现为"永久黑屏"）。
4. 站点侧待办（另有 PR）：首页大图与 live2d 模型做懒加载/缩略图，别让 `load` 事件被拖住。
