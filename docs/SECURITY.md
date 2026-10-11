# 安全设计（SECURITY）

> 本项目在终端上保存着用户的登录会话，又需要下载并安装 APK——两者都是高风险面。

---

## 1. 资产与威胁

| 资产 | 威胁 | 已实施/计划中的控制 | 残留风险 |
|---|---|---|---|
| 会话 Cookie（等于是账号） | 中间人窃取；任意页面读取 | 全站 HTTPS + 禁止明文；TLS 错误直接失败；不用 `addJavascriptInterface`；仅白名单 Host 在壳内打开 | 设备已被 root / 恶意输入法 |
| 密码与表单 | 同源页面外泄 | 仅加载本站 Origin；第三方 Cookie 关闭；无第三方 JS | 网站自身的 XSS（属网站仓库） |
| 上传文件/图片 | 被其它 App 读取 | 文件选择用系统 SAF（不申请宽存储权限）；拍照输出走 FileProvider 缓存目录 | 用户主动保存到公共下载目录时会公开 |
| 下载文件 | 落到公共目录被其它 App 读取 | 落盘目录由 `DownloadPolicy` 统一判定：API 29+ 免权限写公共「下载」目录；
API 26~28 需 `WRITE_EXTERNAL_STORAGE`（**仅限 maxSdkVersion=28**，管理员 2026-10-11 确认）；被拒则退回 App 目录 | 权限在 Android 8/9 上被授予期间的通用写入能力 |
| 更新包（APK） | 伪造包 → 任意代码执行 | sha256 校验 + **签名指纹必须与本机已装版本一致**；下载源限定本站与 GitHub Releases | 用户自行从其它渠道装包 |
| 本地数据 | 备份导出、别沟通读取 | `allowBackup=false` + `dataExtractionRules` 全排除；App 私有目录 | root 设备 |
| 调试接口 | 远程调试暴露 | WebView 远程调试仅 `BuildConfig.DEBUG` 开启 | debug 包泄露 |
| 运维密钥（keystore、token） | 进入仓库 → 伪造发布 | `.gitignore` 屏蔽 `*.jks/*.keystore/keystore.properties`；keystore 只存 GitHub Secrets（base64） | Secrets 权限被拉走 |

---

## 2. WebView 安全基线（已在区块 1 实施）

| 配置 | 值 | 原因 |
|---|---|---|
| `mixedContentMode` | `MIXED_CONTENT_NEVER_ALLOW` | 禁止 HTTPS 页面加载 HTTP 子资源 |
| `cleartextTrafficPermitted` | `false`（network-security-config） | 系统层禁止任何明文 HTTP |
| 信任锢 | 仅 `system`，**不含 `user`** | 不信任用户/应用安装的根证书（防抓包式中间人；代价：不再被这些工具"正常调试"） |
| `allowFileAccess` | `false` | 禁止 `file://` 读本地文件 |
| `allowContentAccess` | `false` | 禁止 `content://` 任意读取 |
| `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` | `false` | 防 file 页面跨域读 |
| 第三方 Cookie | `false` | 会话只需第一方 |
| JavaScript | `true`（必需，SPA 靠它） | 配合上面的 origin 白名单与无 JS 桥接 |
| 数据库/本地存储 | `true` | SPA 需要 localStorage（如 `cobalt:theme`） |
| 安全浏览 | `setSafeBrowsingEnabled(true)`（API 26+） | 拦截恶意站点 |
| 远程调试 | 仅 debug | 避免 release 被 adb 接入 |
| 文件选择 | 仅系统 SAF / 相机 | 不申请读取媒体、读取外部存储类权限 |
| 下载落盘 | API 29+ 写公共「下载」目录（免权限）；API 26~28 用 `WRITE_EXTERNAL_STORAGE`（**maxSdkVersion=28**）；被拒则退 App 目录 | 让 Android 8/9 用户也能在系统「下载」里找到文件，代价被限制在两个旧版本上 |

### 2.1 权限清单（当前全部声明的权限）

| 权限 | 用于 | 限制 |
|---|---|---|
| `INTERNET` | 加载站点与下载 | — |
| `ACCESS_NETWORK_STATE` | 网络可用性判断/错误提示 | — |
| `CAMERA` | 拍照上传 | 仅在“将要提供拍照入口”时申请；拒了退化为只能选文件 |
| `WRITE_EXTERNAL_STORAGE` | Android 8/9 把下载写进公共「下载」目录 | **`maxSdkVersion=28`**，且仅在下载时申请；拒了退 App 目录 |
| `REQUEST_INSTALL_PACKAGES` | 安装自己下载的更新包（区块 3） | 不静默安装：仍需用户在系统里允许「安装未知应用」；包必须通过 sha256 + 签名校验（见 §5） |

> **为什么不做证书固定（Certificate Pinning）**：
> 站点证书是 ZeroSSL DV，SAN 就是 IP `8.153.145.175`，**90 天必须换一次**。
> 固定叶证书指纹 → 换证当天所有 App 报错；固定中间 CA → ZeroSSL 轮换中间证书时也会挂。
> 因此选择"HTTPS 强制 + 系统信任链"，并在 `onReceivedSslError` 里**显式失败**（绝不调 `handler.proceed()`），
> 把安全风险交给标准的 PKI，把可用性风险抹掉。

