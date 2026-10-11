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
首屏资源必须靠 HTTP 缓存与压缩（网站侧已完成 4.2× gzip），App 侧只做缓存与预加载，
**不做任何服务端代理**。

## 3. 目录结构

```
app/                                ← 本仓库根（仓库名 app，Gradle 模块也叫 app/）
├── README.md                       ← 你正在读
├── docs/                           ← 设计文档（先看 ARCHITECTURE，再看 ROADMAP）
│   ├── ARCHITECTURE.md             ← 分层、组件职责、与网站的契约
│   ├── UPDATE-STRATEGY.md          ← 三级更新模型（热/温/冷）
│   ├── SECURITY.md                 ← 威胁模型与已实施的安全控制
│   ├── ROADMAP.md                  ← 任务分块（本项目的推进方式）
│   ├── BUILD.md                    ← 本地与 CI 构建、签名、发版 SOP
│   ├── CI-WORKFLOW.md              ← CI 工作流说明与发版前置条件
│   ├── APP-MANIFEST.md             ← 远程配置清单（app-manifest.json）契约
│   ├── FEATURE-MATRIX.md           ← 网站功能 ↔ 壳支持情况 台账
│   └── chunks/CHUNK-0N-REPORT.md   ← 每一块的开发说明
├── .github/workflows/android.yml   ← CI：debug 构建 / tag 自动发 Release
├── settings.gradle.kts / build.gradle.kts / gradle.properties / version.properties
└── app/                            ← Android 模块
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/nhyzcms/clubapp/
        │   ├── MainActivity.kt          入口：装载 WebView 与导航策略
        │   └── shell/
        │       ├── AppConfig.kt         站点地址、UA 标记、Host 白名单
        │       ├── NavPolicy.kt         导航裁决（纯函数，可单测）
        │       ├── ShellErrorKind.kt    错误分类
        │       ├── WebShellClient.kt    导航拦截 / 外链 / TLS 失败处理
        │       └── WebShellChromeClient.kt  进度回传 / 控制台日志
        └── res/                         字符串、主题（跟随系统深色）、
                                         网络安全配置、纯矢量图标、主界面布局
```

## 4. 快速开始

### 4.1 本地构建

```bash
# 需要 JDK 17 + Android SDK（compileSdk 35）
gradle :app:assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 本仓库**不提交 `gradle-wrapper.jar`**（CI 通过 `gradle/actions/setup-gradle` 指定
> Gradle 版本）。若要本地用 `./gradlew`，在 Android Studio 里执行一次
> `gradle wrapper --gradle-version 8.13` 生成即可。详见 [`docs/BUILD.md`](docs/BUILD.md)。

### 4.2 CI（已启用）

| 触发 | 行为 |
|---|---|
| 分支 push / PR | `assembleDebug`，失败即红；产物为 artifact `app-debug-apk`（14 天） |
| 推送 tag `vX.Y.Z` | `assembleRelease`（读取 Secrets 签名）→ 自动创建 GitHub Release 并附 APK + SHA-256 |

> 发 Release 必须先配好 4 个 Secrets，否则 `release` 作业会**主动失败**
> （宁可失败，也不发出装不上的未签名包）。步骤见 [`docs/BUILD.md`](docs/BUILD.md) §3、
> 说明见 [`docs/CI-WORKFLOW.md`](docs/CI-WORKFLOW.md)。

### 4.3 改站点地址

只改一处：`app/src/main/java/com/nhyzcms/clubapp/shell/AppConfig.kt` 的 `SITE_ORIGIN`
与 `ALLOWED_HOSTS`。**换域名时务必同步白名单**，否则站内跳转会被判为外链。

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
- 不信任用户安装的 CA（关闭 user trust anchors），防中间人；
- 站内导航白名单 = 本站 host；其它域名一律交给系统浏览器，未知 scheme 一律拦截；
- 更新包必须**校验 sha256 + 签名一致**才允许安装；安装来源链接只允许本站 / GitHub Releases；
- 密钥永不入库：keystore 只存在于 GitHub Secrets（base64）；
- Release 构建关闭 WebView 远程调试（`BuildConfig.DEBUG` 才开）。

## 7. 项目状态

| 区块 | 内容 | 状态 |
|---|---|---|
| 1 | 整体骨架：可编译的最小壳 + 文档 + CI | ✅ 已完成（[报告](docs/chunks/CHUNK-01-REPORT.md)） |
| 2 | 全部功能能力：文件上传/下载、相机、权限、长按保存、分享 | ⏳ 下一块 |
| 3 | 三级更新机制（manifest + APK 自升级） | ⏳ |
| 4 | 原生手感：沉浸式、下拉刷新、手势返回、启动页、主题跟随 | ⏳ |
| 5 | 站内消息与角标（实时/通知） | ⏳ |
| 6 | 弱网与性能（3M 带宽适配、缓存、预加载） | ⏳ |
| 7 | 签名与首个 Release 发布 | ⏳ |
| 8 | 网站侧配套（App 模式 UI 覆盖全部功能 + manifest 端点） | ⏳ |
| 9 | 测试与验收（手测清单 / 回归流程 / E2E） | ⏳ |
| 10 | 长期维护手册与交接 | ⏳ |

分块依据与验收标准见 [`docs/ROADMAP.md`](docs/ROADMAP.md)。

## 8. 维护者 30 分钟上手

1. 读 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) 第 1~3 节（分层 + 契约）；
2. 读 [`docs/UPDATE-STRATEGY.md`](docs/UPDATE-STRATEGY.md) 的决策表，理解「改什么要发 APK」；
3. 跑一次 `gradle :app:assembleDebug`，装到真机，确认能登录、能滚动、能返回；
4. 读 [`docs/SECURITY.md`](docs/SECURITY.md) 末尾的**改代码自查清单**——每次提交前过一遍；
5. 想动手就挑 [`docs/ROADMAP.md`](docs/ROADMAP.md) 里状态为 ⏳ 的区块，一区块一 PR。

## 9. 文档索引

| 文档 | 用途 |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层架构、组件职责、与网站仓库的契约 |
| [UPDATE-STRATEGY.md](docs/UPDATE-STRATEGY.md) | 三级更新模型、发布/回滚 SOP |
| [SECURITY.md](docs/SECURITY.md) | 威胁模型、安全控制、自查清单 |
| [ROADMAP.md](docs/ROADMAP.md) | 分块计划与验收标准 |
| [BUILD.md](docs/BUILD.md) | 构建、签名、版本号规则、发布步骤 |
| [CI-WORKFLOW.md](docs/CI-WORKFLOW.md) | CI 行为与发版前置条件 |
| [APP-MANIFEST.md](docs/APP-MANIFEST.md) | 远程配置清单字段契约 |
| [FEATURE-MATRIX.md](docs/FEATURE-MATRIX.md) | 网站功能覆盖台账 |

---

© 南汇一中传媒社 · 内部项目
