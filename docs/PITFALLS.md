# 坑位与排查手册（PITFALLS）

> 面向读者：接手 `nhyzcms/app` 的开发者与 AI 助手。
> 最后更新：2026-10-14。本文只记录**已经真实踩过并且已经修好**的坑；每条尽量给出"症状 → 根因 → 修法 → 怎么复现/验证"。
> 与 `docs/ENGINEERING-ENV.md` 配套阅读：那边讲环境与工作流，这边讲具体故障。

---

## 0. 先看这个：排查三原则

1. **先确认请求有没有发出去**。客户端"没反应"时不要猜代码，先看服务器 `nginx access.log`：原生请求的指纹是 `HTTP/1.1` + `Referer "-"`，网页请求是 `HTTP/2.0` + 带 `Referer`。再加一个"只有原生代码会发"的探针请求（本项目的探针是 `/app-update/manifest.json`），就能一刀切分"壳层有没有跑"。
2. **症状分层**：HTML 有没有拿到（网络/TLS）→ JS 有没有跑（WebView/控制台）→ UI 有没有画（CSS/主题）→ 数据有没有到（接口/权限）。每层都有独立的排查入口，别跳层。
3. **能本地复现就别靠 CI**。本工作区没有 Android SDK，但 §5 的 stub 编译能拦掉大部分"未解析引用/漏 import/资源名错"，比等 CI 快几十倍。

---

## 1. Android 资源与构建（AAPT / Gradle）

### 1.1 XML 注释里出现连续两个短横线 → 构建直接中断

```xml
<!-- 这样写会炸：-- 连续两个短横线在 XML 注释里非法 -->
```

症状：AAPT2 报错并中断整个 `assembleDebug`，错误信息指向该行。
最容易犯的姿势：**把报错原文照抄进注释**（报错里常含 `--`），于是"改注释"又炸一次。
修法：注释里不要出现 `--`；要举例就用「两个短横线」这样的文字。
本地防线：`scripts/preflight.sh` 会扫 XML 注释。

### 1.2 `setWebViewClient` 在 API 35 上参数是 `@NonNull`

```kotlin
// 错：onDestroy 里想"清理"而传 null
webView.webViewClient = null

// 对：传一个空实现
webView.webViewClient = WebViewClient()
webView.webChromeClient = WebChromeClient()
```

### 1.3 `WebSettings` 的 safe browsing 只有 setter

```kotlin
// 错：没有 getter，Kotlin 属性语法编译不过
settings.safeBrowsingEnabled = true

// 对
settings.setSafeBrowsingEnabled(true)
```

### 1.4 Kotlin 属性在自身初始化块里自引用 → `recursive problem`

```kotlin
// 错：clearPoll 在自己的 lambda 里引用自己
private val clearPoll = object : Runnable {
    override fun run() { probeContent { postDelayed(clearPoll, 2000) } }
}

// 对：进 lambda 之前先取一个局部引用
private val clearPoll: Runnable = object : Runnable {
    override fun run() {
        val self: Runnable = this
        probeContent { postDelayed(self, 2000) }
    }
}
```

症状：`Type checking has run into a recursive problem.`（CI 报在 Kotlin 编译阶段）
本地防线：`scripts/preflight.sh` 第 10 组规则；用 `kotlinc` + 最小 stub 可以本地复现。

### 1.5 主题/样式：点号在预检里会被归一成下划线

预检脚本用 `R.style.X` 逐字比对资源名，而它把 `.` 归一成 `_`。因此主题名统一用下划线风格：
`Theme_ClubApp`、`Theme_ClubApp_Splash`（**不要**写 `Theme.ClubApp`，否则预检会误报"资源不存在"）。

### 1.6 不要留 `values-night/themes.xml`

深浅色不要用"同名 style 在两个目录各写一份"的方式表达：一旦某一侧漏项，整体替换会静默丢属性。
本项目的做法：删掉 `values-night/themes.xml`，深浅色差异由 `@color/@bool` 表达。

### 1.7 依赖与 runner

- **不提交 `gradle/wrapper/gradle-wrapper.jar`**：CI 用 `gradle/actions/setup-gradle@v4` 指定 Gradle 8.13。
- **不要用 `android-actions/setup-android@v3`**：在 runner 上会失败（8 秒就挂）。改为自写 `scripts/ci-prepare-android-sdk.sh`，直接把 `$SDK/licenses/android-sdk-license` 写好，绕过 `sdkmanager --licenses` 的交互失败。
- **`runs-on` 固定 `ubuntu-24.04`**：不要用 `ubuntu-latest`（发行版迁移会把构建搞红）。
- `actions/setup-java@v5`，JDK 17。

### 1.8 发版阻断

