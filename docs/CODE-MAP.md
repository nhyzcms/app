# 代码地图（CODE-MAP）

> 面向读者：新加入的开发者，以及在本仓库上工作的 AI 助手。
> 目的：**用"我想改 X，该动哪个文件"的方式索引代码**，避免每次都从 `README.md` 的目录树里逐层找。
> 最后更新：2026-10-14（对应 `main` = 区块 6 之后的状态）。
> 配套阅读：`docs/ARCHITECTURE.md`（设计取舍）、`docs/ENGINEERING-ENV.md`（环境与工作流）、`docs/PITFALLS.md`（坑位）。

---

## 0. 三条分层的硬原则

1. **UI 与业务全在 Web 层**（`https://8.153.145.175/` 的 SPA）。壳只提供"网页做不到或做不好"的原生能力。
   理由：**只有 Web 层能热更新**（L0：网站发版即生效，不用重装 APK）。凡是能做成网页的，不要在壳里做。
2. **决策逻辑与副作用分离**。策略（决定"该不该、该去哪"）写成纯函数放 `shell/**/*Policy.kt`，
   宿主（真正去 touched WebView / 发通知 / 写文件）叫 `*Host.kt`。
   → 纯函数可以被 `/workspace/klocaltest.sh` 在本地 JVM 上真跑单测。
3. **单一真源**。跨文件共享的常量只允许有一处定义（例如更新主机白名单、Live2D 拦截前缀、FileProvider authority），
   其他地方必须引用它。多条真源会造成"改了 A 忘了 B"的静默 bug。

---

## 1. 入口与壳骨架（`app/src/main/java/com/nhyzcms/clubapp/`）

| 文件 | 职责 | 改动注意 |
|---|---|---|
| `ClubApp.kt` | `Application`：进程级初始化、崩溃自记录挂钩、通知渠道树立 | 这里做的事要"便宜"——冷启动路径上任何重活都会拖慢首屏 |
| `MainActivity.kt` | **唯一的 Activity**（`launchMode=singleTask`）：WebView 宿主、权限结果分发、`onShowFileChooser`、下载、返回键、错误面板 | 体量最大、耦合最多；**新逻辑优先抽到 `shell/**` 或 `*Host`**，不要把 MainActivity 继续养肥 |
| `shell/AppConfig.kt` | **常量单一真源**：站点 URL、UA 标记、允许的更新主机（`UPDATE_ALLOWED_HOSTS`）、Live2D 拦截前缀（`BLOCKED_ASSET_PREFIXES`）、FileProvider authority、超时等 | 改白名单/前缀**只改这里**；被 `PerfHost`、`ManifestFetcher` 等依赖，误删会连带编译失败 |
| `shell/WebShellClient.kt` | `WebViewClient`：URL 裁决（站内 vs 外部）、错误页、`shouldInterceptRequest` 挂载资源拦截 | 涉及安全边界（HTTPS 强制、SSL 错误绝不 `proceed()`） |
| `shell/WebShellChromeClient.kt` | `WebChromeClient`：文件选择、控制台日志、进度 | 文件选择必须把 `ValueCallback` 交回 `FileChooserHost` |
| `shell/ShellErrorKind.kt` | 错误分类（网络 / TLS / 服务不可达…）→ 错误面板文案 | 与错误面板一一对应，新增分类要同步文案 |
| `shell/DiagLog.kt` | 诊断轨迹落盘到 `filesDir/diag/`（有上限） | 只写"关键事件"，不要在里面做字符串拼接大对象 |
| `shell/CrashText.kt` | 崩溃文本格式化（**纯逻辑**，有单测） | 保持纯函数 |
| `shell/CrashReporter.kt` | 崩溃落盘 + 下次启动上报入口 | 不要在网络不可用时阻塞启动 |

**为什么强调 `singleTask`**：深链复用同一个实例，避免堆出多个 WebView（每个 WebView 都是几十 MB 内存，
本机目标设备内存紧张）。

