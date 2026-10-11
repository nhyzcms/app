# 更新策略（UPDATE-STRATEGY）

> 目标：**大部分更新对用户无感（重新加载即可），只有少部分重大更新才需要重装 APK。**

---

## 1. 三级模型

| 级别 | 名称 | 内容举例 | 生效方式 | 用户成本 | 发布方 |
|---|---|---|---|---|---|
| **L0** | 内容/业务更新（热） | 页面、样式、接口、活动中新增字段、修 bug、新增页 | 下次加载/回到前台时自动生效 | 0 | 网站仓库现有发版流程 |
| **L1** | 配置/开关（温） | 起始路由、功能开关、是否提示更新、强制更新、紧急停用某功能 | 启动时拉 `app-manifest.json` | 0 | 改服务器上的一个 JSON（或发网站版） |
| **L2** | 壳能力（冷） | 新权限、新原生接口、安全修复、WebView 策略变更、包名/签名相关 | App 内提示 → 下载 APK → 覆盖安装 | 一次点击 + 等待下载 | 本仓库发 Release |

### 为什么 L0 是”自动热更新”

App 界面本身就是远程页面（`https://8.153.145.175/`）。网站一发版，App 下次加载就是新代码。
再配合 nginx 已有的缓存策略：

- 带内容哈希的 JS/CSS：`max-age=31536000, immutable`（换版即换文件名 → 立即生效）；
- 无哈希的 `/static/` 与 `/media/`：7 天；
- **待补（网站侧，区块 8）**：`/` 与 SPA 入口 HTML 应显式声明 `Cache-Control: no-cache`（允许缓存但每次协商）。
  现状是没有显式头，WebView 可能用启发式缓存把旧入口 HTML 留一段时间 → 表现就是"网站更新了但 App 里还是旧页面"。

---

## 2. 决策表：这次改动要不要发 APK？

| 改动 | 级别 | 说明 |
|---|---|---|
| 改文案/颜色/布局、加页面、改接口字段 | L0 | 直接发网站 |
| 新增 Django 接口 / DRF 路由 | L0 | 同上 |
| 修前端 bug | L0 | 同上 |
| 换服务器 IP / 域名 | **L1 + L2** | manifest 可改，但 `ALLOWED_HOSTS` 在 APK 里 → 必须发新版（所以宝址域名要尽量稳） |
| 新增一个完全由网页实现的功能（包含表单、上传） | L0（前提：区块 2 已完成） | 壳只需支持通用上传/下载 |
| 功能需要**新的 Android 权限**（如定位、蓝牙） | **L2** | 权限必须声明在 manifest |
| 需要新的原生能力（扫码、录视频、后台定位） | **L2** | 需写 Kotlin + 桥接 |
| 发现壳的安全漏洞（如导航白名单绕过） | **L2（紧急）** | 同时用 L1 的 `blockedVersions` 先把老版本挡掉 |
| 服务器证书换发（90 天一次） | **无需任何动作** | 因为不做证书固定；换证后 App 自动继续可用 |
| 改登录/会话机制 | L0（仅 Cookie 语义不变） | 仍用 WebView CookieJar，不自己存 token |

---

## 3. L1：远程配置清单

- **固定地址**：`https://8.153.145.175/app-update/manifest.json`（nginx 静态文件，不经 Django，不占 gunicorn）
- **拉取时机**：① 冷启动后 3 秒（不阻塞首屏）；② 应用回到前台且距上次拉取 > 30 分钟；③ 用户手动点"检查更新"。
- **失败处理**：静默忽略（用上次缓存或默认值），**绝不能因为配置拉不到导致 App 不可用**。
- **壳侧实现（区块 3 已落地）**：`shell/update/ManifestFetcher`（no-cache + 主机白名单 + 256 KB 上限）→
  `UpdateManifestCodec`（字段级容错）→ `UpdatePolicy`（安全判定）→ `UpdateStore`
  （把 `startRoute`/`flags` **落盘、下次冷启动生效**，避免首屏等网络）。
  手动入口在原生错误面板的「检查更新」按钮上（正常使用靠自动检查，不打扰用户）。
- **字段契约**：[APP-MANIFEST.md](APP-MANIFEST.md)
- 可实现的典型动作：
  - `startRoute`：改起始页（如从 `/#/m` 改到 `/#/app`）—— 不升级也能上线新首页；
  - `flags.live2d=false`：紧急关掉吃带宽的 Live2D；
  - `minShellVersionCode`：低于此版本弹"必须更新"（不可跳过）；
  - `blockedVersions[].action=block`：发现严重问题时直接停用某些壳版本（因为旧版无法降级/删除，只能阻断）；
  - `apk.url` / `apk.sha256` / `apk.size`：新包分发（自托管优先，GitHub 作备用源）。

---

## 4. L2：APK 自升级流程

```
检查 manifest
   └─ latestVersionCode > 本地 VERSION_CODE
         ├─ 弹窗（普通更新：可稍后；强制：只给"立即更新"）
         ├─ 下载：自托管 URL 优先；失败 → GitHub Release 备用 URL
         │    （WiFi 优先；移动网络大包先询问）
         ├─ 校验：① sha256 与 manifest 一致 ② 下载包的签名指纹与本机已装应用一致
         │    ✗ 任一不过 → 删包 + 告警（绝不交给安装器）
         ├─ 启动安装器（FileProvider 暴露单一文件，授权 FLAG_GRANT_READ_URI_PERMISSION）
         │    首次需用户授权"允许此来源安装未知应用"，UI 要提前告知
         └─ 安装后：签名相同 → 数据与登录态保留
```