`assembleDebug` 必须排在单测之后、release 变体作为发版阻断；失败时把 `unit-test.log` / `build.log` 的尾巴贴成 PR 评论（见 `docs/CI-WORKFLOW.md` §5）。

---

## 2. WebView 运行时（最容易出"空白屏"）

### 2.1 后台线程读 `webView.settings` → 直接崩溃

症状：启动瞬间闪退，日志里有 `A WebView method was called on thread 'X'. All WebView methods must be called on the same thread.`
根因：`targetSdk >= 18` 时 AOSP 的 `WebView.checkThread()` 会**直接抛 RuntimeException**（不是警告）。
本项目事故：`UpdateController` 里 `fetcher by lazy` 在后台线程被首次触发，而初始化时要读 UA。
修法：所有 WebView 访问固定在主线程；需要 UA 就在主线程先取出来（`userAgentOnMainThread()`）。
加保险：壳侧崩溃自记录（`shell/CrashText.kt` + `shell/CrashReporter.kt`），落盘到 `filesDir/diag/`。

### 2.2 骨架屏"永不揭开" → 看起来就是黑/白屏

根因：一开始把"揭开骨架"挂在 `onPageFinished` 上，而该回调要等 `load` 事件；站点是 SPA，`load` 可能很晚甚至不触发。
修法（三层）：
1. **按 DOM 内容探测**揭开（`feel/ContentProbe.kt`），而不是等事件；
2. **纯计时硬看门狗**（12 s）兜底，到点无论如何先给用户可见内容；
3. 壳侧 HTTP 探测 + 诊断浮层 + 轨迹落盘（`DiagLog`）。

配套经验：**不要**用"隐藏骨架 / 改 WebView alpha"之类的方案，容易在异常路径上把内容一起藏了。

### 2.3 看门狗的"误报"要按多次复核来判断

一次性判定"空 DOM"很不可靠（页面正在重渲染）。现方案：`feel/RenderWatchPolicy.kt` 在 5/12/25/45 s 多次复核，**连续 2 次空**且到点才判定为异常并弹诊断浮层；浮层按 2/4/8/15/30 s 轮询，一旦恢复自动撤销。
探测频率：`feel/ProbeCadence.kt` 退避 `2s → 4s → 8s → 15s`（低内存档 30 s），有变化立刻回快档；`PageStateProbe` 硬下限 1500 ms —— 探测本身不能成为耗电元凶。

### 2.4 TLS：老设备信任库缺根证书

症状：空白屏 + 诊断浮层里 `Trust anchor for certification path not found`。
根因：站点用的是 ZeroSSL DV 证书，链路根是 `Sectigo Public Server Authentication Root R46`（2021 年新建），老 Android 信任库没有它。
修法：把 R46 作为**额外信任锚**打进 App：
- `app/src/main/res/raw/sectigo_r46.pem`
- `network_security_config.xml` 里 **base-config 与 debug-overrides 两处**都要写（`debug-overrides` 是**替换**语义，只写一处会失效）。
红线：`onReceivedSslError` 里**绝不** `proceed()`；不做证书固定（站点 90 天证书 + IP SAN，固定必在换证日全量失联）。
排查提示：站点证书每年到期前需要续期，**续期是运维动作，不是 App 发版动作**；换过证书链就要重新核对 R46 路径。

### 2.5 拦截重资源

`perf/HeavyAssetPolicy.kt` + `WebShellClient.shouldInterceptRequest` 可以拦 Live2D 素材（返回空体 200）：
- 主文档**永不拦**；
- 白名单唯一真源是 `AppConfig.BLOCKED_ASSET_PREFIXES`（改白名单只改这一处）；
- 开关 `blockLive2dAssets` 默认 true。

---

## 3. 会话、Cookie 与推送（区块 5 的真实故障）

### 3.1 症状

装上新包后"完全没反应"：没有通知、没有角标、App 内也没提示；只有手动打开私信页才看到新消息。

### 3.2 定位过程（可复用的方法）

1. 服务器 access.log 里 `/app-update/manifest.json` 有 4 次原生请求 → **壳是新的、启动正常**；
2. 但原生发出的 `unread_count` 拉取请求 **= 0 条** → 说明 `PushHost` 第一步就 return 了；
3. 服务器侧 3 worker + Redis 频道层、nginx Upgrade 转发、`/ws/messaging/` 大量 `101` → **链路健康**；
4. 结论：问题在客户端"拿 Cookie"这一步。

### 3.3 根因

`onActivityStarted` 里只取**一次** `CookieManager.getCookie()` 快照。冷启动刚开始时 Cookie 库可能还没就绪（返回空）→ 连 WS 与未读拉取都直接 return；而用户一直停在前台，`onActivityStarted` 不再触发 → **永远不恢复**。
第二个坑：`startPush()` 只看 `socket != null` 判重，而连接线程可能已退出 → 永久连不上。

