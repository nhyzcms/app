# 区块 2 开发报告：全部功能所需的能力补齐

- **分支**：`feat/02-capabilities`（待人工审阅合并）
- **日期**：2026-10-11
- **仓库**：`nhyzcms/app`
- **一句话**：把“网页里每个入口都能在 App 内真正跑起来”所缺的**系统能力**补齐——
  选文件 / 拍照上传、带会话 Cookie 的下载、长按保存与分享、上传体积前置提示，
  并顺手把图标与品牌色换成**社徽钴蓝**。

> 依据：`docs/ROADMAP.md` 区块 2、`docs/chunks/CHUNK-02-BRIEF.md` 的交付物清单。

---

## 1. 交付物

### 1.1 新增（壳能力层）

| 文件 | 作用 |
|---|---|
| `shell/FileChooserHost.kt` | `onShowFileChooser` 唯一入口；保证 `ValueCallback` **恰好回调一次** |
| `shell/CameraCapture.kt` | FileProvider 临时文件 + 相机 Intent（authority 用 `BuildConfig.APPLICATION_ID` 拼） |
| `shell/PermissionsPolicy.kt` | 运行时权限策略（纯函数）：只申请相机 |
| `shell/UploadLimit.kt` | 20 MB 上限的本地前置判定（纯函数） |
| `shell/DownloadPolicy.kt` | 落盘位置 + 文件名解析/清洗 + 可下载性判定（纯函数） |
| `shell/DownloadStarter.kt` | 交给系统 `DownloadManager`，**手动带会话 Cookie 与 UA** |
| `shell/LongPressPolicy.kt` | 长按命中判定（图片 / 链接 / 不管，纯函数） |
| `res/xml/file_paths.xml` | FileProvider 只暴露 `cacheDir/capture/` |

### 1.2 新增（纯逻辑单测，不需要设备）

| 文件 | 覆盖 |
|---|---|
| `test/…/UploadLimitTest.kt` | 上限边界、多文件求和、文案格式 |
| `test/…/DownloadPolicyTest.kt` | API 分级、RFC 5987 文件名、路径穿越清洗、scheme 判定 |
| `test/…/LongPressPolicyTest.kt` | 命中类型映射、`javascript:`/`blob:` 拒绝 |
| `test/…/PermissionsPolicyTest.kt` | “网站没有 capture 属性也要给拍照入口”这条关键规则 |
| `test/…/NavPolicyTest.kt` | 导航裁决回归（站点只能壳内、明文拦下、未知 scheme 拦下） |

### 1.3 修改

| 文件 | 改动 |
|---|---|
| `MainActivity.kt` | 接上全部新能力：选择器 / 相机 / 权限 / 下载 / 长按 / 体积提示 |
| `shell/WebShellChromeClient.kt` | 新增 `onShowFileChooser` 钩子 |
| `AndroidManifest.xml` | 加 `CAMERA`、两个 `uses-feature(required=false)`、`<queries>`、FileProvider |
| `res/values/strings.xml` | 新增 19 条文案；删掉已实现的占位提示 `toast_download_todo` |
| `res/values/colors.xml` | 品牌色改为社徽钴蓝 `#0756A2`，新增 `brand_accent #79C2FA` |
| `res/drawable/ic_launcher_foreground.xml` | 图标改为“三道波 + 圆点”（社徽意象） |
| `app/build.gradle.kts` | 新增 **仅测试类路径** 的 `junit:junit:4.13.2` |
| `.github/workflows/android.yml` | CI 新增“纯逻辑单测”步骤（debug 在 APK 之前；release 作为发版阻断） |
| `scripts/preflight.sh` | 新增 6 条静态规则（见第 4 节） |
| `scripts/ci-extract-errors.sh` | 报错模式补充单测失败行（FAILED / AssertionError / junit） |
| `docs/FEATURE-MATRIX.md` | 第 5/6/9/12/13/16/19/20 行标 🟡（壳已就绪，入口待区块 8），第 21~24 行标 🟡 待真机 |
| `docs/CI-WORKFLOW.md`、`README.md` | 同步单测步骤、新组件与当前状态 |

---

## 2. 关键决策（含与简报不同的地方，请重点看）

### 2.1 壳**不**实现 tus

