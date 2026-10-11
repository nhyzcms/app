# 区块 2 开发报告：全部功能所需的能力补齐

- **分支**：`feat/02-capabilities`（待人工审阅合并）
- **日期**：2026-10-11（含当日根据管理员确认的追加一轮）
- **仓库**：`nhyzcms/app`
- **一句话**：把“网页里每个入口都能在 App 内真正跑起来”所缺的**系统能力**补齐——
  选文件 / 拍照上传、带会话 Cookie 的下载、长按保存/分享/复制/浏览器打开、上传体积前置提示，
  并把图标与品牌色换成**社徽钴蓝**。

> 依据：`docs/ROADMAP.md` 区块 2、`docs/chunks/CHUNK-02-BRIEF.md` 的交付物清单。

---

## 1. 交付物

### 1.1 新增（壳能力层）

| 文件 | 作用 |
|---|---|
| `shell/FileChooserHost.kt` | `onShowFileChooser` 唯一入口；保证 `ValueCallback` **恰好回调一次** |
| `shell/CameraCapture.kt` | FileProvider 临时文件 + 相机 Intent |
| `shell/PermissionsPolicy.kt` | 运行时权限策略（纯函数）：相机 + “仅 Android 8/9 需要的”写存储权限 |
| `shell/UploadLimit.kt` | 20 MB 上传上限的本地前置判定（纯函数） |
| `shell/DownloadPolicy.kt` | 落盘位置/文件名/可下载性判定（纯函数） |
| `shell/DownloadStarter.kt` | 交给系统 `DownloadManager`，**手动带会话 Cookie 与 UA** |
| `shell/LongPressPolicy.kt` | 长按命中判定（图片 / 链接 / 不管，纯函数） |
| `shell/MediaSharePolicy.kt` | 分享图片文件本体的前置判定（纯函数） |
| `shell/MediaSharer.kt` | 分享图片**文件本体**：下载到缓存 → FileProvider → `ACTION_SEND` |
| `res/xml/file_paths.xml` | FileProvider 只暴露 `cacheDir/capture/` 与 `cacheDir/share/` |

### 1.2 新增（纯逻辑单测，不需要设备 —— 共 42 个用例）

| 文件 | 覆盖 |
|---|---|
| `test/…/UploadLimitTest.kt` | 上限边界、多文件求和、文案格式 |
| `test/…/DownloadPolicyTest.kt` | 落盘分级（SDK × 权限）、RFC 5987 文件名、路径穿越清洗、scheme 判定 |
| `test/…/LongPressPolicyTest.kt` | 命中类型映射、`javascript:`/`blob:` 拒绝 |
| `test/…/PermissionsPolicyTest.kt` | “没 capture 属性也要给拍照入口”、下载权限只在 API ≤ 28 |
| `test/…/MediaSharePolicyTest.kt` | 可分享性、大小上限边界 |
| `test/…/NavPolicyTest.kt` | 导航裁决回归（站点只能壳内、明文拦下、未知 scheme 拦下） |

### 1.3 修改

| 文件 | 改动 |
|---|---|
| `MainActivity.kt` | 能力接线：选择器 / 相机权限 / 两阶段下载（含存储权限）/ 长按菜单 / 体积提示 |
| `shell/WebShellChromeClient.kt` | 新增 `onShowFileChooser` 钩子 |
| `shell/AppConfig.kt` | 新增 `FILE_PROVIDER_AUTHORITY`（拍照与分享共用的**单一真源**） |
| `AndroidManifest.xml` | `CAMERA`、`WRITE_EXTERNAL_STORAGE(maxSdkVersion=28)`、两个 `uses-feature(required=false)`、`<queries>`、FileProvider |
| `res/values/strings.xml` | 新增 25 条文案；删掉已实现的占位提示 |
| `res/values/colors.xml` | 品牌色改为社徽钴蓝 `#0756A2`，新增 `brand_accent #79C2FA` |
| `res/drawable/ic_launcher_foreground.xml` | 图标改为“三道波 + 圆点”（社徽意象） |
| `app/build.gradle.kts` | 新增 **仅测试类路径** 的 `junit:junit:4.13.2` |
| `.github/workflows/android.yml` | CI 新增“纯逻辑单测”步骤（debug 在 APK 之前；release 作为发版阻断） |
| `scripts/preflight.sh` | 新增 6 条静态规则（见第 4 节） |
| `scripts/ci-extract-errors.sh` | 报错模式补充单测失败行 |
| `docs/FEATURE-MATRIX.md`、`docs/CI-WORKFLOW.md`、`docs/SECURITY.md`、`README.md` | 同步状态、单测与权限基线 |