### 3.4 修法

- 主线程**看门狗每 15 s** 重读 Cookie，按登录态连/断（登录后无需切后台即自动接上）；
- **兜底刷新每 90 s** 拉一次未读（WS 事件丢了也会亮）；
- 判重改看 `PushSocket.isRunning`；
- 可观测性（**仅 debug**）：`PushDiagnostics` 最多 3 条 toast + 通知栏常驻状态（独立渠道 `SELF_TEST_CHANNEL_ID`、`setShowBadge(false)`）+ 写 `DiagLog`；一次性自检通知。
- 原生请求加独立 UA 标记：`NhyzClubShell/<ver> (native; <code>)` —— 以后在 access.log 里一眼区分原生/网页。

### 3.5 纪律

诊断代码必须走 `PushDiagnostics`、必须 debug 限定、必须用独立通知渠道且 `setShowBadge(false)`（否则调试通知会污染真实角标）。`scripts/preflight-push.sh` 已把这几条固化成规则。

---

## 4. 文件上传 / 下载 / 分享

### 4.1 下载必须手动带 Cookie

用系统 `DownloadManager` 下载**受权限保护**的文件时，必须自己把 Cookie 塞进 `DownloadManager.Request`，否则服务器按匿名处理 → 403。
落盘位置必须由 `DownloadPolicy.destination()` 统一收口；`MainActivity` 按 `Build.VERSION.SDK_INT` 分支：
- API ≤ 28：需要 `WRITE_EXTERNAL_STORAGE`（清单里必须带 `android:maxSdkVersion="28"`）才能写公共「下载」目录；
- API ≥ 29：走 App 目录，不申请任何存储权限。

本 App 权限只有 4 个：`INTERNET`、`ACCESS_NETWORK_STATE`、`CAMERA`、`WRITE_EXTERNAL_STORAGE(maxSdk=28)`。**不申请 `READ_MEDIA_IMAGES`**（导航选择器不需要）。

### 4.2 上传体积前置提示

站点 nginx `client_max_body_size 20M`。`shell/UploadLimit.kt` 在选文件阶段就提示"过大"，避免传半天最后吃 413。

### 4.3 分享要分享"文件本体"

`MediaSharer` 的逻辑：后台线程用 `HttpURLConnection` 带 Cookie/UA 抓图片 → 存到 `cacheDir/share/` → `FileProvider` → `ACTION_SEND`；失败自动退回"分享链接"。
`FileProvider` authority 的单一真源是 `AppConfig.FILE_PROVIDER_AUTHORITY`（= `BuildConfig.APPLICATION_ID + ".fileprovider"`，debug 会带 `.debug`），清单、`file_paths.xml`、代码三处必须一致（预检会查）。

### 4.4 `FileChooserHost`：回调恰好一次

`onShowFileChooser` 的 `ValueCallback` 必须**恰好回调一次**（用户取消也要回调，返回 `null`）。所有出口（选文件、拍照、取消、异常）都要收敛到同一个 `deliver()`。

---

## 5. 本地校验链的坑

### 5.1 白名单要手工维护

`/workspace/klocaltest.sh`（纯逻辑单测）与 `/workspace/kandroidcheck.sh`（stub 编译 Android 依赖层）各有一份文件白名单。**新增文件必须同步加进去**，否则"跑了但没覆盖"，会给出虚假的安全感。

### 5.2 stub 编译的已知误报

同名嵌套类（例如 shell 包的 `MediaSharer.Result` 与 push 包的 `WsHandshake.Result`）会让"跨包引用必须 import"的规则误报，需要在规则里排除"本包内自己声明过的类型名"。
另外 stub 只覆盖实际用到的 API 子集，签名偏差可能造成误报/漏报 → **CI 仍是最终判据**。

### 5.3 stub 复现法（通用技巧）

任何"本地没有 SDK 所以编不了"的 Android 文件，都可以用 `/workspace/androidstub/` + `kotlinc` 做最小 stub 编译来复现/验证编译错误。区块 6 的 `recursive problem` 就是这么在推 CI 之前定位并验证修法的。

---

## 6. Kotlin / 测试代码写法坑

### 6.1 raw string 承载"首尾都带引号"的 JSON

```kotlin
// 错：3 个引号会让 JSON 未闭合，parse 返回 null，只报 "AssertionError at MiniJsonTest.kt:27"
val s = """{"a":1}"""

// 对：首尾各 4 个引号（多出来的那一个属于内容）
val s = """"{"a":1}""""
```

