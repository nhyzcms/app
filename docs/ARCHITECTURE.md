# 架构设计（ARCHITECTURE）

> 本文回答三个问题：**壳里放什么、网站里放什么、两边怎么约定**。

---

## 1. 目标与非目标

### 目标
1. 在 Android 上以接近原生 App 的体验使用站点**全部功能**（不是只有手机版那 4 个页面）；
2. 网站日常更新 **不需要重装 APK**（热更新）；
3. 应用体积小、弱网可用（适配 3 M 带宽服务器）；
4. 敏感信息（会话 Cookie、密码、上传文件、更新包）不被窃取或伪造；
5. 后来者能看懂、能改、能发版。

### 非目标
- ❌ 不追求 Play 商店上架（国内校园场景，用 GitHub Release + 自托管分发）；
- ❌ 不重写业务 UI（不在 Kotlin 里重做列表/表单——那是无法热更新的死路）；
- ❌ 不做多平台（iOS 不在本期范围，但架构保留可能）。

---

## 2. 分层

```
┌─────────────────────────────────────────────────────────────┐
│  Web 层（网站仓库 frontend/ + Django）  ← 业务与 UI 的家     │
│  React SPA · HashRouter · 手机版 UI(/#/m) · App 模式 UI      │
│  可热更新：发版即生效，无需动 APK                              │
└───────▲──────────────────────────────────▲──────────────────┘
        │ HTTPS（gzip / HTTP2，已有 nginx 配置）│ app-manifest.json
        │                                    │
┌───────┴────────────────────────────────────┴──────────────────┐
│  Shell 层（本仓库，Kotlin）  ← 只做 Web 做不到的事            │
│  WebView 策略 · 导航白名单 · 文件选择/相机 · 下载 · 分享       │
│  更新检查与安装 · 权限申请 · 深链 · 返回手势 · 沉浸式状态栏     │
└───────▲──────────────────────────────────────────────────────┘
        │ Android 框架 API（AppCompat / WebKit / DownloadManager）
┌───────┴──────────────────────────────────────────────────────┐
│  Android 系统（WebView 内核、安装器、通知、存储）             │
└─────────────────────────────────────────────────────────────┘
```

**一句话概括：能用 Web 做的就用 Web 做（因为可热更新）；只有 Web 做不了的（系统能力）才写进 Shell。**

---

## 3. 关键决策（ADR 摘要）