---

## 2. 导航与"网页做不到"的原生能力（`shell/`）

| 文件 | 职责 | 关键约束 |
|---|---|---|
| `NavPolicy.kt` | **导航裁决纯函数**：站内 vs 站外 vs 拦截，`UrlParts` → `NavDecision` | 有单测；改规则先改测试 |
| `FileChooserHost.kt` | `onShowFileChooser` 的状态机 | `ValueCallback` **恰好回调一次**（所有出口收敛到 `deliver()`，用户取消也要回调 `null`） |
| `CameraCapture.kt` | 拍照：`FileProvider` 临时文件（`cacheDir/capture/`）+ `ACTION_IMAGE_CAPTURE` | 作为选择器的 `EXTRA_INITIAL_INTENTS`，与"选文件"并列呈现 |
| `PermissionsPolicy.kt` | 该在什么时候要哪个权限（相机；仅 API ≤ 28 的写存储） | 权限只有 4 个（含 `POST_NOTIFICATIONS`/`REQUEST_INSTALL_PACKAGES`），**不申请 `READ_MEDIA_IMAGES`** |
| `UploadLimit.kt` | 上传体积本地前置判定（对齐 nginx `client_max_body_size 20M`） | 站点改了上限要同步 |
| `DownloadPolicy.kt` | 下载落盘位置决策（按 SDK_INT / 是否有写权限） | **所有落盘必须经过 `destination()` 收口**，不要在别处拼路径 |
| `DownloadStarter.kt` | 交给系统 `DownloadManager` 下载 | **必须手动带 Cookie**，否则受权限文件 403 |
| `LongPressPolicy.kt` | 长按图片是否能"保存/分享" | 纯逻辑 + 单测 |
| `MediaSharePolicy.kt` | 分享决策（文件本体 vs 链接、文件名清洗） | 纯逻辑 + 单测 |
| `MediaSharer.kt` | 抓取图片字节 → `cacheDir/share/` → `FileProvider` → `ACTION_SEND`，失败退回分享链接 | 后台线程 + 带 Cookie/UA；失败必须优雅降级 |

---

## 3. 手感层（`shell/feel/`，"像原生 App"的那部分）

| 文件 | 职责 | 关键约束 |
|---|---|---|
| `FeelHost.kt` | 手感层宿主：把下面这些 policy 接到真实 View/WebView 上 | 体量大；改这里务必跑 `bash scripts/preflight-feel.sh`；**注意 Kotlin 属性自引用坑**（见 PITFALLS §1.4） |
| `PageTheme.kt` | 跟随网页 `meta theme-color` 改状态栏/导航栏配色 | 用 WCAG 亮度决定图标明暗，别硬编码 |
| `PageStateProbe.kt` | 探测页面状态（DOM 内容、滚动） | 间隔有**硬下限 1500 ms** |
| `ProbeCadence.kt` | 探测频率退避：`2s → 4s → 8s → 15s`（低内存档 30s），有变化立刻回快档 | 纯逻辑 + 单测；探测不能变成耗电元凶 |
| `ContentProbe.kt` | "页面有没有真实内容"的判定（**按 DOM 内容**，不看事件） | 纯逻辑 + 单测 |
| `RenderWatchPolicy.kt` | 首屏看护：多次复核（5/12/25/45 s）、连续 2 次空才判定异常、诊断浮层自动撤销 | 纯逻辑 + 单测；**不要退化成一次性判定**（会误报） |
| `BackExitPolicy.kt` | 返回键：网页能否消费 → 双击才退出 | 纯逻辑 + 单测 |
| `EdgeToEdgePolicy.kt` | 是否铺满（`edgeToEdge`） | **默认关**：WebView 的 `env(safe-area-inset-*)` 通常为 0，铺满会让 sticky 顶栏被状态栏压住 |
| `FabPolicy.kt` | "返回顶部"按钮的显隐阈值（滚动距离） | 纯逻辑 + 单测 |
| `PullToRefreshPolicy.kt` | 下拉刷新是否可用（超时自动停圈等） | 纯逻辑 + 单测 |
| `ScrollAwareWebView.kt` | 把 WebView 的滚动偏移同步给壳（供 FAB / 下拉刷新判断） | `setOnChildScrollUpCallback` 是"网页已滚到顶"的判定点 |
| `SplashPolicy`/`values*/themes.xml` | 启动页 | API 26–30 用 layer-list；API 31+ 用系统 SplashScreen。**零图片资源** |