---

## 2. 关键决策（含与简报不同的地方，请重点看）

### 2.1 壳**不**实现 tus

壳只做“打开选择器 + 回传 `content://` URI”，分片上传完全交给网页里的 tus 客户端。

### 2.2 权限：只留四个，存储权限被压到 Android 8/9

| 场景 | 做法 | 需要权限？ |
|---|---|---|
| 选文件（图片/文档/视频/多选） | `FileChooserParams.createIntent()` → 系统 SAF | ❌ 系统按 URI 临时授权 |
| 拍照上传 | FileProvider 临时文件 + `ACTION_IMAGE_CAPTURE` | 仅相机 |
| 下载（Android 10+） | `DownloadManager` 写公共「下载」目录 | ❌ |
| 下载（Android 8/9） | 先申请 `WRITE_EXTERNAL_STORAGE`（清单带 **maxSdkVersion=28**）→ 写公共目录；拒了则退 App 目录 | 仅这两个版本 |
| 分享图片文件本体 | 下载到 App 缓存 + FileProvider | ❌ |

- **管理员 2026-10-11 确认**可以把下载落到公共目录，所以 8/9 上申请写权限（这是本项目唯一为兼容旧系统开的口子，已写进 SECURITY.md §2.1）。
- **仍然不申请 `READ_MEDIA_IMAGES`**：选图走系统选择器，不需要它；只有自建“相册九宫格”才需要，届时在区块 4/8 单独决策。

### 2.3 相机权限的时机

清单声明了 `CAMERA` → 系统要求调用方自持 → 只在“**将要**提供拍照入口”时申请
（accept 含图片通配，或网页写了 `capture`，或设备有摄像头）；拒了就退化为只能选文件，
“禁止且不再询问”给设置页引导。**管理员已确认维持这个时机**（2026-10-11）。

> 关键事实：网站里的 `input` 都是图片/视频的 accept 通配、**没有** `capture` 属性，
> 所以不能只看 `isCaptureEnabled()`（已写成单测钉死）。

### 2.4 上传体积超限：先提示，由用户决定

超过 20 MB 弹框：`仍然上传` / `换一个文件`。选后者向网页回传 `null`（等于没选文件），
**不会**出现“传到一半才 413”。

### 2.5 长按图片 / 链接

- 图片：**保存图片** / **分享图片** / 分享链接 / 复制链接 / 用浏览器打开；
- 链接：分享链接 / 复制链接 / 用浏览器打开；
- 其它命中（文本选择、输入框、电话/邮箱/地图）返回 `false` 交回系统。

分享图片文件本体（管理员 2026-10-11 确认要）：先把图片下到 `cacheDir/share/`（**带会话 Cookie**，
否则拿到的是登录页 HTML），再 FileProvider + `ACTION_SEND`；
失败/超 20 MB/地址不支持（`blob:`）时**自动退回分享链接**，不让用户两手空空。

### 2.6 品牌色与图标（管理员 2026-10-11 确认 #0756A2 正确）

| 令牌 | 值 | 来源 |
|---|---|---|
| `brand` | `#0756A2` | 网站 `cobalt.css` 的 `--brand-800`，注释标注为 **SEAL BLUE 社徽主色**（锚定 `oklch(0.455 0.141 254)`） |
| `brand_accent` | `#79C2FA` | 同文件的 `--brand-300`（社徽里的浅蓝） |

图标为**纯矢量**的“三道波 + 圆点”（意象化简化，非社徽原图矢量化），仓库仍零二进制资源。