| 编号 | 决策 | 选项与理由 |
|---|---|---|
| **A01** | Shell = Kotlin + 系统 WebView | Electron 无 Android 产物；Capacitor 引入 Node/插件层且体积大一倍，收益不是本项目所需。原生壳 3~5 MB，适合 3 M 带宽分发。 |
| **A02** | 所有 UI/业务流程位于 Web 层 | 若在 Kotlin 里实现 UI，每次改样式都要发 APK，与"热更新优先"目标冲突。 |
| **A03** | **不做证书固定（no pinning）** | 站点证书为 ZeroSSL DV，`SAN=8.153.145.175`，**90 天有效期**（当前实例 2026-11-26 到期）。固定指纹会在换证日整站失联；固定 CA 也随 ZeroSSL 中间证书轮换而失效。改为：HTTPS 强制 + 系统信任链 + `onReceivedSslError` 明确失败（绝不 `proceed()`）。 |
| **A04** | `minSdk 26`（Android 8.0） | ① 支持 XML 自适应图标（仓库内不提交二进制 PNG，纯文本可审查）；② 2017 年后的机型覆盖率足够；③ 规避 API 24/25 的 WebView 兼容杂音。 |
| **A05** | 依赖最小化 | 不引入 OkHttp/Retrofit/协程运行时以外的大件：网络请求用 `HttpURLConnection`，图片/图标用 VectorDrawable。依赖越多，APK 越大、供应链风险越高。区块 4 的例外：`androidx.swiperefreshlayout`（约 +40 KB，见 CHUNK-04 报告 §3.6）。 |
| **A06** | 桥接（JS ⇄ 原生）只在必要时做，且**按 origin 限定** | 优先用 `WebViewCompat.addWebMessageListener`（androidx.webkit，可按 origin 白名单收消息）；绝不使用全局可注入的 `addJavascriptInterface`（旧 API 在 API<17 有漏洞，且对任意页面可见）。 |
| **A07** | 更新包分发：自托管优先 + GitHub Release 兔底 | 服务器在国内（3 M 带宽、无 CDN），从本站下载 APK 才快；GitHub 只作归档与公开下载页。manifest 与 APK 均为公开只读资源，不含任何密钥。 |
| **A08** | 单 Activity + 单 WebView，不引入导航框架 | 站点是 HashRouter SPA，路由全在网页内；原生只需一个宿主 Activity。不引入 Compose Navigation / Fragment 堆栈。 |
| **A09** | 沉浸式：**默认不铺满状态栏**，只做“配色沉浸” | Android WebView 的 `env(safe-area-inset-*)` 只反映屏幕缺口（display cutout）、通常为 0，铺满会让网页 sticky 顶栏被状态栏压住。因此铺满做成清单开关 `edgeToEdge`（默认 false）；默认改为让状态栏 / 导航栏 / 窗口底色跟随网页 `meta theme-color`（`shell/feel/PageTheme`）。见 [chunks/CHUNK-04-REPORT.md](chunks/CHUNK-04-REPORT.md) §3.1。 |
| **A10** | 壳内执行 JS：只允许**固定常量脚本** | 探测网页主题色 / 滚动位置与“返回顶部”需要执行 JS；一旦拼接变量就成了注入口子。禁止 `addJavascriptInterface`、禁止 `evaluateJavascript` 字符串拼接、禁止 `loadUrl("javascript:…")`（`scripts/preflight.sh` 已固化）。 |
| **A11** | 推送（区块 5）只在前台保活，角标由「固定 id 的静音通知」驱动 | 后台常驻连接需要前台服务（常驻通知 + 耗电 + 国内 ROM 清理），与 3 M 带宽 / 2 G 内存 / 学生手机的约束不划算。Android 8+ 没有「设置数字角标」的公开 API，launcher 数字来自活动通知，故用一条 `IMPORTANCE_LOW` + `setShowBadge(true)` + `setNumber(未读数)` 的通知驱动。见 [chunks/CHUNK-05-REPORT.md](chunks/CHUNK-05-REPORT.md) 2.1。 |
| **A12** | 壳自建原生 WebSocket（区块 5），而非读网页 DOM / 走 WebMessage 桥 | 与网站仓库零耦合、不依赖网页选择器、不引入 `addWebMessageListener`（老设备会降级）；代价是自写帧与握手（已用 74 个单测钉死）。实测 `dm` 事件自带 `conversation_id`，连「点通知直达会话」也无需网站侧配合。见 [PUSH-CONTRACT.md](PUSH-CONTRACT.md)。 |
| **A13** | 首屏看护（区块 6）「多次复核 + 独立撤销」，不再一次定生死 | 原实现只复核一次、且撤销点（`onPageFinished`）已过期 → 慢速首屏必现「诊断浮层一直挂着」。改为连续两次空白且到点才列诊断、浮层出现后按 2/4/8/15/30 s 轮询撤销。见 [chunks/CHUNK-06-REPORT.md](chunks/CHUNK-06-REPORT.md) 2.1。 |
| **A14** | 网页状态探测（区块 6）按需退避（2 s → 15 s，有变化回快档） | 滚动与手势的主信息源是 `ScrollAwareWebView.onScrollChanged`（同步、零开销），探测只用于配色与兜底补正 → 退避不损手感，省电并少与渲染抢主线程。见 [chunks/CHUNK-06-REPORT.md](chunks/CHUNK-06-REPORT.md) 2.2。 |
| **A15** | 大资源（区块 6）只在壳侧**兜底**拦截，正解留在网站侧 | 实测网站已按视口宽度不挂载看板娘（App UA 零 live2d 请求），壳侧拦截仅防「网站改规则 / 视口偏大」；拦截返回空体 200、**主文档永不拦**、白名单唯一真源在 `AppConfig.BLOCKED_ASSET_PREFIXES`，可远程关。见 [chunks/CHUNK-06-REPORT.md](chunks/CHUNK-06-REPORT.md) 2.3。 |