---

## 4. 性能层（`shell/perf/`）

| 文件 | 职责 | 关键约束 |
|---|---|---|
| `PerfHost.kt` | 性能层宿主：资源拦截、缓存清理时机 | 依赖 `AppConfig.BLOCKED_ASSET_PREFIXES`（**曾经被误删过**，见 PITFALLS §7.3） |
| `HeavyAssetPolicy.kt` | 哪些请求算"重资源"（Live2D 素材）→ 返回空体 200 | **主文档永不拦**；白名单唯一真源在 `AppConfig` |
| `DeviceClass.kt` | 设备分级（内存/低内存档） | 纯逻辑 + 单测（`DeviceClassifierTest`） |
| `CacheTrimPolicy.kt` | 什么时候清理 WebView 缓存 | **默认什么都不做**（别自作聪明，清缓存会让用户重下载） |

---

## 5. 推送与角标（`shell/push/`，区块 5 + 修复 PR #19）

| 文件 | 职责 | 关键约束 |
|---|---|---|
| `PushHost.kt` | 推送状态机宿主：**看门狗每 15 s 重读 Cookie** 决定连/断；**兜底刷新每 90 s** 拉未读 | 曾经的致命 bug：只取一次 Cookie 快照 → 冷启动 Cookie 未就绪就永久不恢复（PITFALLS §3） |
| `PushSession.kt` | 会话级纯逻辑（登录态、节流窗口判定） | 纯逻辑 + 单测 → 让 `PushSocket` 也能进本地编译链 |
| `SessionCookies.kt` | 取 Cookie（`CookieManager`） | 必须在主线程；要对"空 Cookie"做容错 |
| `PushSocket.kt` | WebSocket 连接与重连（`/ws/messaging/`） | 判重看 `isRunning`，不看 `socket != null` |
| `WsHandshake.kt` / `WsFrame.kt` | WS 握手与帧编解码（纯逻辑） | 有单测；帧/消息都有大小上限，超限即断连 |
| `PushProtocol.kt` | 事件类型与解析（纯逻辑） | 事件名与后端契约绑定，见 `docs/PUSH-CONTRACT.md` |
| `UnreadApi.kt` | 拉未读汇总（HTTP 兜底路径） | 走会话 Cookie；失败静默（下次兜底再来） |
| `UnreadBadge.kt` | 角标数字合并/递减（纯逻辑） | 有单测：多个来源（WS 事件 / 兜底拉取）合并时不能互相覆盖 |
| `PushNotifier.kt` | 通知构建与展示 | 用 `NotificationCompat`，渠道名/优先级集中定义 |
| `PushNotifications.kt` | 渠道与权限（Android 13+ `POST_NOTIFICATIONS`） | 权限未授予要能降级（不崩） |
| `PushDiagnostics.kt` | **仅 debug** 的自检：toast（最多 3 条）+ 通知栏常驻状态（独立渠道 `SELF_TEST_CHANNEL_ID`、`setShowBadge(false)`）+ 写 `DiagLog` | 预检规则强制；**debug 限定**，否则污染真实角标 |

---

## 6. 三级更新（`shell/update/`，区块 3）