### 2.7 引入 junit（仅测试类路径）+ CI 跑单测

`testImplementation` 不进 APK，收益远大于代价：本仓库没有本地 Android 构建环境，
CI 是唯一能编译/验证的地方，纯函数不该只靠人眼审查。

---

## 3. 实现要点（后人改代码前请先读）

1. **`ValueCallback` 必须且只能回调一次**：所有出口都收敛到 `FileChooserHost.deliver(...)`，
   漏一条网页的 input 就永久卡死。
2. **所有拿着会话的请求都要带 Cookie**：`DownloadManager` 不共享 CookieJar；
   `MediaSharer` 的 `HttpURLConnection` 也一样。
3. **FileProvider authority 的单一真源在 `AppConfig.FILE_PROVIDER_AUTHORITY`**，
   必须与清单的 `${applicationId}.fileprovider` 一致（preflight 会拦）。
4. **临时文件都在 `cacheDir/`**：`capture/`（拍照）、`share/`（分享）；没用到就删，
   用到的留给网页/接收方，下次启动清 24 小时以前的。
5. **落盘位置统一由 `DownloadPolicy.destination(sdk, hasWritePermission)` 判定**，
   不要在下载器里写死（Android 8/9 无权限时写公共目录会 SecurityException）。
6. **Kotlin 块注释里不能出现「斜杠 + 星号」**：块注释会嵌套，KDoc 里写一个 MIME 通配示例
   就会把整个文件吞掉，报错却是 `Unclosed comment` + 别的文件 `Unresolved reference`。
7. **预检自身的两个教训**：代码模式只在非注释行搜（`code_grep`）；
   python 的 `sys.exit("消息")` 走 stderr，命令替换里必须 `2>&1`。

---

## 4. 新增的静态预检规则（错误只能犯一次，共 6 条）

| # | 规则 | 为什么 |
|---|---|---|
| 1 | 禁止 `addJavascriptInterface` | 安全文档 §4：桥接只能走 `WebMessageListener` |
| 2 | 禁止出现 `.proceed()` | `onReceivedSslError` 绝不能放行 |
| 3 | 权限声明按**元素**解析：禁读媒体/读外部存储；写外部存储必须带 `maxSdkVersion="28"` | 旧规则“见到写权限就报错”已不适用（现按管理员确认只在 8/9 生效） |
| 4 | 用了 `getUriForFile` 就必须满足：清单 `${applicationId}.fileprovider`、代码有 `BuildConfig.APPLICATION_ID`、`file_paths.xml` 存在 | 三者缺一，拍照/分享只在运行时才炸 |
| 5 | 出现 `setDestinationInExternalPublicDir` 就必须由 `DownloadPolicy.destination()` 收口，且按 `Build.VERSION.SDK_INT` 分支 | 否则 Android 8/9 会 SecurityException |
| 6 | Kotlin 块注释里不得出现「斜杠 + 星号」 | 块注释会嵌套，会把整个文件吞掉（本块真踩过） |

> 新规则一律用 `code_grep`（只搜非注释行），并且都做过**双向验证**：
> 干净仓库 → 通过；故意注入问题 → 精确报出行号。

---

## 5. 验收清单（真机，12 条）

先按 `docs/BUILD.md` 取 CI 的 artifact `app-debug-apk` 安装。