---

## 4. 组件职责

| 位置 | 职责 | 不负责 |
|---|---|---|
| `MainActivity.kt` | 创建 WebView、加载起始页、返回键策略、状态保存/恢复、深链分发、错误面板 | 业务逻辑、路由判断（交网页） |
| `shell/AppConfig.kt` | 站点 Origin、Host 白名单、UA 标记、起始路径、允许外跳的 scheme 集合 | 不需运行时可变项（可变的走 manifest） |
| `shell/WebShellClient.kt` | `shouldOverrideUrlLoading` 导航裁决、`onReceivedSslError` 处理、主文档错误处理、各页面事件回调 | 下载具体实现（→ 区块 2） |
| `shell/WebShellChromeClient.kt` | 进度回调、控制台日志（仅 debug）、（区块 2）文件选择器、（区块 4）全屏视频 | — |
| `update/*`（区块 3） | 拉取 `app-manifest.json`、版本对比、下载与校验、调用安装器 | 决定"是否强制更新"（由 manifest 声明） |
| `shell/feel/*`（区块 4） | 原生手感：主题色探测与解析（`PageTheme` / `PageStateProbe`）、滚动感知 WebView、下拉刷新 / 返回顶部 / 返回键的纯逻辑策略，以及把它们接线到窗口的 `FeelHost` | 网页内容与路由（仍属 Web 层）；不做 JS 桥接 |
| `shell/push/*`（区块 5） | 前台 WSS 长连接（帧 / 握手 / 心跳 / 退避重连）、未读角标与静音通知、未读计数校准、点通知深链到会话、前后台生命周期接线 | 后台保活与真推送（明确不做）；网页内的徽标（网页自理） |
| `shell/perf/*`（区块 6） | 设备分级、大资源兜底拦截（`shouldInterceptRequest` 白名单）、WebView 缓存清理时机与内存压力响应 | 网页侧的图片懒加载/缩略图（属网站仓库）；资源预取（明确不做） |
| `shell/DiagLog.kt`（区块 6） | 运行轨迹落盘（`filesDir/diag/last-run.txt`，容量封顶） | 任何上报（本应用不采集设备标识） |
| `bridge/*`（按需） | 向网页暴露版本号、`openExternal`、`share`、`toast` 等白名单方法 | 任何文件系统/隐私数据的宽接口 |

---

## 5. 与网站仓库（`nhyzcms/club-management-website`）的契约

> 这些是**跨仓库约定**。改任何一边都要同步这里。

### 5.1 UA 标记

App 在默认 UA 后附加： `NhyzClubApp/<versionName> (Android; <versionCode>)`

例：`Mozilla/5.0 (Linux; Android 14; ...) ... NhyzClubApp/1.2.0 (Android; 12)`

网站侧可以用来：
- 识别"来自 App"，弹出更适合的引导；
- 判定**最低壳版本**，过低时展示"请更新 App"页（而不是白屏）；
- 在问题反馈中自动带上附环境信息。

> 注意：`frontend/src/utils/device.ts` 的 `isMobileDevice()` 靠 `Mobile` 等 UA 特征判定。
> Android WebView 的默认 UA 本身包含 `Android` 与 `Mobile`，所以**无需额外处理**就会进入手机版。

### 5.2 App 模式 UI（网站侧待做）

现状：手机版只有 `/#/m`（首页/新闻/活动/我的），**不等于全功能**。

契约：网站提供一个 **App 模式**入口（建议 `/#/app`，或用查询串 `?app=1` 标记），在该模式下：
- 底部 Tab 为"首页 / 资讯 / 活动 / 消息 / 我的"，覆盖全站所有入口（含社团管理、任务、审核队列、反馈、全景图、考试看板、课表、教程、加入申请、问卷等）；
- 每一级页面都有可达路径，不存在"只能从桌面版才能进的页面"；
- 使壳不需要注入任何原生 Tab（注入原生 UI 会与网页滚动/手势打架，坚决不做）。