| 文件 | 职责 | 关键约束 |
|---|---|---|
| `MiniJson.kt` | 零依赖 JSON 解析（128 KB / 深度 24 上限） | **纯逻辑 + 单测**；raw string 引号坑见 PITFALLS §6.1 |
| `UpdateManifest.kt` | 清单模型 + 字段级容错解码 | 契约见 `docs/APP-MANIFEST.md`（字段改名 = 破坏性变更） |
| `UpdatePolicy.kt` | 安全边界与决策：起始路由校验、URL 白名单、sha256 形状、体积区间、强制/提醒分支 | **纯逻辑 + 单测**；白名单主机来自 `AppConfig.UPDATE_ALLOWED_HOSTS` |
| `ManifestFetcher.kt` | 拉 `manifest.json`：`no-cache` + 主机白名单 + 256 KB 上限 + 失败静默 | 绝不能因为清单拉不到而影响 App 可用性 |
| `UpdateStore.kt` | 本地记住"已跳过的版本 / 上次检查时间" | 节流：冷启动 3 s、前台 30 min |
| `ApkDownloader.kt` | 下载 APK：`cacheDir/update/*.part` + Range 续传 + 边下边算 sha256 + 多源回退 | 自托管源在前、GitHub Release 备用 |
| `ApkDigest.kt` | 流式 sha256（纯逻辑） | 有单测；**必须以 `ApkDigest.matches(` 形式被调用**（预检会查） |
| `ApkSignatures.kt` | 读 APK 签名证书（API 28+ `GET_SIGNING_CERTIFICATES`；26–27 `GET_SIGNATURES`） | 兼容分支不能少 |
| `SignatureCheck.kt` | 签名一致性比对（防"换 key 的包"覆盖安装） | 跨版本必须同一把 release key |
| `UpdateInstaller.kt` | 交给系统安装器：`FileProvider` + `FLAG_GRANT_READ_URI_PERMISSION` + 未知来源引导 | 必须带 `FLAG_GRANT_READ_URI_PERMISSION`（预检会查） |
| `UpdateController.kt` | 编排：检查时机、对话框、公告横幅、强制/灰度 | **不要在后台线程碰 `webView.settings`**（闪退根因，PITFALLS §2.1） |

---

## 7. 资源清单（`app/src/main/res/`）

| 路径 | 说明 |
|---|---|
| `layout/activity_main.xml` | 唯一布局：WebView + 骨架 + FAB + 诊断浮层 |
| `drawable/bg_fab.xml`、`ic_arrow_up.xml` | 返回顶部 FAB（矢量，不引位图） |
| `drawable/bg_skeleton_block.xml` | 首屏骨架块 |
| `drawable/splash_background.xml` | 启动页背景（layer-list，API 26–30） |
| `drawable/ic_stat_club.xml` | 通知小图标（单色） |
| `drawable/ic_launcher_foreground.xml` | 自适应图标前景 |
| `mipmap-anydpi-v26/ic_launcher*.xml`、`mipmap-anydpi-v33/ic_launcher*.xml` | 自适应/主题化图标（v33 = 主题图标） |
| `values/colors.xml`、`values-night/colors.xml` | 品牌色与深色覆盖（品牌色取自站点 `cobalt.css`） |
| `values/bools.xml`、`values-night/bools.xml` | 深浅色差异用 **bool/color** 表达（不另建 `values-night/themes.xml`） |
| `values/themes.xml`、`values-v31/themes.xml` | `Theme_ClubApp` / `Theme_ClubApp_Splash`（**名字用下划线**） |
| `values/strings.xml` | 全部文案（含错误面板、诊断文案） |
| `values/ic_launcher_background.xml` | 图标底色 |
| `xml/network_security_config.xml` | 禁明文 + **额外信任锚 `sectigo_r46`**（`base-config` 与 `debug-overrides` **两处**） |
| `xml/file_paths.xml` | `FileProvider` 可共享路径：`cache/capture/`、`cache/share/`、`cache/update/` |
| `xml/data_extraction_rules.xml` | 备份/迁移规则（`allowBackup=false` 仍要声明现代规则） |
| `raw/sectigo_r46.pem` | Sectigo R46 根证书（老设备信任库缺失） |

