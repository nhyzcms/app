# 区块 1 开发报告：整体骨架

- **分支**：`feat/01-shell-skeleton`（代码）+ `chore/01b-enable-ci`（CI 收尾，PR #1）
- **日期**：2026-10-11
- **仓库**：`nhyzcms/app`（新建，之前完全空仓）
- **一句话**：搭好了“能编译、能装、能打开网站”的最小原生壳 + 完整设计文档，
  并在 CI 上完成了**首次真实编译（已绿）**。

---

## 1. 这块交付了什么

### 1.1 代码与构建

| 文件 | 作用 |
|---|---|
| `settings.gradle.kts` / `build.gradle.kts` / `gradle.properties` | Gradle 工程骨架（AGP 8.7.3 + Kotlin 2.0.21 + JDK 17） |
| `version.properties` | 版本号唯一真源（当前 `1 / 0.1.0`） |
| `app/build.gradle.kts` | 模块配置：包名、minSdk 26 / targetSdk 34、签名注入、R8、依赖（仅 4 个） |
| `app/proguard-rules.pro` | 保留入口与桥接方法、剔除 release 日志 |
| `app/src/main/AndroidManifest.xml` | 权限只留 INTERNET/网络状态；深链；禁备份；明文流量禁止 |
| `res/xml/network_security_config.xml` | 只信系统根证书 + 禁止明文（debug 才允许 user CA） |
| `res/xml/data_extraction_rules.xml` | 云备份/换机迁移全排除（不把登录态带走） |
| `res/layout/activity_main.xml` | WebView + 顶部进度条 + 原生错误面板 |
| `res/values/*`、`values-night/*` | 字符串、颜色、主题（跟随系统深浅色） |
| `res/drawable/ic_launcher_foreground.xml` + `mipmap-anydpi-v26/v33` | **纯矢量**自适应图标（仓内零二进制资源） |
| `MainActivity.kt` | 壳入口：加载、返回键、深链、状态恢复、错误面板、WebView 安全设置 |
| `shell/AppConfig.kt` | 站点地址/主机白名单/UA 标记（改地址只改这一处） |
| `shell/NavPolicy.kt` | **导航裁决（纯函数，无 Android 依赖）**，后续可直接写 JVM 单测 |
| `shell/WebShellClient.kt` | 导航拦截、外链交浏览器、TLS 失败显式放弃 |
| `shell/WebShellChromeClient.kt` | 进度回调 + 控制台日志（debug） |
| `shell/ShellErrorKind.kt` | 错误分类（网络/证书/明文/其它） |
| `.github/workflows/android.yml` | CI：预检 + debug 构建 + tag 自动发 Release |
| `scripts/preflight.sh` | **静态预检**（XML/资源引用/已知陷阱，5 秒跑完） |
| `scripts/ci-prepare-android-sdk.sh` | CI 用的 SDK 准备（接受许可 + 写 local.properties） |
| `scripts/ci-extract-errors.sh` | 从构建日志里抽出关键报错（供失败时贴 PR 评论） |

### 1.2 文档（共 9 份）

README、ARCHITECTURE（8 条 ADR + 与网站的 4 项契约）、UPDATE-STRATEGY（三级更新模型）、
SECURITY（威胁模型 + 自查清单）、ROADMAP（10 个区块）、BUILD（构建/签名/发版 SOP）、
CI-WORKFLOW、APP-MANIFEST（远程配置字段契约）、FEATURE-MATRIX（**28 行功能台账**）。

---

## 2. 关键结论与决策

### 2.1 Electron 做不了 Android（已换方案）

Electron 只能打包 Windows/macOS/Linux 桌面端，**无法产出 APK**。改用
**Kotlin + 系统 WebView**：原生壳 3~5 MB，零第三方运行时依赖，适合你 2 核/2G/3M 的服务器。

### 2.2 证书：**故意不做固定**

站点证书是 ZeroSSL DV，`SAN = IP:8.153.145.175`，**2026-11-26 到期（90 天一轮）**。
固定指纹或中间 CA 都会在换证日让全部用户失联，所以采用“HTTPS 强制 + 系统信任链 + 失败就报错”，
并在 `onReceivedSslError` 里明确 cancel（绝不 proceed）。详见 SECURITY.md §2。

### 2.3 热更新的关键前提

