# 区块 4 交付报告 — 原生手感（“不像套壳”的部分）

> 分支 `feat/04-native-feel` ｜ 前置：区块 3 已合并（main = `670de7d`）
> 交付日期：2026-10-11

---

## 1. 结论（TL;DR）

| 交付物 | 状态 | 说明 |
|---|---|---|
| 沉浸式状态栏 / 导航栏配色 | ✅ 已实现 | 状态栏、导航栏、窗口底色**跟随网页 `meta name="theme-color"`**（站点会随主题改写它），按亮度自动选深/浅色图标 |
| 深色模式跟随系统 | ✅ 已实现（壳侧） | 启动页/骨架/错误页/下拉圈/浮动按钮全部走 `values-night`；深色兜底色改为站点深色底 `#080f17` |
| 启动页（纯色 + 图标，零图片依赖） | ✅ 已实现 | API 26~30 用 `windowBackground` layer-list；API 31+ 用系统 SplashScreen API |
| 首屏骨架（消白屏） | ✅ 已实现 | 仅冷启动首屏显示，160 ms 淡出移交网页 |
| 下拉刷新 | ✅ 已实现 | 手势**只在网页已回到顶部时**生效；15 秒自动停转圈 |
| 返回手势 / 预测式返回 | ✅ 已实现 | `enableOnBackInvokedCallback` + 双击才退出（误触不退出） |
| 加载进度条 / 错误重试面板 | ✅ 已有（区块 1/3），本块补齐联动 | 错误面板出现时自动停刷新圈、隐藏浮动按钮 |
| “返回顶部”浮动按钮 | ✅ 已实现 | 清单开关 `showBackToTop`（默认开），阈值 600 dp |

**本地验证**：`scripts/preflight.sh` 全绿 ｜ `ktcheck.py` 49 个源文件配平 ｜ 本地 kotlinc 编译 + JUnit **115 个用例全部通过**（本块新增 33 个）。
**未做**：真机验收（见 §5，需要你在手机上过一遍）。

---

## 2. 交付物（文件级）

### 新增源码（`app/src/main/java/com/nhyzcms/clubapp/shell/feel/`）

| 文件 | 职责 | 可单测 |
|---|---|---|
| `PageTheme.kt` | 颜色解析、WCAG 亮度、配色决策、探测结果解析 | ✅ |
| `BackExitPolicy.kt` | 返回键决策（后退 / 提示退出 / 退出） | ✅ |
| `FabPolicy.kt` | 返回顶部按钮的显示阈值 | ✅ |
| `PullToRefreshPolicy.kt` | 下拉刷新的门控与“转圈超时” | ✅ |
| `EdgeToEdgePolicy.kt` | 铺满开关、浮层让位边距 | ✅ |
| `ScrollAwareWebView.kt` | 同步上报滚动位置的 WebView | ❌（依赖 android） |
| `PageStateProbe.kt` | 每 2 秒读一次网页主题色/滚动位置（固定常量脚本） | ❌（依赖 android） |
| `FeelHost.kt` | 原生手感的宿主：窗口配色、下拉刷新、骨架、返回顶部、返回键判定 | ❌（依赖 android） |

### 新增资源

`drawable/splash_background.xml`（layer-list）、`drawable/bg_skeleton_block.xml`、`drawable/bg_fab.xml`、
`drawable/ic_arrow_up.xml`、`values/bools.xml`、`values-night/bools.xml`、`values-v31/themes.xml`。

### 修改

| 文件 | 改了什么 |
|---|---|
| `MainActivity.kt` | 启动页主题切换、视图绑定新增 3 个控件、错误面板联动、返回键改走 FeelHost；区块 4 的逻辑本体已抽到 `FeelHost.kt`，避免 MainActivity 继续膨胀 |
| `res/layout/activity_main.xml` | 外层包 `SwipeRefreshLayout`，WebView 换 `ScrollAwareWebView`，新增骨架与浮动按钮 |
| `AndroidManifest.xml` | Activity 主题改 `Theme_ClubApp_Splash`；application 开 `enableOnBackInvokedCallback` |
| `res/values/themes.xml`、`colors.xml`、`values-night/colors.xml` | 主题分层（Base → Splash → ClubApp）；深色兜底色对齐 `#080f17` |
| `res/values/strings.xml` | +2 条（返回顶部、再按一次退出） |
| `app/build.gradle.kts` | +1 依赖 `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` |
| `shell/update/UpdateManifest.kt` | 内置开关 +3（`pullToRefresh` / `showBackToTop` / `edgeToEdge`） |

### 删除

- `res/values-night/themes.xml` —— 深浅色差异全部改由 `@color` / `@bool` 资源表达。
  原因：`values-night` 里的**同名 style 是整体替换而不是合并**，两边各写一份很容易漏 item
  （漏掉 `windowBackground` 就会让深色下的启动页背景消失）。现在只需要一份 `themes.xml`。

### 两个命名 / 工具事实