---

## 8. 单测清单（`app/src/test/java/com/nhyzcms/clubapp/`，共 27 个文件）

> 全部是**纯逻辑**测试（JVM，不需要设备）。本地可用 `/workspace/klocaltest.sh` 直接跑（约 40 s，236 用例）。

`shell/`：`CrashTextTest`、`DownloadPolicyTest`、`LongPressPolicyTest`、`MediaSharePolicyTest`、
`NavPolicyTest`、`PermissionsPolicyTest`、`UploadLimitTest`
`shell/feel/`：`BackExitPolicyTest`、`ContentProbeTest`、`EdgeToEdgePolicyTest`、`FabPolicyTest`、
`PageThemeTest`、`ProbeCadenceTest`、`PullToRefreshPolicyTest`、`RenderWatchPolicyTest`
`shell/perf/`：`CacheTrimPolicyTest`、`DeviceClassifierTest`、`HeavyAssetPolicyTest`
`shell/push/`：`PushPolicyTest`、`PushProtocolTest`、`UnreadBadgeTest`、`WsFrameTest`、`WsHandshakeTest`
`shell/update/`：`ApkDigestTest`、`MiniJsonTest`、`UpdateManifestTest`、`UpdatePolicyTest`

**新增纯逻辑文件时**：
1. 写单测；
2. 加进 `/workspace/klocaltest.sh` 的白名单（否则"跑了但没覆盖"）；
3. 若被 Android 依赖层引用，再加进 `/workspace/kandroidcheck.sh` 的编译集。

---

## 9. 构建、脚本与 CI

| 路径 | 说明 |
|---|---|
| `build.gradle.kts` / `app/build.gradle.kts` / `settings.gradle.kts` | AGP 8.7.3 + Kotlin 2.0.21 + JDK 17；`minSdk 26` / `targetSdk 34` / `compileSdk 35` |
| `gradle.properties`、`version.properties` | `versionCode` / `versionName`（**tag 必须与 `versionName` 一致**） |
| `scripts/preflight.sh` | 静态预检主脚本（XML/资源/权限/安全红线/已知陷阱） |
| `scripts/preflight-feel.sh` | 手感层预检，**连带执行同目录 `preflight-*.sh`** |
| `scripts/preflight-push.sh` | 推送层规则（诊断必须走 `PushDiagnostics`、debug 限定、独立渠道…） |
| `scripts/preflight-perf.sh` | 性能层规则（拦截白名单、主文档不拦…） |
| `scripts/preflight-signing.sh` | 签名层规则（登记指纹格式、闸门未被删、Secret 名齐全、口令不得 echo/入库） |
| `scripts/ci-prepare-android-sdk.sh` | CI 侧准备 SDK（直接写 licenses，绕过 `sdkmanager --licenses`） |
| `scripts/ci-extract-errors.sh` | 从日志抽关键报错，贴成 PR 评论 |
| `scripts/verify-keystore.sh` | **发版闸门**：比对 keystore 证书指纹 ↔ `deploy/signing-fingerprint.txt`（不发版也能手动跑） |
| `scripts/publish-apk.sh` | 发布 APK 到服务器 `/app-update/`（自托管分发） |
| `scripts/nginx-app-update.conf` | 服务器 nginx 片段（`/app-update/` 静态端点） |
| `.github/workflows/android.yml` | 唯一工作流：`debug` / `release` / `verify-signing`（手动）三个作业（见 `docs/CI-WORKFLOW.md`） |
| `deploy/app-update/` | 分发目录模板与说明（`manifest.json`、`README.md`） |
| `deploy/signing-fingerprint.txt` | **发布签名证书指纹登记**（唯一真源；公开信息，刻意入库，供 CI 比对） |

---

## 10. "我要改 X" 索引表（最高频的 12 件事）