App 界面来自你的网站，所以网站发版 = App 内容更新（L0 热更新天然成立）。
但 **SPA 入口 HTML 目前没有 `Cache-Control`**，WebView 可能缓存旧入口，
表现为“网站更新了但 App 还是旧页面”。建议区块 8 给 `/` 加 `Cache-Control: no-cache`。

### 2.4 手机版 UI ≠ 全部功能

现状手机版只有 4 页；任务、私信、审核、反馈、全景图、问卷、加入申请等**无入口**。
方案：**在网站侧新增 App 模式 UI**（底部 Tab 覆盖全站），不在原生壳里画导航。

### 2.5 `minSdk 26`（Android 8.0）

为了能用纯 XML 自适应图标（仓库不存二进制 PNG）且 2017 年后机型覆盖充分。

---

## 3. 我发现的网站侧问题（需要你安排）

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| 1 | **证书 2026-11-26 到期** | 到期后 App 与浏览器全量报错 | 提前续期（不需要动 APK） |
| 2 | SPA 入口 HTML 无缓存策略 | 热更新可能延迟生效 | 给 `/` 加 `Cache-Control: no-cache` |
| 3 | `/app/manifest.json` 被 SPA 兜底路由吞掉 | 更新清单不能放这里 | 已改用 `/app-update/`（nginx 静态，不经 Django） |
| 4 | 手机版 UI 只有 4 页 | 无法满足“全部功能” | 区块 8 |
| 5 | 无 Service Worker | 断网完全不可用 | App 已提供原生错误面板兜底 |
| 6 | `client_max_body_size 20M` | 上传超 20 M 被 nginx 拒 | 区块 2 在本地前置提示 |

---

## 4. 首次真实编译：失败 6 次，已全部修复

代码在提交时**从未被编译过**（开发环境无 Android SDK，你的服务器只有 2G 内存
不适合跑 Gradle），所以 CI 是第一次真实编译。过程中一共红了 6 轮，每个坑都记下来了：

| 序 | 报错/现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | “安装 Android SDK”步骤 8 秒失败 | `android-actions/setup-android@v3` 年久失修；而 runner 本就预装 SDK | 删掉该 action，改自写 `ci-prepare-android-sdk.sh` |
| 2 | `Task 'versionInfo' not found in project ':app'` | 我把任务写成了 `:app:versionInfo`，而它注册在**根项目** | CI 改为纯 shell 打印版本号（不再依赖 Gradle 任务） |
| 3 | `sdkmanager --licenses` 失败 | runner 上该命令不靠谱，而 AGP 自动下载组件必须先接受许可 | 直接写 `licenses/android-sdk-license` 文件（不依赖网络与权限试探） |
| 4 | `colors.xml:5:62 Error: The string "--" is not permitted within comments` | XML 注释里写了 CSS 变量名（含两个连续短横线） | 清掉；并加入了预检规则 |
| 5 | `colors.xml:9:31` **同一错误又出现** | 我在上一轮的注释里**把报错原文照抄了进去**，又写了 `"--"`（自掘坟墓） | 彻底清掉，并在注释里留下了警告 |
| 6 | `Null cannot be a value of a non-null type 'android.webkit.WebViewClient'` | API 35 上 `setWebViewClient` 参数是 `@NonNull`，我传了 `null` | 改为传空实现（切断回调），而不是 null |

另有 1 个**预先拦下**的隐患：`WebSettings` 的 safe browsing 只有 setter 没有 getter，
`safeBrowsingEnabled = true` 这种属性语法会直接编译失败 → 已改为 `setSafeBrowsingEnabled(true)`。

### 最终结果

```
Debug APK ✅ success（run #20，耗时 1 分 45 秒，27 个 Gradle 任务全部执行完成）
```

产物在 Actions → 该次运行 → Artifacts → `app-debug-apk`（保留 14 天）。

---

## 5. 为了防止再白跑 CI，新增了两个机制

1. **静态预检**（`scripts/preflight.sh`，CI 已接入，Gradle 之前先跑）
   - XML 语法 + 注释里禁止连续两个短横线（就是坑 4/5）；
   - Kotlin 里每个 `R.xxx.yyy` 引用都必须在资源里真实存在；
   - 已知陷阱清单（如 `setWebViewClient(null)`、`safeBrowsingEnabled =`）；
   - 仓库里不得出现 `.jks` / `.keystore` / `local.properties`。
   > 维护方式：**每踩一个新坑，就往脚本里加一条规则**。这样错误只会发生一次。