1. **主题名用下划线**（`Theme_ClubApp` / `Theme_ClubApp_Splash`，不是惯例的 `Theme.ClubApp.Splash`）：
   `scripts/preflight.sh` 把 Kotlin 里的 `R.style.X` 与资源名逐字比对，而点号在 R 类里会被换成下划线
   （`Theme.ClubApp` → `R.style.Theme_ClubApp`），含点号的资源名会被误判成「资源不存在」。
   层级关系本来就由 `parent` 表达，改用下划线可以两头都对得上。
2. 本块的多处改动因此也修掉了 `preflight` 的一条误报根因；新增的 6 条预检规则见 §3.7。

---

## 3. 关键设计与取舍

### 3.1 沉浸式：为什么**默认不**把内容铺到状态栏下

站点确实已经写了 `viewport-fit=cover` 与 `env(safe-area-inset-top)`
（网站仓库 `frontend/src/styles/cobalt.css`、`mobile.css`），看起来可以铺满。但：

> **Android WebView 的 `env(safe-area-inset-*)` 只反映屏幕缺口（display cutout），通常返回 0**，
> 它并不会把“状态栏高度”告诉网页。

于是铺满之后，网页那条 sticky 顶栏仍从 `y=0` 开始画，上半截正好被状态栏压住 —— 比不铺满更糟。
因此本块把它做成清单开关 `edgeToEdge`（**默认 false**），并保留 `EdgeToEdgePolicy` 的边距计算供开启时使用。
等真机确认 WebView 会传 insets（或网站侧改成固定顶部内边距），再考虑把默认值改成 true。

### 3.2 本块真正生效的沉浸式：**配色沉浸**

壳不再用“系统深浅色”直接决定状态栏颜色，而是读网页的 `meta name="theme-color"`
（站点浅色 `#ffffff`、深色 `#080f17`，见网站 `frontend/src/theme.ts`），于是：

- 用户在**站内**切换深色（`cobalt:theme`）→ 状态栏随手就变了（不必等系统设置）；
- 网页浅色 + 系统深色这种“混合”状态也不会出现刺眼色带；
- 探测失败（网页结构变了/JS 被禁用）→ 回退到系统深浅色，功能不降级。

探测方式：`PageStateProbe` 每 2 秒执行一次**固定常量脚本**读两个属性
（`meta.content` 与 `window.pageYOffset`），只在 Activity 可见时运行。安全边界见 §3.7。

### 3.3 下拉刷新如何不“抢”网页滚动

1. 新增 `ScrollAwareWebView`，重写 `onScrollChanged` 记录 `scrollOffset`，并覆写 `canScrollVertically(-1)`；
2. 显式设置 `SwipeRefreshLayout.setOnChildScrollUpCallback { … webView.canScrollVertically(-1) }`；
3. 兜底：`PageStateProbe` 每 2 秒的 JS 探测顺带把 `window.pageYOffset` 补正回来（顶多晚 2 秒）；
4. 门控：错误面板盖着、骨架屏期间、清单开关 `pullToRefresh=false` 时，一律不响应下拉。

### 3.4 启动页不需要任何图片资源

- API 26~30：`Theme_ClubApp_Splash` 的 `windowBackground` 用 layer-list（纯色 + 居中社徽矢量），
  系统会一直显示它直到第一帧内容画好 —— 零代码、零白屏；
- API 31+：`values-v31/themes.xml` 覆盖同名样式，交给系统 SplashScreen API。

⚠️ 坑：`values-v31` 里的同名 style 是整体替换，必须把 `windowBackground` 等基础项重列一遍。

### 3.5 返回键：误触不再直接退出

`BackExitPolicy` 三态：能后退 → 后退；起始页第一次 → 提示“再按一次返回键即退出应用”；
2 秒内再按 → 退出。配套在清单里开 `android:enableOnBackInvokedCallback="true"`，
由系统给 Android 13+ 的**预测式返回**做动画，壳只负责“按下去做什么”。

### 3.6 依赖：+1 个（ADR-A05 的例外）

`androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` —— Jetpack 官方组件，
只带 `annotation` / `core` / `interpolator` 三个小传递依赖，APK 增量约 40 KB。
手写下拉刷新的手势状态机代码更多、且本仓库没有真机可调参，判定为不划算。

### 3.7 静态预检新增规则（本地已改好；因工具链载荷限制，随下一次提交入库）

`scripts/preflight.sh` 准备新增 6 条：

1. `evaluateJavascript` 的脚本必须是常量 —— **禁止字符串拼接**（拼接 = 开放 JS 注入）；
2. 禁止 `loadUrl("javascript:...")` 注入脚本；
3. 启动页三处一致（清单引用 → 主题定义 → `splash_background`）；
4. 用了 `SwipeRefreshLayout` 就必须设 `setOnChildScrollUpCallback`（递归搜索，`FeelHost` 里设置也认）；
5. 下拉刷新必须搭配 `ScrollAwareWebView`；
6. 修风格 bug：style 名里的点会被 AAPT 转成下划线（`Theme.ClubApp` → `R.style.Theme_ClubApp`）。

