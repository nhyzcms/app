# 南汇一中传媒社 · Android 客户端（`nhyzcms/app`）

> 面向 [南汇一中传媒社社团管理系统](https://8.153.145.175/) 的 Android 应用。
> **原生外壳 + 网页内核（Kotlin + 系统 WebView）**，不是 Electron 套壳。

---

## 1. 这是什么

一个 **3~5 MB 的 Android 外壳**：原生部分只负责「浏览器能力 + Android 系统能力」，
界面与业务逻辑 100% 复用网站前端。这样做的直接收益是——

- 网站发版（Django 后端 / React 前端）→ **App 重新加载即生效，无需重新下载安装包**（热更新）；
- 只有「原生能力变化」（新权限、新桥接接口、安全修复）才需要发新 APK（冷更新）。

详细的更新模型见 [`docs/UPDATE-STRATEGY.md`](docs/UPDATE-STRATEGY.md)。

## 2. 为什么不用 Electron / Capacitor

| 方案 | 能否产出 APK | 体积 | 结论 |
|---|---|---|---|
| Electron | ❌ 只能打包 Win/macOS/Linux 桌面端 | 80 MB+ | **不可用于 Android** |
| Capacitor / Cordova | ✅ | 5~10 MB，额外引入 Node 工具链与插件层 | 本项目不需要：没有跨平台原生需求，且壳要极简 |
| **Kotlin + 系统 WebView（本项目）** | ✅ | **3~5 MB**，零第三方运行时依赖 | ✅ 采用 |

适配约束：服务器 2 vCPU / 2 GB 内存 / **3 M 带宽**、不能使用 CDN —— 因此 APK 必须小、
首屏资源必须靠 HTTP 缓存与压缩（网站侧已完成 4.2× gzip），App 侧只做**缓存复用与按需降级**
（区块 6：探测降频、大资源兜底拦截、低内存降级；实测瓶颈是图片而非 JS，故**不做预取**），
**不做任何服务端代理**。

## 3. 目录结构

```
app/                                ← 本仓库根（仓库名 app，Gradle 模块也叫 app/）
├── README.md                       ← 你正在读
├── docs/                           ← 设计文档（先看 ARCHITECTURE，再看 ROADMAP）
│   ├── ARCHITECTURE.md             ← 分层、组件职责、与网站的契约
│   ├── UPDATE-STRATEGY.md          ← 三级更新模型（热/温/冷）
│   ├── SECURITY.md                 ← 威胁模型、权限清单、安全控制
│   ├── ROADMAP.md                  ← 任务分块（本项目的推进方式）
│   ├── BUILD.md                    ← 本地与 CI 构建、签名、发版 SOP
│   ├── CI-WORKFLOW.md              ← CI 工作流说明与发版前置条件
│   ├── APP-MANIFEST.md             ← 远程配置清单（app-manifest.json）契约
│   ├── FEATURE-MATRIX.md           ← 网站功能 ↔ 壳支持情况 台账
│   └── chunks/CHUNK-0N-REPORT.md   ← 每一块的开发说明
├── .github/workflows/android.yml   ← CI：预检 + 单测 + debug 构建 / tag 自动发 Release
├── settings.gradle.kts / build.gradle.kts / gradle.properties / version.properties
├── scripts/                        ← preflight*.sh（分区块静态预检；preflight-feel.sh 连带跑全部专项）、CI 辅助脚本、
│                                     nginx-app-update.conf（/app-update/ 片段）、
│                                     publish-apk.sh（把 APK 发到自托管目录并更新清单）
├── deploy/app-update/              ← 要拷到服务器 /opt/club/app-update/ 的清单模板与部署说明
└── app/                            ← Android 模块
    ├── build.gradle.kts
    ├── proguard-rules.pro
    ├── src/main/
    │   ├── AndroidManifest.xml
    │   ├── java/com/nhyzcms/clubapp/
    │   │   ├── MainActivity.kt          入口：装载 WebView、错误面板、能力接线
    │   │   ├── ClubApp.kt               Application：只装「崩溃自记录」（见 shell/CrashReporter.kt）
    │   │   └── shell/
    │   │       ├── AppConfig.kt             站点地址、UA 标记、Host 白名单、FileProvider authority
    │   │       ├── NavPolicy.kt             导航裁决（纯函数，可单测）
    │   │       ├── ShellErrorKind.kt        错误分类
    │   │       ├── WebShellClient.kt        导航拦截 / 外链 / TLS 失败处理
    │   │       ├── WebShellChromeClient.kt  进度 / 控制台日志 / 文件选择钩子
    │   │       ├── FileChooserHost.kt       input[type=file] 入口（回调恰好一次）
    │   │       ├── CameraCapture.kt         拍照：FileProvider 临时文件 + 相机 Intent
    │   │       ├── PermissionsPolicy.kt     运行时权限策略（相机 + 仅 8/9 的写存储）
    │   │       ├── UploadLimit.kt           20 MB 上传上限的本地前置判定
    │   │       ├── DownloadPolicy.kt        落盘位置 / 文件名 / 可下载性（纯函数）
    │   │       ├── DownloadStarter.kt       交给系统 DownloadManager（带会话 Cookie）
    │   │       ├── LongPressPolicy.kt       长按命中判定（图片 / 链接）
    │   │       ├── MediaSharePolicy.kt      分享图片的前置判定（纯函数）
    │   │       ├── MediaSharer.kt           分享图片文件本体（缓存 → FileProvider → ACTION_SEND）
    │   │       ├── CrashText.kt             崩溃报告文本排版（纯函数，可单测）
    │   │       ├── CrashReporter.kt         崩溃自记录：未捕获异常落盘 + 下次启动弹窗（可复制/分享）
    │   │       ├── DiagLog.kt               运行轨迹落盘（filesDir/diag/last-run.txt，FeelHost/PerfHost 共用）
    │   │       ├── feel/                    区块 4：原生手感（启动页/配色/骨架/下拉刷新/返回顶部）
    │   │       │   ├── PageTheme.kt             网页主题色 → 壳配色（纯函数）
    │   │       │   ├── BackExitPolicy.kt        返回键决策：双击才退出（纯函数）
    │   │       │   ├── FabPolicy.kt             返回顶部按钮的显示阈值（纯函数）
    │   │       │   ├── PullToRefreshPolicy.kt   下拉刷新的门控与超时（纯函数）
    │   │       │   ├── EdgeToEdgePolicy.kt      铺满开关与浮层让位（纯函数）
    │   │       │   ├── ScrollAwareWebView.kt    同步上报滚动位置的 WebView
    │   │       │   ├── PageStateProbe.kt        读网页主题色/滚动位置（固定脚本；间隔由 ProbeCadence 动态给）
    │   │       │   ├── ContentProbe.kt          网页「画出内容了吗」探测（纯函数；决定骨架揭开与空白诊断）
    │   │       │   ├── RenderWatchPolicy.kt     区块 6：首屏看护复核定策（纯函数；多次重试 + 诊断自动撤销）
    │   │       │   ├── ProbeCadence.kt          区块 6：状态探测退避曲线（纯函数；稳定 → 15 s，有变化回 2 s）
    │   │       │   └── FeelHost.kt              把上述能力接到窗口与视图的宿主
    │   │       ├── perf/                    区块 6：弱网与性能（省流量 / 省内存）
    │   │       │   ├── DeviceClass.kt            设备分级与派生参数（纯函数）
    │   │       │   ├── HeavyAssetPolicy.kt       「大而不必要」资源判定（纯函数；Live2D 素材拦截）
    │   │       │   ├── CacheTrimPolicy.kt        WebView 缓存清理时机（纯函数；默认什么都不做）
    │   │       │   └── PerfHost.kt               设备分级 / 大资源拦截 / 缓存维护的宿主
    │   │       ├── push/                    区块 5：消息与角标（前台 WebSocket + 系统通知角标）
    │   │       │   ├── WsFrame.kt / WsHandshake.kt / PushProtocol.kt  帧、握手、事件解析（纯函数）
    │   │       │   ├── PushSocket.kt            前台 WSS 长连接（主机名校验 + 心跳 + 退避重连）
    │   │       │   ├── UnreadBadge.kt           未读角标状态机（纯函数）
    │   │       │   ├── UnreadApi.kt             未读计数校准（地址收口）
    │   │       │   ├── SessionCookies.kt        会话 Cookie 快照（只在主线程取）
    │   │       │   ├── PushNotifications.kt     静音通知 + launcher 角标
    │   │       │   └── PushHost.kt              前后台生命周期接线（MainActivity 零改动）
    │   │       └── update/                  区块 3：三级更新（L0 热 / L1 清单 / L2 自升级）
    │   │           ├── MiniJson.kt              零依赖 JSON 解析（可单测）
    │   │           ├── UpdateManifest.kt        清单数据模型 + 解析（字段级容错）
    │   │           ├── UpdatePolicy.kt          安全判定与版本决策（纯函数，更新流程的安全边界）
    │   │           ├── UpdateStore.kt           清单缓存 / 开关 / 续传元数据（SharedPreferences）
    │   │           ├── ManifestFetcher.kt       拉清单（no-cache、主机白名单、体积上限）
    │   │           ├── ApkDownloader.kt         下更新包（Range 续传 + 边下边算 sha256）
    │   │           ├── ApkDigest.kt             SHA-256 计算与比对（纯函数）
    │   │           ├── ApkSignatures.kt         取本机 / APK 文件的签名证书
    │   │           ├── SignatureCheck.kt        签名一致性判定（纯函数）
    │   │           ├── UpdateInstaller.kt       FileProvider + 系统安装器（含“未知来源”引导）
    │   │           └── UpdateController.kt      串联检查 → 提示 → 下载 → 校验 → 安装
    │   └── res/                         字符串、主题（跟随系统深色 + 启动页）、网络安全配置、
    │                                    FileProvider 路径、纯矢量图标、主界面布局
    └── src/test/java/…                  纯逻辑单测（不需要设备；`gradle :app:testDebugUnitTest`）
```

## 4. 快速开始

### 4.1 本地构建

```bash
# 需要 JDK 17 + Android SDK（compileSdk 35）
gradle :app:assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 提交前必跑：静态预检（几秒，不需要 JDK/SDK）
bash scripts/preflight.sh          # 基础规则（导航 / 权限 / 资源 / 密钥 …）
bash scripts/preflight-feel.sh     # 手感规则，并连带执行同目录其它专项脚本 preflight-*.sh

# 纯逻辑单测（纯函数，不需要设备）
gradle :app:testDebugUnitTest
```

> 本仓库**不提交 `gradle-wrapper.jar`**（CI 通过 `gradle/actions/setup-gradle` 指定
> Gradle 版本）。若要本地用 `./gradlew`，在 Android Studio 里执行一次
> `gradle wrapper --gradle-version 8.13` 生成即可。详见 [`docs/BUILD.md`](docs/BUILD.md)。

> **提交与推送纪律**见 [`docs/ENGINEERING-ENV.md`](docs/ENGINEERING-ENV.md)：本仓库
> **不再需要把大文件拆小**（早期"单次写入/推送约 1 万字符上限"的限制已解除，
> 2026-10-14 实测 2 MiB 二进制一次推送、1.1 万+ 字符文档一次写入均逐字节无损），
> 但"推送前跑完预检 + 推送后逐字复核"一条都不能省。

### 4.2 CI（已启用）

| 触发 | 行为 |
|---|---|
| 分支 push / PR | 预检 → `testDebugUnitTest` → `assembleDebug`；失败即红，产物为 artifact `app-debug-apk`（14 天） |
| 推送 tag `vX.Y.Z` | 预检 → 单测 → `assembleRelease`（读取 Secrets 签名）→ 自动创建 GitHub Release 并附 APK + SHA-256 |

> 构建失败时关键报错会自动贴成 **PR 评论**（Actions 原始日志需仓库管理员权限，PR 评论才读得到）。

### 4.3 改站点地址

只改一处：`app/src/main/java/com/nhyzcms/clubapp/shell/AppConfig.kt` 的 `SITE_ORIGIN`
与 `ALLOWED_HOSTS`。**换域名时务必同步白名单**，否则站内跳转会被判为外链。

同一文件还有更新相关的两处（区块 3）：`UPDATE_MANIFEST_URL`（清单地址）与 `UPDATE_ALLOWED_HOSTS`
（允许的清单/APK 主机）。**换域名时它们必须一起改**，否则壳会把新地址判为非法并忽略（这是有意的：
更新流程会下载并安装，地址白名单是它的安全边界）。

## 5. 三级更新模型（TL;DR）

| 级别 | 场景 | 用户动作 | 归属 |
|---|---|---|---|
| **L0 内容/业务更新** | 网站改了页面、接口、样式；新增站内功能 | 打开 App 即生效 | 网站仓库发版（现有流程不变） |
| **L1 配置/开关** | 起始页、功能开关、最低壳版本、强制更新、紧急停用 | 打开 App 自动拉取 | 服务器托管 `app-manifest.json` |
| **L2 壳能力更新** | 新权限、新原生接口、安全修复 | App 内提示 → 下载 → 覆盖安装 | 本仓库发 Release（自托管镜像优先） |

**原则：UI 与业务尽量放在 Web 层（可热更新），原生壳尽可能薄。**

## 6. 安全红线（详见 docs/SECURITY.md）

- 仅 HTTPS，明文流量全站禁止（`cleartextTrafficPermitted=false`）；
- **不做证书固定**：站点证书是 90 天 ZeroSSL DV，固定必然在换证日砖掉；改为「HTTPS 强制 + 系统信任链 + TLS 失败明确报错」；
- **唯一的信任链例外**：站点证书链的根（`Sectigo Public Server Authentication Root R46`，2021 年新建）
  不在老设备的系统信任库里（实测 HUAWEI P40 Pro+ / HarmonyOS 4.2 = Android 12 内核会直接握手失败），
  因此把它作为**额外信任锚**打进 App（`res/raw/sectigo_r46.pem`，同时写进 `base-config` 与 `debug-overrides`）；
  **站点换 CA 时必须同步维护**，见 [docs/FIX-20261012-CERT-R46.md](docs/FIX-20261012-CERT-R46.md)；
- 不信任用户安装的 CA（关闭 user trust anchors），防中间人；
- 站内导航白名单 = 本站 host；其它域名一律交给系统浏览器，未知 scheme 一律拦截；
- **权限只有 6 个**（完整清单见 SECURITY.md §2.1）：
  - `INTERNET` / `ACCESS_NETWORK_STATE`：加载站点、判断网络（后者区块 1 起就在）；
  - 文件选择走系统 SAF、分享图片走 App 缓存，都不需要读取类权限；
  - `WRITE_EXTERNAL_STORAGE` **只在 Android 8/9 上**用来把下载写进公共「下载」目录
    （`maxSdkVersion="28"`，且仅在下载时申请；拒了退 App 目录），preflight 会强制校验这个限定；
  - `CAMERA` 只在“将要提供拍照入口”时申请；
  - `POST_NOTIFICATIONS`（区块 5）：Android 13+ 进 App 约 4 秒后弹一次，用于未读通知与 launcher 角标；
    拒绝则不发通知、无角标，网页内徽标照常（绝不反复弹窗）；
  - `REQUEST_INSTALL_PACKAGES` 只用于安装自己下载的更新包（系统仍会要求用户逐个允许“安装未知应用”，
    壳不绕过也不静默安装）；
- 所有带会话的请求（`DownloadManager`、分享图片）**必须手动带 Cookie**；
- 更新包必须**校验 sha256 + 签名一致**才允许安装；安装来源链接只允许本站 / GitHub Releases；
- 壳内执行 JS 只允许**固定常量脚本**（区块 4：读主题色、滚回顶部、探测网页内容），
  禁止拼接变量，也不用 JS 桥接（见 SECURITY.md §4.1）；
- 壳侧**只拦白名单里的"不必要"资源**（区块 6：`/static/live2d/` 的看板娘素材约 60 MB，
  拦截后返回空体 200）；**主文档永不拦截**，白名单唯一真源在 `AppConfig.BLOCKED_ASSET_PREFIXES`，
  清单开关 `blockLive2dAssets` 可远程关闭；
- 密钥永不入库：keystore 只存在于 GitHub Secrets（base64）与管理员离线备份；
  **公开发布证书指纹**登记在 `deploy/signing-fingerprint.txt`，CI 发版前会比对
  （防"用错密钥发版"这种构建成功但用户装不上的事故，见 [BUILD.md](docs/BUILD.md) §3）；
- Release 构建关闭 WebView 远程调试（`BuildConfig.DEBUG` 才开）。

## 7. 项目状态

| 区块 | 内容 | 状态 |
|---|---|---|
| 1 | 整体骨架：可编译的最小壳 + 文档 + CI | ✅ 已完成，真机验收通过 2026-10-12（[报告](docs/chunks/CHUNK-01-REPORT.md)） |
| 2 | 全部功能能力：文件上传/下载、相机、权限、长按保存、分享 | ✅ 已完成，真机验收通过 2026-10-12（[报告](docs/chunks/CHUNK-02-REPORT.md)） |
| 3 | 三级更新机制（manifest + APK 自升级） | ✅ 已完成（代码 + 清单端点已部署 2026-10-11 + 真机验收通过 2026-10-12）（[报告](docs/chunks/CHUNK-03-REPORT.md)） |
| 4 | 原生手感：沉浸式配色、启动页、骨架屏、下拉刷新、手势返回、返回顶部 | ✅ 已完成，真机验收通过 2026-10-12（[报告](docs/chunks/CHUNK-04-REPORT.md)） |
| 5 | 站内消息与角标（前台 WSS / 静音通知驱动角标 / 点通知直达会话） | 🟡 代码就绪（PR #16 已合并 2026-10-13），待真机验收（[报告](docs/chunks/CHUNK-05-REPORT.md)｜[跨仓库契约](docs/PUSH-CONTRACT.md)） |
| 6 | 弱网与性能（首屏看护去误报 / 探测降频 / Live2D 兜底拦截 / 低内存降级） | 🟡 代码就绪（2026-10-13，CI 绿），待真机验收（[报告](docs/chunks/CHUNK-06-REPORT.md)） |
| 7 | 签名与首个 Release 发布 | 🟡 密钥 + Secrets + 指纹闸门已就绪（2026-10-17），待打 tag 发首个 Release（[BUILD.md §3](docs/BUILD.md)｜[报告](docs/chunks/CHUNK-07-REPORT.md)） |
| 8 | 网站侧配套（App 模式 UI 覆盖全部功能 + manifest 端点） | ⏳（已挂入网站侧不注入 live2d + 大图懒加载，见 ROADMAP） |
| 9 | 测试与验收（手测清单 / 回归流程 / E2E） | ⏳ |
| 10 | 长期维护手册与交接 | ⏳ |

分块依据与验收标准见 [`docs/ROADMAP.md`](docs/ROADMAP.md)，功能台账见 [`docs/FEATURE-MATRIX.md`](docs/FEATURE-MATRIX.md)。

> **2026-10-12 真机联调**：首次真机安装暴露了三起底层故障（启动闪退 / 黑白屏 / 证书链的根不在老设备信任库），
> 已由 PR #10~#13 修复；区块 1~4 随后由管理员在真机上**全部验收通过**（M1「可用」达成）。
> 故障报告见 [FIX-20261012-STARTUP-CRASH.md](docs/FIX-20261012-STARTUP-CRASH.md)、
> [FIX-20261012-BLANK-SCREEN.md](docs/FIX-20261012-BLANK-SCREEN.md)、
> [FIX-20261012-BLANK-SCREEN-2.md](docs/FIX-20261012-BLANK-SCREEN-2.md)、
> [FIX-20261012-CERT-R46.md](docs/FIX-20261012-CERT-R46.md)；
> 遗留项（卡顿 / 诊断浮层去误报 / App 不加载 live2d）已并入区块 6 / 8 的开发计划。

## 8. 维护者 30 分钟上手

1. 读 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) 第 1~3 节（分层 + 契约）；
2. 读 [`docs/UPDATE-STRATEGY.md`](docs/UPDATE-STRATEGY.md) 的决策表，理解「改什么要发 APK」；
3. 跑一次 `gradle :app:assembleDebug`，装到真机，确认能登录、能滚动、能返回；
4. 读 [`docs/SECURITY.md`](docs/SECURITY.md) 末尾的**改代码自查清单**——每次提交前过一遍；
5. 想动手就挑 [`docs/ROADMAP.md`](docs/ROADMAP.md) 里状态为 ⏳ 的区块，一区块一 PR。
6. 踩到坑先查 [`docs/PITFALLS.md`](docs/PITFALLS.md)（构建 / WebView / 推送 / 上传下载 / 协作流程的已知坑与修法）。