细节由**区块 8**（网站仓库 PR）负责，台账见 [FEATURE-MATRIX.md](FEATURE-MATRIX.md)。

### 5.3 远程配置（`app-manifest.json`）

固定路径：`https://8.153.145.175/app-update/manifest.json`（nginx `location` 静态文件，**不经 Django**）。
字段契约见 [APP-MANIFEST.md](APP-MANIFEST.md)。

### 5.4 站点侧已知约束（壳必须适配）

| 约束 | 数值/现状 | 壳的应对 |
|---|---|---|
| 上传体积上限 | nginx `client_max_body_size 20M` | 上传前先在本地提示，避免传到一半 413 |
| 上传协议 | `tus-resumable: 1.0.0`（drf-tus） | 必须提供文件选择器（`onShowFileChooser`），否则传不了 |
| 全景图 | Marzipano + WebGL，解码瓦片为 `preview` + 分层 JPEG | 必须开启硬件加速；低端机回落提示 |
| Live2D 素材 | 静态 resources 约 60 MB | 网站侧按视口宽度（≤1024 px）不挂载看板娘；壳侧再**兜底拦截** `/static/live2d/`（区块 6，开关 `blockLive2dAssets`）—— 实测 App UA 零请求 |
| 实时消息 | Channels/WebSocket | 前台保持连接；后台不保活（区块 5 已实现，契约见 [PUSH-CONTRACT.md](PUSH-CONTRACT.md)） |
| 静态缓存 | 带 contenthash 的资源 1y immutable | WebView 缓存策略直接受益，不需要额外做本地包 |
| 无 Service Worker | 已确认仓库无 SW | 离线兜底由壳提供（原生错误页 + 重试） |
| 网页主题 | `meta[name=theme-color]` 随主题改写（浅 `#ffffff` / 深 `#080f17`） | 壳按需探测并同步状态栏 / 导航栏 / 窗口底色（区块 4；间隔区块 6 起由 `ProbeCadence` 在稳定后退避 2 s → 15 s） |
| `viewport-fit=cover` + `env(safe-area-inset-top)` | 站点已写，但 **Android WebView 里该值为 0** | 不铺满状态栏（ADR-A09）；做真 edge-to-edge 需区块 8 配合注入安全区高度 |

---

## 6. 关键流程

### 6.1 冷启动
```
icon → 启动页（Theme_ClubApp_Splash；API 31+ 走系统 SplashScreen API）
  → MainActivity.onCreate: setTheme(Theme_ClubApp) → 读清单开关 → 先按系统深浅色给一版窗口配色
  → restoreState(savedInstanceState) 存在？→ 恢复（不重载、不显示骨架）
  → 否则：显示首屏骨架 → load START_URL（/#/m 或 manifest 指定的起始路由）
  → onPageStarted: 显示顶部进度条
  → onPageFinished: 隐藏进度条/骨架（错误时切错误面板）→ 开始主题/滚动探测（2 s 起，稳定后由 ProbeCadence 退避到 15 s）
```

### 6.2 导航裁决（`shouldOverrideUrlLoading`）
```
URL → scheme 是 http/https？
        ├─ host ∈ ALLOWED_HOSTS → 返回 false（壳内导航）
        └─ 否则 → 返回 true，交给系统浏览器/对应 App
      scheme ∈ {tel, mailto, sms, geo} → 返回 true，尝试系统处理
      其它（file/content/javascript/intent 等）→ 返回 true，默认拦截
```

### 6.3 TLS 失败（必须显式失败，不得静默放行）
```
onReceivedSslError → handler.cancel() → 原生错误面板
  「安全连接失败：无法验证服务器证书。请确认网络环境（如公共 WiFi 劫持），
    或联系管理员；证书有效期至 2026-11-26。」
```