壳只做“打开选择器 + 回传 `content://` URI”，分片上传完全交给网页里的 tus 客户端。
在壳里再写一套 tus 会与网页重复，还得自己处理断点与重试，得不偿失。

### 2.2 一个存储权限都不申请（与简报有出入，已取安全基线）

`docs/SECURITY.md` §2/§7 明确写着“不申请读写外部存储”。基于这个前提：

| 场景 | 做法 | 需要权限？ |
|---|---|---|
| 选文件（图片/文档/视频/多选） | `FileChooserParams.createIntent()` → 系统选择器（SAF） | ❌ 系统按 URI 临时授权 |
| 拍照上传 | FileProvider 临时文件 + `ACTION_IMAGE_CAPTURE` | 仅相机（见 2.3） |
| 下载（Android 10+） | `DownloadManager` 写公共“下载”目录 | ❌ |
| 下载（Android 8/9） | 降级写 App 自己的外部目录 | ❌（代价见下） |

**因此没有申请 `READ_MEDIA_IMAGES`**（简报里提到过它）：只有自建“相册九宫格选择器”才需要它，
走系统选择器时申请它属于多余权限。若你希望 App 内置相册格网，我在区块 4/8 再单独加（会是一次显式决策）。

**Android 8/9 下载的代价**：文件落在 App 外部目录
（`Android/data/com.nhyzcms.clubapp[/.debug]/files/Download/`），通知栏依旧可见、可点开/分享，
但不会出现在系统“下载”App 的列表里。要让它落公共目录就得开 `WRITE_EXTERNAL_STORAGE`（maxSdk 28）——
请确认后再改（这会在安全文档里开一个口子）。

### 2.3 相机权限为什么必须申请，以及什么时候申请

在清单里声明了 `CAMERA`（这样“拍照”入口在各厂 ROM 上才稳定出现），而**一旦声明**，
系统就要求调用方自己持有该权限，否则 `ACTION_IMAGE_CAPTURE` 会被拒。所以：

- 只在“**将要**提供拍照入口”时申请：`accept` 含 `image/…`（图片通配），或网页写了 `capture`，或设备有摄像头；
- 用户拒绝 → 本次会话不再重复弹窗，退化成“只能选已有文件”并给出提示；
- “禁止且不再询问” → 弹一个说明对话框，可直接跳系统设置页；
- 设备没有摄像头（`FEATURE_CAMERA_ANY`）→ 不申请、不给入口。

> 站点里的 `input` 用的是图片/视频的 accept 通配、**没有** `capture` 属性，
> 所以不能只看 `isCaptureEnabled()`，否则拍照入口永远不出现（已写成单测钉死）。

### 2.4 上传体积超限：先提示，由用户决定

超过 20 MB 时弹原生对话框：`仍然上传` / `换一个文件`。
选后者则向网页回传“取消选择”（`onReceiveValue(null)`），网页表现等同于用户没选文件，
**不会**出现“传到一半才 413”的体验。不硬性阻断是因为服务器上限将来可能调高。

### 2.5 长按图片 / 链接

`setOnLongClickListener` + `HitTestResult`：

- 图片：保存图片（交给 `DownloadManager`）/ 分享链接 / 复制链接 / 用浏览器打开；
- 链接：分享链接 / 复制链接 / 用浏览器打开；
- 其它命中（文本选择、输入框、电话/邮箱/地图）一律返回 `false`，交回系统默认行为。

`javascript:` / `blob:` / `data:` 一律不接管（它们不能交给浏览器或下载器）。

### 2.6 品牌色与图标：取自社徽

| 令牌 | 值 | 来源 |
|---|---|---|
| `brand` | `#0756A2` | 网站 `frontend/src/styles/cobalt.css` 的 `--brand-800`，该行注释写明是 **SEAL BLUE 社徽主色**，整层锚定 `oklch(0.455 0.141 254)` |
| `brand_accent` | `#79C2FA` | 同文件的 `--brand-300`（社徽里的浅蓝） |

图标改为**纯矢量**的“三道波 + 圆点”（社徽里的波形与那颗圆点），白 + 浅蓝双色，底色 `@color/brand`；
仓库依旧**零二进制资源**。这是**意象化简化**，不是社徽原图的精确矢量化——
如果你有社徽的 SVG/AI 原文件，给我我就按原坐标重建。