### 必须知道的两条硬约束

1. **Android 不允许降级安装**（新版 versionCode 一旦装过，就装不回旧的）。
   → 所以"回滚"不是发回旧代码，而是**发一个 versionCode 更高的修复版**。
2. **签名必须永久一致**。keystore 丢了，用户就必须卸载重装（会丢本地 Cookie，需重新登录）。

### 壳侧实现（区块 3 已落地）

| 环节 | 实现 | 关键取舍 |
|---|---|---|
| 落盘位置 | `cacheDir/update/latest.apk.part` → 校验后改名 `latest.apk` | 不用公共「下载」目录：更新包是一次性产物，也不该要存储权限；系统清缓存最多是重下 |
| 断点续传 | HTTP `Range: bytes=N-`；服务端返回 200（不支持 Range）则从头写 | `.part` 长度 + 上次记录的 sha256 都对得上才续传，否则删掉重下 |
| 摘要 | 下载线程**边下边算** SHA-256（不额外读一遍文件） | 校验不过 → 立刻删包，绝不交给安装器 |
| 体积 | 声明值与实际长度比对；≥ 64 KB 且 ≤ 200 MB | 防"清单被投毒指向一个小文件/超大文件" |
| 签名 | `ApkSignatures` 取本机证书与待装包证书，`SignatureCheck` 比对 | **取不到签名 = 拒绝安装**（宁可重试，不放过来源不明的包） |
| 安装 | `FileProvider` 单文件 URI + `FLAG_GRANT_READ_URI_PERMISSION` + 系统安装器 | Android 8+ 需用户先允许"安装未知应用"，壳只引导不绕过 |
| 下载源 | 清单 `apk.url` 优先，其后 `updateSources`；逐个尝试，全部过主机白名单 | 自托管优先，GitHub Release 作备用 |

### 应用内更新的安装体验取舍

Android 没有"静默自升级"（除非系统应用/设备管理员）。用户至少要确认两次（授权来源 + 安装）。
为了减少阻塞：
- 更新不影响使用（不强制时）；
- 下载在后台（`DownloadManager`，通知栏可看进度）；
- 提供"下载好后自动拉起安装器"开关。

---

## 5. 双向兼容契约

| 方向 | 机制 | 在哪里维护 |
|---|---|---|
| 壳 → 网站：告诉网站"我是 App，版本 X" | UA 附加 `NhyzClubApp/<versionName> (Android; <code>)` | 壳侧 `AppConfig.UA_MARKER` |
| 壳 → 网站：壳太旧时网站拒绝服务 | 网站读 UA，与自己的"最低壳版本"常量比较 → 展示升级引导页 | 网站仓库 |
| 网站 → 壳：告知最新版、开关、强制策略 | `app-manifest.json` | 服务器静态文件 |
| 网站 → 壳：提醒需要新原生能力 | manifest `minShellVersionCode` + `notes` | 同上 |

**原则**：壳要能容忍一个"功能对它不存在"的网站（不因为网站删了个接口就崩）；
网站也要能容忍旧壳（不因为 UA 里没有标记就白屏）。

---

## 6. 发布 SOP

### 6.1 发网站（L0，日常）
1. 正常走网站仓库流程 → 合并 → 现有更新器应用；
2. 验收：手机上打开 App（或刷新），确认新页面已生效；
3. 无需任何 APK 动作。

### 6.2 改配置（L1）
1. 改服务器上的 `app-update/manifest.json`（备份旧文件）；
2. `curl -I` 确认 `Content-Type: application/json` 且可访问；
3. App 冷启验证开关生效。

### 6.3 发新版 APK（L2）
- [ ] `version.properties` bump（`versionCode` 必须大于上一版）；
- [ ] 更新 `docs/chunks/CHUNK-0N-REPORT.md`（本版改了什么、哪些需要新的权限）；
- [ ] PR 合并到 `main`；
- [ ] 打 tag `vX.Y.Z` → CI 产出签名 APK + sha256；
- [ ] 用 `scripts/publish-apk.sh` 把包与清单一起更新到 `/opt/club/app-update/`
      （它负责计算 sha256、复制为 `latest.apk`、改写 `manifest.json` 的 `latest` 字段）：
      ```bash
      bash scripts/publish-apk.sh --apk /tmp/app-release.apk \
        --version-code 2 --version-name 0.2.0 --notes "本版更新说明"
      ```
- [ ] 用旧版 App 实测升级路径（安装后能覆盖、登录态保留）。

### 6.4 紧急处置

| 场景 | 处置 |
|---|---|
| 网站上线出 bug | 网站侧回滚（App 无需动作） |
| 壳有安全漏洞 | ① manifest `blockedVersions` 阻断旧版；② 尽快发新版 |
| 自托管下载不可用 | manifest 将 `apk.url` 换回 GitHub Release 链接 |
| 服务器证书过期 | 续期即可；App 会显示"安全连接失败"，续期后恢复 |
