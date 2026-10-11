# 区块 1 开发报告：整体骨架

- **分支**：`feat/01-shell-skeleton`（已合并到 `main`，合并点 `a5b817d`）
- **日期**：2026-10-11
- **仓库**：`nhyzcms/app`（新建，之前完全空仓）
- **一句话**：搭好了“能编译、能装、能打开网站”的最小原生壳 + 完整设计文档，为后续每一块提供了骨架与规矩。

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
| `.github/workflows/android.yml` | CI：debug 构建 + tag 自动发 Release（收尾 PR #1 启用） |

### 1.2 文档（共 9 份）

| 文档 | 价值 |
|---|---|
| `README.md` | 项目是什么、为何不用 Electron、目录、快速开始、状态表、上手路径 |
| `docs/ARCHITECTURE.md` | 分层、8 条架构决策（ADR）、与网站的 4 项契约、6 个关键流程 |
| `docs/UPDATE-STRATEGY.md` | **三级更新模型** + “什么改动要发 APK”决策表 + 发布/回滚 SOP |
| `docs/SECURITY.md` | 威胁模型、WebView 安全基线、导航规则、自查清单 |
| `docs/ROADMAP.md` | 10 个区块的任务拆分与验收标准 |
| `docs/BUILD.md` | 本地/CI 构建、keystore 生成与 Secrets、版本号规则、常见故障 |
| `docs/CI-WORKFLOW.md` | CI 行为、发版前置条件与 403 踩坑沿革 |
| `docs/APP-MANIFEST.md` | 远程配置清单的字段契约 + 示例 + 校验规则 |
| `docs/FEATURE-MATRIX.md` | **28 行功能台账**（验收“全部功能”的唯一依据） |

---

## 2. 关键结论与决策（需你知悉）

### 2.1 Electron 做不了 Android（已换方案）

Electron 只能打包 Windows/macOS/Linux 桌面端，**无法产出 APK**。改用：
**Kotlin + 系统 WebView**，原生壳 3~5 MB，零第三方运行时依赖，正好适配你 2 核/2G/3M 的服务器。

### 2.2 证书：**故意不做固定**

实测你站点证书是 ZeroSSL DV，`SAN = IP:8.153.145.175`，**有效期至 2026-11-26（只有 90 天）**。
做证书固定会让我种日期全量用户直接报错，所以采用“HTTPS 强制 + 系统信任链 + 失败时就报错”，
并在 `onReceivedSslError` 里明确 cancel（绝不 proceed）。详见 SECURITY.md §2。

### 2.3 热更新的关键前提（重要）

App 界面本身就来自你的网站，所以**网站发版 = App 内容更新**（L0 热更新天然成立）。
但有一个坑：**SPA 入口 HTML 目前没有 `Cache-Control` 头**，WebView 可能用启发式缓存把旧入口留一段时间，
表现为“网站更新了但 App 还是旧页面”。建议在区块 8 给 `/` 加 `Cache-Control: no-cache`。

### 2.4 手机版 UI ≠ 全部功能（已知，靠区块 8 解决）

现状手机版只有 4 页（首页/新闻/活动/我的）；任务、私信、审核、反馈、全景图、问卷、加入申请等**无入口**。
方案：**在网站侧新增 “App 模式 UI”**（底部 Tab 覆盖全站入口），而不是在原生壳里画导航。

### 2.5 `minSdk 26`（Android 8.0）

因为要支持纯 XML 自适应图标（仓库里不存二进制 PNG，便于审计），且 2017 年后机型足够覆盖。
如果你希望支持更老的机器，告诉我，我会加一套 PNG 图标并降到 minSdk 24。

---

## 3. 我发现的网站侧问题（需要你安排）

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| 1 | **证书 2026-11-26 到期** | 到期后 App（及浏览器）全量报错 | 提前续期；不需要动 APK |
| 2 | SPA 入口 HTML 无缓存策略 | 热更新可能延迟生效 | 给 `/` 响应加 `Cache-Control: no-cache` |
| 3 | `/app/manifest.json` 会被 SPA 兜底路由吞掉（实测返回 200 但是 index.html） | 更新清单不能放这个路径 | 已改用 `/app-update/`（nginx 静态，不经 Django） |
| 4 | 手机版 UI 只有 4 页 | 无法满足“全部功能” | 区块 8：新增 App 模式 UI |
| 5 | 无 Service Worker | 断网时页面完全不可用 | App 已提供原生错误面板兜底；完整离线不在本期范围 |
| 6 | `client_max_body_size 20M` | 上传超 20 M 会被 nginx 拒 | 区块 2 会在本地前置提示 |

---

## 4. 如何验证本块

> ⚠️ **诚实说明**：代码在提交时从未被编译过（开发环境无 Android SDK，
> 而你的服务器只有 2G 内存——在上面跑 Gradle 会吃光内存）。
> **第一次真实编译发生在 CI**（PR #1）。若 CI 报错，我在区块 2 开头先修它。

### 4.1 CI 已启用

`main` 上已存在 `.github/workflows/android.yml`。在 Actions 页面可看运行结果，
也可以点 **Run workflow** 手动触发。首次编译结果见 PR #1 的 Checks。

### 4.2 拿到 debug APK