### 2.7 引入 junit（仅测试类路径）+ CI 跑单测

ADR-A05 要求 App 依赖最小化，但 `testImplementation` 不进 APK，收益远大于代价：
本仓库没有本地 Android 构建环境，**CI 是唯一能编译/验证的地方**，
有了单测，纯逻辑（文件名清洗、上限判定、导航裁决）就能在 CI 里被真正验证，而不是靠人眼读代码。

---

## 3. 实现要点（后人改代码前请先读）

1. **`ValueCallback` 必须且只能回调一次**。所有出口（成功 / 取消 / 构建失败 / 被新请求打断 / Activity 销毁）
   都收敛到 `FileChooserHost.deliver(...)`；漏掉任意一条，网页那个 input 会永久卡死。
2. **下载必须手动带 Cookie**：`DownloadManager` 不共享 WebView 的 CookieJar，
   受权限保护的文件少了它直接 403。同时带上带 `NhyzClubApp/…` 标记的 UA，网站才能认出 App。
3. **FileProvider authority 用 `${applicationId}.fileprovider`**（清单）+ `BuildConfig.APPLICATION_ID`（代码）。
   写死包名会让 debug 变体（`.debug` 后缀）的拍照在运行时炸掉。
4. **相机临时文件的生命周期**：`cacheDir/capture/`；没走相机就立刻删；
   走了相机就保留（网页还要读它上传），下次启动清掉超过 24 小时的残留。
5. **不碰 `setWebViewClient(null)`、不改 TLS 处理**（区块 1 踩过的坑与红线，已加静态规则守着）。
6. 日志只记 **host**，不记完整 URL / Cookie（延续区块 1 的脱敏习惯）。
7. **Kotlin 块注释里不能出现「斜杠 + 星号」**（本块 CI 实际挂了两次才查出来）：
   Kotlin 的块注释**支持嵌套**，KDoc 里写一个 MIME 通配示例就会开启嵌套注释，
   注释不会在 `*/` 处结束，而是把文件剩余部分全吞掉；报错却是
   `Unclosed comment` + 另一个文件的 `Unresolved reference`，极难定位。
   已加进预检（第 4 节规则 6）。
8. **预检自身的误报与丢消息**（同一个教训的另外两个面）：
   ① 代码模式规则必须只在非注释行搜（`code_grep`），否则自己的警示注释会触发规则；
   ② python 的 `sys.exit("消息")` 走 stderr，命令替换里必须 `2>&1`，否则报错只有空消息。

---

## 4. 新增的静态预检规则（错误只能犯一次，共 6 条）

`scripts/preflight.sh` 新增：

| # | 规则 | 为什么 |
|---|---|---|
| 1 | 禁止 `addJavascriptInterface` | 安全文档 §4：桥接只能走 `WebMessageListener` |
| 2 | 禁止出现 `.proceed()` | `onReceivedSslError` 绝不能放行 |
| 3 | 清单里不得出现存储/媒体类权限 | 安全基线：存储权限一个都不要 |
| 4 | 用了 `getUriForFile` 就必须同时满足：清单是 `${applicationId}.fileprovider`、代码用 `BuildConfig.APPLICATION_ID`、`res/xml/file_paths.xml` 存在 | 三者缺一，拍照只在运行时才炸，极难定位 |
| 5 | 出现 `setDestinationInExternalPublicDir` 必须伴随 `Build.VERSION.SDK_INT` 判断 | 否则 Android 8/9 会 SecurityException |
| 6 | Kotlin 块注释里不得出现「斜杠 + 星号」 | 块注释会嵌套，会把整个文件吞掉（本块真踩过） |

> 新规则一律用 `code_grep`（只搜非注释行），并且都做过**双向验证**：
> 干净仓库 → 通过；故意注入问题 → 精确报出行号。

---

## 5. 验收清单（真机，10 条）

先按 `docs/BUILD.md` 取 CI 的 artifact `app-debug-apk` 安装。