| # | 操作 | 预期 |
|---|---|---|
| 1 | 个人中心 → 更换头像 → 选相册图片 | 出现系统选择器（**多一个“相机”入口**），选中后上传成功 |
| 2 | 同上，在选择器里点「相机」 | 首次弹相机权限申请；允许后能拍照并上传 |
| 3 | 再点「相机」，在权限弹窗选“拒绝” | 界面继续可用，退化为只能选文件，有明确提示 |
| 4 | 选一张大于 20 MB 的图片 | 弹「超过上传上限」；点“换一个文件”后网页无反应，点“仍然上传”才继续 |
| 5 | 任务详情 → 上传附件（pdf/docx） | 能选到文件并上传成功 |
| 6 | 反馈页 → 多选图片/视频 | 能一次选多个并上传 |
| 7 | 点一个受权限保护的下载 | 通知栏出现下载进度；Android 10+ 在系统「下载」目录；**Android 8/9 首次会先问存储权限，允许后也在公共目录** |
| 8 | Android 8/9 上在第 7 步拒绝存储权限 | 仍然能下，落在应用目录，有明确提示 |
| 9 | 长按新闻里的图片 | 菜单：保存图片 / **分享图片** / 分享链接 / 复制链接 / 用浏览器打开 |
| 10 | 点「分享图片」并选微信/QQ | 能发出**图片本体**（不是链接）；弱网下先看到“正在准备图片…” |
| 11 | 长按一段正文文字 | **不弹我们的菜单**，仍是系统文本选择 |
| 12 | 全景图管理 → 导入一张原图（≈8 MB） | 能选到文件；上传正常（切后台再回来不崩） |

> 第 1~6、12 条对应的**页面入口**在区块 8（网站侧 App 模式 UI）之前，手机上可能只能从桌面版路径进到这些表单——
> 这不影响本块“能力是否接上”的验收。

---

## 6. 已知限制（本块故意不做）

- `blob:` 地址的下载/分享：会明确提示或退回分享链接，需要时在区块 6 用 JS 桥接读取；
- 下载的“仅 WiFi”开关与分段策略：区块 6；
- 上传进度的原生显示：进度在网页里（tus 客户端已有 UI）；
- ⏳ 纯函数单测跑 CI，**真机行为（相机 URI 授权、DownloadManager 落盘、分享给微信）只能由你验证**；
- ⏳ `docs/ARCHITECTURE.md` §4 的组件表尚未同步本块新增的 9 个类
  （清单见本报告 §1.1 与 `README.md` §3；下次动那份文档时一并补，避免整篇重写抄错）。

---

## 7. 管理员已确认的四件事（2026-10-11，已按此实现）

| # | 问题 | 结论 | 落到了哪里 |
|---|---|---|---|
| 1 | 品牌色是否就是 `#0756A2` | **是** | `colors.xml`、图标、本报告 §2.6 |
| 2 | 是否可申请存储权限把下载落到公共目录 | **可以** | 清单 `WRITE_EXTERNAL_STORAGE(maxSdkVersion=28)` + 两阶段下载 + SECURITY.md §2.1 |
| 3 | 是否需要分享图片**文件本体** | **要** | `MediaSharer` + 长按菜单「分享图片」 |
| 4 | 相机权限的申请时机 | **保持现状**（打开选择器时） | `handleFileChooser` 现状不改 |

> 仍开放的一项：若你能提供社徽的 **SVG/AI 原文件**，图标可按原坐标精确重建（现在是意象化简化版）。

---

## 8. 回滚

- 本块只改 Android 壳仓库，**对网站与服务器零影响**；
- 合并前分支即 `feat/02-capabilities`；合并后如需回滚，`git revert` 该 merge commit 即可。

---

## 附：本块的验证方式（实际执行的）

| 手段 | 结果 |
|---|---|
| `bash scripts/preflight.sh`（服务器 `/tmp` 只读 clone，最终提交 f96379e） | ✅ 全部通过 |
| 新规则反向验证（故意注入“斜杠+星号”、改错权限属性） | ✅ 精确报出行号/原因 |
| `.kt` 文件括号配平（带状态机脚本，跳过注释与字符串） | ✅ 21 个文件全通过 |
| 单测用例数统计 | ✅ 42 个（6 个测试文件） |
| `testDebugUnitTest` + `assembleDebug` | 交 CI（失败时关键报错自动贴成本 PR 评论） |

> 本块 CI 红过 4 轮，均已修复并固化成规则：
> ① 预检的 `.proceed()` 规则被自身警示注释误报 → `code_grep`；
> ② KDoc 里的斜杠加星号（块注释嵌套）→ 预检规则 6；
> ③ 预检报错详情走 stderr → 加 `2>&1`；
> ④ 权限/落盘规则与“追加一轮”后的实现不一致（旧规则「见到写权限就报错」）→ 规则 3/5 重写为按元素解析 + 收口校验。