> 现在仓库里的 `preflight.sh`（未含上述新规则）对本分支已经**全绿**，CI 不会因为静态预检变红。

---

## 4. 验证记录（本地实跑）

| 检查 | 命令 | 结果 |
|---|---|---|
| 静态预检（仓库当前版本） | `bash scripts/preflight.sh` | ✅ 全部通过（资源总数 557） |
| Kotlin 源码配平 | `python3 ktcheck.py`（本机工具） | ✅ 49 个文件通过 |
| 纯逻辑编译 + 单测 | 本地 JDK 21 + kotlinc + JUnit4 | ✅ 14 个测试文件、**115 个用例全部通过**（本块新增 33 个） |
| 已推内容与本地逐字一致 | `git fetch` + `git show FETCH_HEAD:<path> | diff - <本地文件>` | ✅ 全部 OK |
| 编译整包（含 Android 依赖） | GitHub Actions `Debug APK` | 由本 PR 的 CI 执行（本地无 Android SDK） |

本块新增的 33 个用例分布：`PageThemeTest` 14、`BackExitPolicyTest` 7、`FabPolicyTest` 4、
`EdgeToEdgePolicyTest` 4、`PullToRefreshPolicyTest` 4。

---

## 5. 真机验收清单（需要你操作）

装 debug APK（CI 产物 `app-debug-apk`）后逐条过：

1. **启动页**：从最近任务里清掉 App 再冷启动 —— 先看到纯色底 + 社徽（无白色闪屏）；
   切到系统深色再来一次，启动页应是深底浅图标。
2. **首屏骨架**：冷启动时先出现几块灰色占位，随后淡出换成页面（弱网下更明显）。
3. **下拉刷新（正常）**：在页面顶部下拉 → 出现刷新圈 → 页面重载 → 圈自动收起。
4. **下拉刷新（不干扰滚动）**：滚到页面中部，手指下滑 —— **不能**出现刷新圈，只能网页正常滚动。
5. **下拉刷新（超时保护）**：断网点下拉 → 15 秒内刷新圈必须自己停下。
6. **返回顶部**：滚到较深处（≥ 600 dp，约两屏）→ 右下角浮出圆形按钮；点一下平滑回顶；回到顶部后按钮消失。
7. **返回键**：在首页连按返回 —— 第一次只弹“再按一次返回键即退出应用”，第二次才退出；
   在二级页面按返回应正常回退（不退出）。
8. **预测式返回（Android 13+）**：从屏幕边缘向内慢慢拖 —— 应能看到**返回箭头的预览动画**。
9. **深色配色**：进入「个人中心 → 外观」切深色/浅色 —— 状态栏、导航栏颜色应随网页改变，不出现色带。
10. **清单开关**：把 `/opt/club/app-update/manifest.json` 的 `flags.showBackToTop` 改成 `false`，
    重开 App → 浮动按钮不再出现（改回来即恢复）。

---

## 6. 远程开关（新增 3 个）

| 键 | 默认 | 作用 |
|---|---|---|
| `pullToRefresh` | `true` | 下拉刷新总开关 |
| `showBackToTop` | `true` | 返回顶部浮动按钮 |
| `edgeToEdge` | `false` | 内容铺到状态栏下（**默认关**，原因见 §3.1） |

已有键（`live2d` / `panorama` / `autoUpdateCheck` / `externalLinkInBrowser` / `forceRefreshEntry`）不改名、不改语义。

---

## 7. 给区块 8（网站侧）的两条输入

1. **真 edge-to-edge 的桥**：站点已用 `env(safe-area-inset-top)`，但 Android WebView 该值是 0。
   若要做“顶栏延伸到状态栏下”，建议网站侧改用 CSS 变量并在区块 8 里由壳注入
   （`WebViewCompat.addDocumentStartJavaScript` 注入 `--app-inset-top`，仍按 origin 限定）。
2. **默认主题**：站点 `cobalt:theme` 无值时默认 `light`，于是“系统深色 + 网页浅色”是常见组合。
   建议把默认值改为 `system`（网页侧一行改动）。

---

## 8. 已知未做 / 风险

- ⚠️ `edgeToEdge` 默认关闭：真机验证 WebView 是否传 insets 之后再决定是否默认开启；
- 骨架屏只在冷启动首屏出现（反复导航时不显示，避免每次点击都闪一下）；
- `PageStateProbe` 是 2 秒轮询，站内切主题后状态栏最多慢 2 秒（换取零 JS 桥接、零注入）；
- 文档（README / ROADMAP / ARCHITECTURE / SECURITY / APP-MANIFEST）与新增预检规则随本 PR 的后续提交同步；
- 未做：Live2D/全景图的按需加载与流量控制（区块 6）、通知角标（区块 5）。