2. **失败自动贴日志到 PR 评论**
   - Actions 原始日志需要仓库管理员权限才能用 API 读，而 PR 评论公开可读；
   - 失败时 `scripts/ci-extract-errors.sh` 会滤掉堆栈帧，只留关键报错行。

---

## 6. 如何验证本块（真机）

1. 打开 PR #1 的 Actions 运行 → 下载 artifact `app-debug-apk` → 得到 `app-debug.apk`；
2. 传到安卓手机安装（允许“未知来源”；debug 包名带 `.debug`，可与后终正式包共存）；
3. 按下面的清单验：

| # | 操作 | 预期 |
|---|---|---|
| 1 | 打开 App | 直接进入**手机版首页**（`/#/m`） |
| 2 | 顶部细进度条 | 加载完自动消失 |
| 3 | 点“我的”→ 登录 | 能正常登录（Cookie 会话） |
| 4 | 浏览新闻/活动列表并点进详情 | 正常滚动、图片能加载 |
| 5 | 按返回键 | 回到上一页；首页再按才退出 |
| 6 | 点站内某个外链 | 跳系统浏览器，并提示“已在系统浏览器打开” |
| 7 | 飞行模式下重开 | 出现原生错误面板（非白屏）+ “重试”可用 |
| 8 | 旋转屏幕 | 页面不重新加载 |
| 9 | 系统切深色模式后重开 | 状态栏/窗口底色不刺眼 |
| 10 | 点网页里需要上传的按钮 | **目前只会弹提示** —— 预期，区块 2 解决 |

---

## 7. 已知限制（区块 1 故意不做）

- ❌ 文件上传/拍照：区块 2；❌ 文件下载：区块 2；
- ❌ 应用内自动更新：区块 3；❌ 下拉刷新/启动页/沉浸式：区块 4；
- ⭕ 实时消息：能连但无角标/通知 → 区块 5；
- ⛔ **所以现在装上它还不能使用全部功能，这是规划的预期**；
  “全部功能”的达成点是 **区块 2（壳能力）+ 区块 8（网站 App 模式 UI）**。

---

## 8. 待你确认的 4 件事

1. **包名**：现为 `com.nhyzcms.clubapp`（发布后不可改）；
2. **应用显示名**：现为「南汇一中传媒社」；
3. **图标**：现为白底品牌蓝的“相机”矢量图，品牌色暂用 `#1E63E9`
   （想要与网站一致，告诉我 `cobalt.css` 的 `--brand` 值，或我下次去取）；
4. **起始页**：现为 `/#/m`；区块 8 做好 App 模式 UI 后改成 `/#/app`。

---

## 9. 下一块（区块 2）预告

接上“全部功能所需的能力”：文件选择与拍照上传（`onShowFileChooser` + 运行时权限）、
下载（`DownloadManager` + 会话 Cookie）、长按保存/分享、上传体积前置提示。
目标是产出一个“能传新闻配图、能下载审核导出”的真实可用版本。

---

## 10. 回滚

- 区块 1 代码已在 `main`（提交 `a5b817d`）；CI 收尾在 PR #1；
- 本块对网站与服务器**零影响**（只新建了一个仓库，未动线上任何服务）。

---

## 附：当日时间线

| 时间（UTC） | 事件 |
|---|---|
| 06:46 | 建分支 `feat/01-shell-skeleton`，从空仓搭骨架（15 个提交） |
| 06:51 | 撞上 `.github/workflows/` 写入 403，工作流暂存 `ci/android.yml` |
| 06:53 | 交付 compare 审查链接（空仓无 `main`，PR 无法创建） |
| 06:54 | 用户授予 `workflow` 权限并解除主分支限制 |
| 06:54 | 建立 `main`（指向已审查提交），发起 **PR #1**，首次触发 CI |
| 06:55 ~ 07:11 | CI 连续失败 6 轮（见第 4 节表格），逐一定位修复 |
| 07:15 | ✅ **Debug APK 构建成功**（run #20，1 分 45 秒） |
| 07:20 | 接入静态预检，并把踩坑记录写入本报告 |