## 9. 文档索引

| 文档 | 用途 |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层架构、组件职责、与网站仓库的契约 |
| [UPDATE-STRATEGY.md](docs/UPDATE-STRATEGY.md) | 三级更新模型、发布/回滚 SOP |
| [SECURITY.md](docs/SECURITY.md) | 威胁模型、权限清单、安全控制 |
| [ROADMAP.md](docs/ROADMAP.md) | 分块计划与验收标准 |
| [BUILD.md](docs/BUILD.md) | 构建、签名、版本号规则、发布步骤 |
| [CI-WORKFLOW.md](docs/CI-WORKFLOW.md) | CI 行为、失败排障与发版前置条件 |
| [ENGINEERING-ENV.md](docs/ENGINEERING-ENV.md) | 工程环境与工具链：工作区能力、预检链、复核纪律、**大文件推送已不再受限** |
| [CODE-MAP.md](docs/CODE-MAP.md) | 代码地图：文件 → 职责 → 约束，含"我要改 X 动哪里"索引 |
| [PITFALLS.md](docs/PITFALLS.md) | 坑位与排查手册（构建 / WebView / 会话推送 / 上传下载 / 协作流程） |
| [APP-MANIFEST.md](docs/APP-MANIFEST.md) | 远程配置清单字段契约 |
| [FEATURE-MATRIX.md](docs/FEATURE-MATRIX.md) | 网站功能覆盖台账 |
| [PUSH-CONTRACT.md](docs/PUSH-CONTRACT.md) | 跨仓库实时消息契约（连接 / 事件 / 未读接口 / 深链） |
| [chunks/CHUNK-02-REPORT.md](docs/chunks/CHUNK-02-REPORT.md) | 区块 2（文件/拍照/下载/长按/分享）交付说明 |
| [chunks/CHUNK-03-REPORT.md](docs/chunks/CHUNK-03-REPORT.md) | 区块 3（三级更新）交付说明与服务器配置步骤 |
| [chunks/CHUNK-04-REPORT.md](docs/chunks/CHUNK-04-REPORT.md) | 区块 4（原生手感）交付说明与真机验收清单 |
| [chunks/CHUNK-05-REPORT.md](docs/chunks/CHUNK-05-REPORT.md) | 区块 5（消息与角标）交付说明与真机验收清单 |
| [chunks/CHUNK-06-REPORT.md](docs/chunks/CHUNK-06-REPORT.md) | 区块 6（弱网与性能）交付说明与真机验收清单 |
| [FIX-20261012-STARTUP-CRASH.md](docs/FIX-20261012-STARTUP-CRASH.md) | 故障报告：启动闪退（后台线程访问 WebView） |
| [FIX-20261012-BLANK-SCREEN.md](docs/FIX-20261012-BLANK-SCREEN.md) | 故障报告：黑/白屏（骨架浮层永不揭开） |
| [FIX-20261012-BLANK-SCREEN-2.md](docs/FIX-20261012-BLANK-SCREEN-2.md) | 故障报告：硬看门狗 + 壳侧网络探测 + 诊断浮层 |
| [FIX-20261012-CERT-R46.md](docs/FIX-20261012-CERT-R46.md) | 故障报告：证书链的根不在老设备信任库 |
| [deploy/app-update/README.md](deploy/app-update/README.md) | 更新分发目录的部署与发版操作 |

---

© 南汇一中传媒社 · 内部项目