| # | 操作 | 预期 |
|---|---|---|
| 1 | 个人中心 → 更换头像 → 选相册图片 | 出现系统选择器（**多一个“相机”入口**），选中后上传成功 |
| 2 | 同上，在选择器里点「相机」 | 首次弹相机权限申请；允许后能拍照并上传 |
| 3 | 再点「相机」，在权限弹窗选“拒绝” | 界面继续可用，退化为只能选文件，有明确提示 |
| 4 | 选一张大于 20 MB 的图片 | 弹「超过上传上限」；点“换一个文件”后网页无反应（不报错），点“仍然上传”才继续 |
| 5 | 任务详情 → 上传附件（pdf/docx） | 能选到文件并上传成功 |
| 6 | 反馈页 → 多选图片/视频 | 能一次选多个并上传 |
| 7 | 点一个受权限保护的下载（审核导出/附件） | 通知栏出现下载进度，完成后能打开（Android 10+ 在系统“下载”目录） |
| 8 | 长按新闻里的图片 | 弹菜单：保存图片 / 分享链接 / 复制链接 / 用浏览器打开，均可用 |
| 9 | 长按一段正文文字 | **不弹我们的菜单**，仍是系统文本选择 |
| 10 | 全景图管理 → 导入一张原图（≈8 MB） | 能选到文件；上传正常（切后台再回来不崩） |

> 第 1~6、10 条对应的**页面入口**在区块 8（网站侧 App 模式 UI）之前，手机上可能只能从桌面版路径进到这些表单——
> 这不影响本块“能力是否接上”的验收。

---

## 6. 已知限制（本块故意不做）

- ❌ `blob:` 下载（网页用 JS 生成的文件）：会明确提示“暂不支持”，需要时在区块 6 用 JS 桥接读取；
- ❌ 下载的“仅 WiFi”开关与分段策略：区块 6；
- ❌ 上传进度的原生显示：进度在网页里（tus 客户端已有 UI），壳不加一套重复 UI；
- ⏳ 纯函数单测跑 CI，**但真机行为（相机 URI 授权、DownloadManager 落盘）只能由你验证**；
- ⏳ `docs/ARCHITECTURE.md` §4 的组件表尚未同步本块新增的 7 个类
  （新增清单见本报告 §1.1 与 `README.md` §3；下次动那份文档时一并补，
  避免为改 4 行而整篇重写带来的抄写风险）。

---

## 7. 待你确认

1. **品牌色**：`#0756A2`（= 网站 `--brand-800`，注释标注为社徽主色）是否就是你要的口径？
   若你有社徽的 SVG/AI 原文件，我可以按原坐标精确重建图标（现在是意象化简化版）；
2. **Android 8/9 的下载落 App 目录**是否可接受？若要落公共“下载”目录，需开 `WRITE_EXTERNAL_STORAGE`（与安全基线冲突）；
3. 是否需要“分享图片**文件本体**”（现在是分享图片链接）？需要的话要先把图片落到缓存再走 FileProvider（可加）；
4. 相机权限的申请时机是“打开选择器时”（现在）还是“用户真的点了相机之后”（后者体验更细，但目前无法在系统选择器里拦截这个选择）。

---

## 8. 回滚

- 本块只改 Android 壳仓库，**对网站与服务器零影响**；
- 合并前分支即 `feat/02-capabilities`；合并后如需回滚，`git revert` 该 merge commit 即可
  （不涉及数据、不涉及线上服务）。

---

## 附：本块的验证方式（实际执行的）

| 手段 | 结果 |
|---|---|
| `bash scripts/preflight.sh`（服务器 `/tmp` 只读 clone） | ✅ 全部通过（最新提交 9475efa） |
| 新规则反向验证（故意注入“斜杠+星号”到 KDoc） | ✅ 精确报出第 5 行 |
| 18 个 `.kt` 文件括号配平（带状态机的脚本，防长文件被截断） | ✅ 通过 |
| `gradle :app:testDebugUnitTest` + `assembleDebug` | 交 CI（本机与服务器都没有 Android SDK，且服务器只有 2 核 2G，不在上面跑 Gradle）；失败时关键报错会自动贴成本 PR 评论 |

> 本块 CI 共红 3 轮：① 预检自身的 `.proceed()` 误报；② KDoc 里的斜杠加星号（块注释嵌套）；
> ③ 预检报错详情走 stderr 丢失。三个都已修好，并已固化成规则/备注，不会再犯。