1. Actions 里 `Debug APK` 作业绿了；
2. 下载 artifact `app-debug-apk` → 得到一个 `app-debug.apk`；
3. 传到安卓手机上安装（会提示“未知来源”，允许即可；debug 包与正式包包名不同，可共存）。

### 4.3 真机验收清单

| # | 操作 | 预期 |
|---|---|---|
| 1 | 打开 App | 直接进入 **手机版首页**（`/#/m`） |
| 2 | 顶部有细进度条 | 页面加载完自动消失 |
| 3 | 点“我的”→ 登录 | 能正常登录（Cookie 会话） |
| 4 | 浏览新闻/活动列表并点进详情 | 正常滚动、图片能加载 |
| 5 | 按返回键 | 回到上一页（不是直接退出）；首页再按才退出 |
| 6 | 点站内某个外链（如关于页里的外部链接） | 跳系统浏览器，并提示“已在系统浏览器打开” |
| 7 | 飞行模式下点重试/重开 | 出现原生错误面板（非白屏）+ “重试”按钮可用 |
| 8 | 旋转屏幕 | 页面不重新加载（状态保留） |
| 9 | 系统切深色模式后重开 | 状态栏/窗口底色不刺眼 |
| 10 | 点网页里需要上传的按钮（如发新闻配图） | **目前无反应/提示** —— 预期，区块 2 解决 |

---

## 5. 已知限制（区块 1 故意不做，不要当成 bug）

- ❌ 文件上传/拍照：网页里“选择文件”按钮无效 → **区块 2**
- ❌ 文件下载：目前只弹提示 → **区块 2**
- ❌ 应用内自动更新（真的能下载并覆盖安装）→ **区块 3**
- ❌ 下拉刷新、启动页、沉浸式状态栏、返回顶部 → **区块 4**
- ⭕ 实时消息：WebSocket 在网页里能连，但无角标/通知 → **区块 5**
- ⛔ **所以现在装上它还不能使用全部功能，这是区块规划的预期**；
  “全部功能”的达成点是块 2 + 块 8。

---

## 6. 开发过程中的障碍（均已解决，留档）

### 6.1 空仓库 → 无法直接开 PR（已解决）

仓库当时完全为空，`main` 分支根本不存在，所以 `create_pull_request` 报
`PullRequest.base (invalid)`，而 API 也不支持把分支建在指定 SHA 上。

处置（2026-10-11 当日完成）：
1. 先在当时的分支上完成区块 1 全部提交，通过 compare 页面交付审查；
2. 审查通过后，用户授权建立 `main`：`create_branch(main, from=feat/01-shell-skeleton)`
   —— 即把 `main` 指向已审查的提交 `a5b817d`，**等价于 fast-forward 合并**，未引入任何未审查内容；
3. 收尾改动走正常 PR（[PR #1](https://github.com/nhyzcms/app/pull/1)）。

从现在起，本仓库的 PR 流程与普通仓库一致。

### 6.2 `.github/workflows/` 写入 403（已解决）

GitHub 要求写工作流的 token 必须具备 `workflow` 权限，最初不具备，报
`403 Resource not accessible by personal access token`（用两个不同路径验证确认）。
用户为 token 加上 `workflow` 权限后，工作流已迁入正式位置，旧副本 `ci/android.yml` 已删除。
详见 [docs/CI-WORKFLOW.md](../CI-WORKFLOW.md)。

---

## 7. 待你确认的 4 件事

1. **包名**：现为 `com.nhyzcms.clubapp`（发布后不可改）；
2. **应用显示名**：现为「南汇一中传媒社」（桌面图标下的名字）；
3. **图标**：现为白底品牌蓝色的“相机”矢量图，品牌色暂用 `#1E63E9`；
   如果你想要与网站一致的品牌色，给我 `cobalt.css` 里的 `--brand` 值（或我下次直接去取）；
4. **起始页**：现为手机版首页 `/#/m`。区块 8 做好 App 模式 UI 后，我会改成 `/#/app`。

---

## 8. 下一块（区块 2）预告

把“全部功能所需的能力”接上：文件选择与拍照上传（`onShowFileChooser` + 运行时权限）、
下载（`DownloadManager` + 会话 Cookie）、长按保存/分享、上传体积前置提示。
预期产出一个“可以上传新闻配图、可以下载审核导出”的真实可用版本。

---

## 9. 回滚

- 区块 1 内容已在 `main`（提交 `a5b817d`），要回退就 revert 这个提交；
- 收尾改动在 PR #1，不合并即不生效；
- 本块对网站与服务器**零影响**（仅新增了一个仓库，未动线上任何服务）。

---

## 附：时间线（当日实际发生）

| 时间 | 事件 |
|---|---|
| 06:46 | 建分支 `feat/01-shell-skeleton`，开始搭骨架（15 个提交） |
| 06:51 | 撞上 `.github/workflows/` 403，工作流暂存 `ci/android.yml` |
| 06:53 | 交付 compare 审查链接（因空仓库无 `main`，PR 无法创建） |
| 06:54 | 用户授予 `workflow` 权限并解除主分支限制 |
| 06:54 | 建立 `main`（指向已审查提交），发起 **PR #1** 启用 CI，触发首次真实编译 |