更稳的做法：用普通字符串拼 JSON，并对断言对象做逐字符码点校验。
本地防线：`scripts/preflight.sh` 3.6 段禁止源码里出现连续四个引号。

### 6.2 一次性读 computed style 会撞重渲染窗口（网站侧经验，同样适用）

用一次性的 `evaluate` 去读 `transitionProperty`，可能正好落在重渲染窗口里读回空串。改为可重试的断言（Playwright 的 `toHaveCSS`），让框架自己等稳定。

---

## 7. Git / CI / 协作流程坑

### 7.1 用 `GITHUB_TOKEN` 推的提交**不会触发 CI**

用一次性 workflow 里的 `GITHUB_TOKEN` push 之后，必须**自己再推一次**（哪怕只改文档），让 CI 验证新的 head。

### 7.2 分批推送的中间态会制造红 run

仓库历史里有一批红 run 全是"分批推送的中间态"（代码推一半、测试文件写错）。纪律：**每批单看都必须可编译、CI 必绿**；独立新文件先行，接线/既有文件最后；中间态不得引用尚不存在的符号。

### 7.3 大文件重写会静默丢内容

重写既有文件后必须 `git show FETCH_HEAD:<path> | diff - <本地>`；新文件 `git diff` 不显示，必须用 `git show` 比对。历史事故：`AppConfig.BLOCKED_ASSET_PREFIXES` 被弄丢过。
（注：这里的"必须复核"与"单次载荷上限"无关 —— 上限已解除，见 `docs/ENGINEERING-ENV.md`；复核纪律依然有效。）

### 7.4 匿名访问 api.github.com 会被限流

共享出口 IP 下匿名调用 60 次/小时就会 403。**一律走 `gh api`**（认证通道）。`raw.githubusercontent.com` 也会间歇超时，取文件优先用本地 clone / `gh api`。

### 7.5 用户可能在你还在推进时就合并了 PR

一旦发现 PR 已被合并，**后续新提交必须另开分支/PR**（同一分支的追加提交不属于已关闭的 PR）。

### 7.6 `delete_file` 报 cancelled 可能其实已生效

客户端报 cancelled 时先确认远端状态，重复删除会报 `GitRPC::BadObjectState`。

---

## 8. 与网站侧协作时的坑（跨仓库）

- **URL 模板不能过 `build_absolute_uri`**：`{z}` 会被转义成 `%7Bz%7D`，前端换不上、瓦片全 404。需要用只拼前缀的 `_absolute_raw`。
- **SPA 入口 HTML 缺 `Cache-Control: no-cache`**：会让"网站发版即生效"（L0 热更新）迟滞 —— 壳层能做的就是每次冷启动 `clearCache(true)`（已实现），网站侧修才是根治。
- **nginx**：worker 用户是 `nginx`（**没有 `www-data`**）；`location` 内一旦用了 `add_header`，就**不再继承** server 级 `add_header` → HSTS / Alt-Svc 必须在 location 内重复声明。
- **Django 侧**（网站仓库）曾踩：新增 `Meta.permissions` 必须同时写 `AlterModelOptions` 迁移，否则权限不落库；给模型加外键会让"批量 delete 的 fast-delete 优化"失效，并且某些"把 schema 迁到旧状态"的迁移测试如果不恢复，会影响后续测试。
- **服务器参数**：2 核 / 2 GB / 3 Mbps。别在上面跑 Gradle、别在上面跑测试套件、别把大体积临时文件堆在 `/tmp`（该机器曾因多个 clone 把 `/tmp` 撑到 1.2 GB）。

---

## 9. 一页速查（命令）

```bash
# 本地静态预检（不需要 JDK/SDK，几秒）
bash scripts/preflight.sh
bash scripts/preflight-feel.sh        # 会连带跑同目录所有 preflight-*.sh
python3 /workspace/ktcheck.py .
bash /workspace/klocaltest.sh         # 纯逻辑单测（kotlinc + JUnit）
bash /workspace/kandroidcheck.sh      # stub 编译 Android 依赖层

# 推送后逐字复核
git fetch origin <branch>
git show FETCH_HEAD:<path> | diff - <本地>

# CI 报错（失败作业会把关键报错贴成 PR 评论）
gh api repos/nhyzcms/app/issues/<pr>/comments --jq '.[-1].body'

# 造一个版本号（用于跨机器 debug 包互相升级的验证）
VERSION_CODE=2 VERSION_NAME=0.2.0 gradle :app:assembleDebug
```

---

## 10. 变更记录

- **2026-10-14**：初版。汇总 2026-10-11 ~ 10-14 期间真实踩过并已修复的坑（构建、WebView、会话推送、上传下载、本地校验、协作流程、跨仓库）。

<!-- EOF-SENTINEL-PITFALLS-20261014 -->