---

## 3. 导航与外部链接

```
仅以下两种情况在壳内打开：
  ① scheme ∈ {https} ② host ∈ {8.153.145.175}
其它：
  http/https 外部域名 → 交给系统浏览器（壳内不加载，不注入任何东西）
  tel / mailto / sms / geo → 尝试系统处理（失败就忽略，不抛异常）
  intent: / market: / file: / content: / javascript: / 其它未知 scheme → 一律拦截
```

**为什么不把外部网页放进 WebView**：一旦在同一个 WebView 里加载第三方内容，
第三方页面就可能利用 WebView 自身漏洞、也可能诱导用户输入密码（钓鱼页面无法与地址栏区分）。
交给系统浏览器，地址栏会把真相告诉用户。

> 注（区块 3）：清单开关 `externalLinkInBrowser=false` 可让外链改在壳内打开，
> 但上述明文拦截与未知 scheme 拦截不受该开关影响（`NavPolicy` 在开关之前就已裁决）。

---

## 4. JS 桥接规则（尚未启用，后续如需启用须遵守）

✅ 允许：`WebViewCompat.addWebMessageListener`（可指定只接受哪些 origin 的消息）+ 白名单方法（如 `getVersion()`、`openExternal(url)`、`share(text)`）
❌ 禁止：`addJavascriptInterface` 暴露文件系统/数据库/任意 HTTP 接口
❌ 禁止：桥接方法里执行网页传来的任意字符串（不得 `eval`、不得拼接 shell、不得直接当文件路径用）

> `scripts/preflight.sh` 已加规则：代码里出现 `addJavascriptInterface` 直接挂预检。

---

## 5. 密钥与签名

| 项 | 存放位置 | 入库？ |
|---|---|---|
| 发布 keystore（`.jks`） | 用户本地 + GitHub Secrets（base64） | ❌ |
| keystore 口令 / alias / 口令 | GitHub Secrets | ❌ |
| 站点密钥（`UPDATE_GITHUB_TOKEN`、DB 密码等） | 服务器 `.env` | ❌ 与 App 无关 |

**签名 = 更新路径的信任根**。两件事必须做到：
1. keystore 丢失 → 用户必须卸载重装（丢登录态）；
2. 更新前校验下载包签名与已装应用一致，**绝不安装签名不同的包**。

---

## 6. 需要服务器/网站配合的安全项

| 项 | 现状 | 建议 |
|---|---|---|
| HSTS | ✅ 已有 `max-age=31536000` | 保持 |
| 明文 HTTP | 已有 301 → HTTPS | 保持 |
| Cookie 属性 | 需网站确认 `SESSION_COOKIE_SECURE/HTTPONLY/SAMESITE` | 建议 `Secure=True, HttpOnly=True, SameSite=Lax` |
| CSP | 无 | 可评估（注意不要打断 Marzipano/live2d） |
| `X-Frame-Options` | `SAMEORIGIN` | 保持 |
| `app-update/` 目录 | ✅ 已部署（2026-10-11：`root:nginx 0750`，文件 0640，只读） | 只读、无目录列表、无执行权限、只放 JSON 与 APK |
| nginx `server_tokens` | `off` | 保持 |

---

## 7. 改代码自查清单（每次提交前过一遍）

- [ ] 有没有新增在壳内打开的非本站域名？（必须交浏览器）
- [ ] 有没有在 `onReceivedSslError` 里调 `handler.proceed()`？（绝对不能）
- [ ] 有没有新增宽存储/通讯录/定位权限？能不用就不用
- [ ] 若动了下载落盘：`WRITE_EXTERNAL_STORAGE` 是否仍带 `maxSdkVersion="28"`，且仅在下载时申请？（preflight 会拦）
- [ ] 有没有新增 JS 桥接接口？是否限定 origin + 白名单？
- [ ] 日志里有没有打印 URL 参数、Cookie、token、手机号？
- [ ] 下载/安装路径是否校验了 `https` + `sha256` + 签名？
- [ ] 上传/分享拿着会话的请求（下载、分享图片）是否都带了会话 Cookie？（不带会拿到 403 或登录页 HTML）
- [ ] FileProvider 可共享目录有没有变多？（`res/xml/file_paths.xml` 每多一行都是一个对外可见路径）
- [ ] 是否误提交了 `*.jks` / `local.properties` / `.env`？
- [ ] Release 构建是否仍保持调试关闭（`BuildConfig.DEBUG` 分支）？

---

## 8. 已知不做的事（与理由）

| 不做 | 理由 |
|---|---|
| 证书固定 | 90 天换证必砖（见 §2） |
| 应用锁/生物识别 | 校园内部应用，收益低、误触成本高；如需可作开关后加 |
| 截图防护（`FLAG_SECURE`） | 会阻止用户正常截图分享，体验损失大于收益 |
| 自建 Token 存储 | 会话已在 WebView Cookie 中，自建反而多一个泄露面 |
| 混淆到极致（R8 full mode + 反射） | 代码不是敏感资产，崩溃排查成本反而变高 |