| 我想…… | 该动 |
|---|---|
| 改站点地址 | `shell/AppConfig.kt`（+ `README.md` §4.3） |
| 改起始路由 | **服务器** `app-update/manifest.json` 的 `startRoute`（L1，无需发版）；**不要**改壳里写死的默认值除非要改默认 |
| 改通知文案/渠道 | `values/strings.xml` + `shell/push/PushNotifications.kt` |
| 改上传体积上限提示 | `shell/UploadLimit.kt`（并核对站点 nginx `client_max_body_size`） |
| 改下载落盘位置规则 | `shell/DownloadPolicy.kt`（**唯一收口点**） |
| 改"哪些外部链接交浏览器" | `shell/NavPolicy.kt` + `AppConfig` 的开关键 |
| 改 Live2D 拦截范围 | `shell/AppConfig.kt::BLOCKED_ASSET_PREFIXES`（+ `HeavyAssetPolicy` 无需改） |
| 加一个远程开关 | `docs/APP-MANIFEST.md`（契约）+ `shell/update/UpdateManifest.kt` + `UpdatePolicy.mergeFlags`；**不改名，只新增** |
| 改首屏看护阈值 | `shell/feel/RenderWatchPolicy.kt` / `ProbeCadence.kt`（都带单测） |
| 改图标/品牌色 | `res/values/colors.xml` + `res/drawable/ic_launcher_foreground.xml` |
| 加一条预检规则 | 新建/追加 `scripts/preflight-<域>.sh`（会被 `preflight-feel.sh` 连带执行）+ 在 `docs/` 里记原因 |
| 改 CI 步骤 | `.github/workflows/android.yml`（改完更新 `docs/CI-WORKFLOW.md`） |
| 换发布签名密钥 / 加 Secrets | `deploy/signing-fingerprint.txt`（登记新指纹）+ 仓库 Secrets + `docs/BUILD.md` §3.4（**顺带必须发 Release 公告**，老用户要卸载重装） |

---

## 11. 文档地图（该写哪儿）

| 文档 | 定位 |
|---|---|
| `README.md` | 门面：是什么、为什么这么做、快速开始、状态、文档索引 |
| `docs/ARCHITECTURE.md` | 架构与取舍（为什么壳这么薄、为什么不做证书固定…） |
| `docs/ROADMAP.md` | 区块计划与进度 |
| `docs/CODE-MAP.md` | 本文：代码索引 |
| `docs/ENGINEERING-ENV.md` | 环境与工作流（含"大文件推送不再受限"的结论） |
| `docs/PITFALLS.md` | 坑位与排查手册 |
| `docs/CI-WORKFLOW.md` | CI 使用与排障 |
| `docs/BUILD.md` / `docs/SECURITY.md` / `docs/UPDATE-STRATEGY.md` | 构建、安全红线、三级更新策略 |
| `docs/APP-MANIFEST.md` | 远程清单字段契约 |
| `docs/PUSH-CONTRACT.md` | 与后端 WS 的事件契约 |
| `docs/FEATURE-MATRIX.md` | 功能 × 验证状态矩阵 |
| `docs/FIX-2026*.md` | 单次故障的深度复盘（按时间） |
| `docs/chunks/CHUNK-0N-*.md` | 每个区块的交付报告（历史记录；其中"推送上限/临时通道"的结论已过时，见 ENGINEERING-ENV §1.3） |

---

## 12. 变更记录

- **2026-10-14**：初版。按"文件 → 职责 → 约束"整理全仓 57 个 main 源文件、27 个测试文件、
  资源与脚本，并给出"改 X 动哪里"的索引表与文档地图。
- **2026-10-17（区块 7）**：新增 `deploy/signing-fingerprint.txt`（签名指纹登记，CI 的唯一真源）、
  `scripts/verify-keystore.sh`（发版闸门）、`scripts/preflight-signing.sh`（签名层静态规则）；
  工作流新增 `verify-signing` 作业（手动触发，不发包）。

<!-- EOF-SENTINEL-CODE-MAP-20261017 -->