### 6.4 更新检查（区块 3）
```
启动（3s 后，不阻塞首屏）→ GET app-update/manifest.json（no-cache）
  → latestVersionCode > BuildConfig.VERSION_CODE？
      ├─ 否 → 结束
      └─ 是 → 是否在灰度/强制范围内？→ 提示用户
              → 下载（自托管优先，失败转 GitHub）
              → 校验 sha256 与签名一致
              → 交给系统安装器（REQUEST_INSTALL_PACKAGES）
```

### 6.5 原生手感（区块 4）
```
每次页面渲染（onPageFinished）
  → 收刷新圈 / 淡出骨架 / 刷新「返回顶部」按钮状态
  → 立刻读一次网页主题色（不等轮询周期）
前台按需（PageStateProbe，仅可见时；间隔由 ProbeCadence 退避 2 s → 15 s，有变化立刻回 2 s）
  → 读 meta theme-color 与 window.pageYOffset（固定常量脚本）
  → 配色变了 → 同步状态栏 / 导航栏 / 窗口底色 + 图标明暗
  → 滚动位置 → 兜底校正下拉刷新判断与「返回顶部」按钮
下拉刷新（SwipeRefreshLayout）
  → 仅当网页已回到顶部（ScrollAwareWebView 同步上报）+ 开关打开 + 无错误面板
  → 超过 15 s 未完成 → 自动停止刷新圈
```

### 6.6 弱网与性能（区块 6）
```
首屏看护（骨架 + 空白诊断）
  → 探测「网页画出内容了吗」（不等 onPageFinished 的 load 事件）
  → 画出来了 → 立刻揭骨架；到点仍空 → 连续两次确认后揭骨架 + 列诊断
  → 诊断浮层出现后按 2/4/8/15/30 s 轮询撤销：内容一出现自动消失（保留「点一下复制」）

大资源闸门（shouldInterceptRequest，运行在后台线程）
  → 命中 AppConfig.BLOCKED_ASSET_PREFIXES（live2d 素材）且清单开关打开 → 返回空体 200
  → 主文档永不拦（isForMainFrame 直接放行）；白名单唯一真源在 AppConfig

缓存与内存
  → 默认不动 WebView 缓存（/static/ 是 immutable 长缓存，弱网下「不重复下载」的依靠）
  → 空间告急 / 低于档位阈值 / 超 30 天 → 清一次磁盘缓存
  → 系统报告内存压力 → 清内存缓存（clearCache(false)，不动 Cookie 与登录态）
```

---

## 7. 生命周期与状态恢复

- Activity `configChanges` 声明旋转/分屏等，避免旋转时重建 WebView（重建会丢页面状态）；
- 系统回收进程后重建：`WebView.saveState/restoreState` 恢复浏览历史；
- 登录态由 Cookie 保存（`CookieManager` 默认持久化于 App 私有目录），**无需自建 Token 存储**；
- 网页主题色探测（`PageStateProbe`）只在 Activity 可见时轮询（`onResume` 起、`onPause` 停），区块 6 起还会按需退避，不会在后台耗电；
- 退出登录/清除数据：提供"清除缓存"入口（区块 4），会同时清 Cookie（即登出）。

---

## 8. 版本号与构建变体

- `version.properties`：`versionCode` / `versionName`；CI 在打 tag `vX.Y.Z` 时用 tag 覆盖；
- 变体：`debug`（可调试、WebView 远程调试开、可指向本地 dev server）/ `release`（`minifyEnabled` + `shrinkResources`）；
- `release` 未配置签名 Secrets 时**构建失败的 APK 不会发布**（CI 会直接报错，避免发出不能覆盖安装的包）。

---

## 9. 扩展点（预留，暂不实现）

- 本地插件式页面（如"扫一扫"）→ 通过 manifest 开关启用；
- 真·推送（国内无 FCM）→ 方案候选：前台服务长连接 / 厂商推送 SDK / 短信+邮件（当前先用站内轮询）；
- iOS 复用同一份"Web 层契约"（UA 标记 + manifest + App 模式 UI）；
- 单元/仪器测试（区块 9）。
