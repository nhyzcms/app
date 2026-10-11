# 区块 3 交付报告 — 三级更新机制（L0 热 / L1 清单 / L2 自升级）

> 日期：2026-10-11 · 分支：`feat/03-update-mechanism` · 依据：`docs/ROADMAP.md` 区块 3
> 状态：**代码与单测完成，静态预检通过；服务器配置与真机验收待办**（第 5、6 节是给管理员的）
> 上游文档：[UPDATE-STRATEGY.md](../UPDATE-STRATEGY.md)、[APP-MANIFEST.md](../APP-MANIFEST.md)

---

## 1. 交付物清单

### 1.1 新增代码（`app/src/main/java/com/nhyzcms/clubapp/shell/update/`）

| 文件 | 职责 | 可否单测 |
|---|---|---|
| `MiniJson.kt` | 零依赖 JSON 解析（对象/数组/字符串/数字/字面量；长度与深度上限） | ✅ |
| `UpdateManifest.kt` | 清单数据模型 + `UpdateManifestCodec`（字段级容错解析） | ✅ |
| `UpdatePolicy.kt` | **安全边界**：起始路由校验、下载源主机白名单、sha256 格式、体积合理性、版本决策、开关合并 | ✅ |
| `UpdateStore.kt` | 清单缓存 / 开关 / 公告已读 / 续传元数据（SharedPreferences） | ❌（Android 依赖） |
| `ManifestFetcher.kt` | 拉清单：8 s 超时、`Cache-Control: no-cache`、主机白名单、256 KB 上限、失败静默 | ❌ |
| `ApkDownloader.kt` | 下更新包：`cacheDir/update/`、`.part` + HTTP Range 续传、边下边算 sha256、体积与摘要校验、多源回退 | ❌ |
| `ApkDigest.kt` | SHA-256 计算/十六进制/常数时间比对 | ✅ |
| `ApkSignatures.kt` | 取本机与 APK 文件的签名证书（API 26/27 与 28+ 两条通道） | ❌ |
| `SignatureCheck.kt` | 证书集合一致性判定 | ✅ |
| `UpdateInstaller.kt` | FileProvider 单文件 URI + 系统安装器 + 「安装未知应用」引导 | ❌ |
| `UpdateController.kt` | 串联：节流检查 → 提示 UI → 下载进度 → 校验 → 安装；公告弹窗 | ❌ |

### 1.2 改动（既有文件）

- `MainActivity.kt`：接入 `UpdateController`（冷启动 3 s 后检查、回前台 30 min 节流、`dispose`）、
  `startRoute` 作为起始页、`forceRefreshEntry` 时清 HTTP 缓存、错误面板新增「检查更新」按钮；
- `WebShellClient.kt`：新增 `externalLinkInBrowser` 开关（清单 `flags` 可改外链行为，明文拦截不受影响）；
- `AppConfig.kt`：新增 `UPDATE_ALLOWED_HOSTS`（清单与 APK 主机白名单，唯一真源）；
- `AndroidManifest.xml`：新增 `REQUEST_INSTALL_PACKAGES`；`file_paths.xml`：新增 `cacheDir/update/`；
- `res/values/strings.xml`、`res/layout/activity_main.xml`：更新相关文案与按钮；
- `scripts/preflight.sh`：新增区块 3 的 5 条规则（见第 4 节）；
- 文档：README / ROADMAP / FEATURE-MATRIX / UPDATE-STRATEGY / APP-MANIFEST / SECURITY 同步。

### 1.3 新增运维资产

- `scripts/nginx-app-update.conf`：`/app-update/` 的 location 片段（no-cache、只读、正确 MIME）；
- `scripts/publish-apk.sh`：把 APK 发到自托管目录并改写清单（算 sha256 / 体积 / 更新 `latest`）；
- `deploy/app-update/manifest.json`：初始清单模板（`latest: null`，即"不推更新"）；
- `deploy/app-update/README.md`：部署、发版、L1 改配置、常见问题。

---

## 2. 关键设计决策

### 2.1 自己写 JSON 解析（`MiniJson`），不引入依赖、也不用 `org.json`

| 方案 | 问题 |
|---|---|
| `org.json`（Android 自带） | JVM 单元测试里是空壳（`Method not mocked`），等于把"解析正确性"排除在 CI 之外 |
| 引入 JSON 库 | 与 ADR-A05「运行期依赖最小化」冲突，且只为读一份自己维护的小文件 |
| **自写 ~200 行解析器** | 零依赖、可 100% 单测、可加长度/深度上限（防投毒清单） |

### 2.2 清单"能落盘、下次启动生效"，而不是拉到就跳页

`startRoute` / `flags` 存进 SharedPreferences，**冷启动先用缓存值渲染首屏**，后台再拉新清单。
代价是配置变更晚一次启动生效；收益是首屏永远不会等网络（服务器只有 3 M 带宽）。
`UpdateStore` 的类注释里写清了这条取舍。

### 2.3 更新包下载自己实现，不用 `DownloadManager`

`DownloadManager` 做不到三件事：① 边下边算 sha256（只给完成后的 URI）；② 落到 App 私有目录；
③ 精确控制 `.part` 偏移做续传（验收第 ③ 条）。所以用 `HttpURLConnection` + `RandomAccessFile`。

### 2.4 校验链是"体积 → 最小体积 → sha256 → 签名"，任一步不过就删包

sha256 只能证明"和清单里写的一致"，而清单本身是服务器上的静态文件；
**签名比对**才能证明"这个包出自我们自己"。签名取不到（`ApkSignatures` 返回空）一律判为不一致 → 拒绝安装。

### 2.5 所有会"下载 + 安装"的地址都必须过白名单

`UpdatePolicy.isAllowedHttpsUrl` 要求：`https` + 主机 ∈ `AppConfig.UPDATE_ALLOWED_HOSTS`
（`8.153.145.175` / `github.com` / `objects.githubusercontent.com`）+ 无 userinfo。
起始路由同样只接受站内 `/#/...` 且长度/字符受限——否则等于让远端改壳的入口。

---

## 3. CI 会不会跳过这些逻辑

不会。新增 5 个测试类、30 个用例，全部是纯 JVM 逻辑（不碰 Android API）：

| 测试类 | 覆盖 |
|---|---|
| `MiniJsonTest`（9） | 正常解析、转义与 `\uXXXX`、坏输入一律 null、深度/长度上限 |
| `UpdateManifestTest`（9） | 完整清单、缺字段默认、类型错误丢弃、非正 versionCode、sha256 归一化、更高 schema 仍可解析 |
| `UpdatePolicyTest`（20） | 路由校验（10 种非法写法）、sha256 格式、体积合理性、URL 白名单（含 userinfo/仿冒域名）、下载源去重排序、五种决策分支、开关合并、公告去重 |
| `ApkDigestTest`（6） | `SHA-256("abc")` 标准向量、十六进制补零、文件与字符串一致、缺文件返回 null |
| `SignatureCheck`（2，含在上一类） | 集合相等（顺序无关）、空集合拒绝 |

> 无法本地编译：本机没有 JDK/Android SDK，编译与单测由 CI 执行（`gradle :app:testDebugUnitTest`）。
> 提交前在本机跑了 `bash scripts/preflight.sh`（通过）与一个自写的括号/字符串/注释配平检查（36 个 .kt 通过）。

---

## 4. 新增的预检规则（`scripts/preflight.sh`）

| # | 规则 | 为什么 |
|---|---|---|
| 1 | 权限声明必须**存在** `REQUEST_INSTALL_PACKAGES`；禁止 `REQUEST_DELETE_PACKAGES` | 少了前者安装 Intent 会被系统直接拒绝；后者壳永远不需要 |
| 2 | 更新相关代码必须出现 `getUriForFile` | 安装只能通过 FileProvider 交 content URI，不能退化成 file URI |
| 3 | 更新相关代码必须出现 `FLAG_GRANT_READ_URI_PERMISSION` | 缺了它系统安装器读不到那个 URI（真机才炸） |
| 4 | 更新相关代码必须出现 `ApkDigest.matches(` | 防止有人"顺手"把未校验的文件交给安装器 |
| 5 | 更新相关代码不得出现 `DIRECTORY_DOWNLOADS`；`AppConfig` 必须有 `UPDATE_ALLOWED_HOSTS` | 更新包不进公共下载目录；白名单只有一处真源 |

---

## 5. 需要你在服务器上做的事（**等高危操作确认后执行**）

> 这四步是区块 3 的"最后一公里"。以下命令我**尚未执行**，请你确认后我再动手（或你自己执行）。

### 5.1 加 nginx location（只读静态目录）

```bash
# ① 备份（记住这个文件名，回滚要用）
sudo cp -a /etc/nginx/conf.d/club.conf /etc/nginx/conf.d/club.conf.bak.app-update

# ② 把 scripts/nginx-app-update.conf 的内容粘贴进 club.conf 的 server { } 块内
#    片段要点：alias /opt/club/app-update/; autoindex off; Cache-Control "no-cache"; 只允许 GET/HEAD

# ③ 语法检查 + 平滑重载
sudo nginx -t && sudo nginx -s reload
```

### 5.2 建目录、放初始清单

```bash
sudo install -d -m 0750 -o root -g www-data /opt/club/app-update
sudo install -m 0640 -o root -g www-data \
     /opt/club/deploy/app-update/manifest.json \
     /opt/club/app-update/manifest.json
```

> `/opt/club/deploy/…` 需要先把本 PR 合并、更新器应用后才能就位；
> 也可以直接把仓库里的 `deploy/app-update/manifest.json` 内容拷过去。

### 5.3 验证

```bash
curl -sSI https://8.153.145.175/app-update/manifest.json   # 期望 200 + application/json
curl -sS  https://8.153.145.175/app-update/manifest.json   # 期望 JSON 内容（latest: null）
curl -sSI https://8.153.145.175/app-update/latest.apk      # 还没发包时期望 404
```

### 5.4 可选：网站侧的一处小改（属于区块 8）

SPA 入口 HTML 目前没有显式 `Cache-Control`，WebView 可能启发式缓存旧入口 → 表现为"网站更新了但 App 还是旧页面"。
建议在 nginx 给 `location = /` 与 `location = /index.html` 加 `add_header Cache-Control "no-cache" always;`。
这一条**不属于本块**，记在 `docs/UPDATE-STRATEGY.md` §1 的待办里。

---

## 6. 真机验收清单（6 条）

### 6.0 怎么造一个"能装上去"的更新包（无正式 keystore 时）

**关键前提：签名必须一致。** debug 包的签名来自构建机器的 `~/.android/debug.keystore`，
所以**不要拿两台不同机器（或 CI 与本机）的 debug 包互相升级**——那会被"签名不一致"正确地拒绝。

```bash
# 在同一台机器上：
VERSION_CODE=1 VERSION_NAME=0.1.0 gradle :app:assembleDebug     # 先装这个
VERSION_CODE=2 VERSION_NAME=0.2.0 gradle :app:assembleDebug     # 再把它当作"新版本"发布
```

把第二个包发到自托管目录（在能写 `/opt/club/app-update` 的机器上）：

```bash
bash scripts/publish-apk.sh --apk app/build/outputs/apk/debug/app-debug.apk \
  --version-code 2 --version-name 0.2.0 --notes "验收用：新增更新机制"
```

### 6.1 清单

| # | 操作 | 期望 |
|---|---|---|
| 1 | 装上 versionCode=1 的包，杀进程重开 | 启动正常；约 3 秒后无任何打扰（`latest: null` 时不提示） |
| 2 | 放好上面的 latest.apk + manifest，杀进程重开 | 弹出「发现新版本 0.2.0」；若 `mandatory: true` 则没有"稍后" |
| 3 | 点「立即更新」 | 出现下载进度；下载完成后系统安装器弹出，安装后**登录态保留**（同一签名覆盖安装） |
| 4 | 下载到一半 `adb shell am force-stop com.nhyzcms.clubapp.debug` 再重开 | 进度从断点继续（不是从 0 开始）；最终仍能安装成功 |
| 5 | 把 manifest 里的 `sha256` 改错一位（或手动改 APK 一个字节），再走一遍 | 提示「更新包校验未通过…已丢弃」，**不弹安装器** |
| 6 | 用不同签名的包（例如另一台机器构建的 debug 包）当更新包 | 提示「签名与本机不一致，已拒绝安装」，**不弹安装器** |

### 6.2 L1 开关（不改 APK 就能生效）

- `startRoute` 改成 `/#/news` → 杀掉进程重开，起始页变了；
- `flags.autoUpdateCheck=false` → 不再自动检查更新（错误面板的「检查更新」按钮仍可用）；
- `notice` 填一条公告 → 只弹一次（同 `id` 不再弹）；
- `blockedVersions` 里放本机 versionCode + `action: "block"` → 启动后弹"需要更新或改用浏览器"。

---

## 7. 已知限制 / 本块未做

| 项 | 说明 |
|---|---|
| 不在后台静默下载 | 下载只在用户点「立即更新」后进行；不做前台 Service（省电、省复杂度），中途退出应用可续传 |
| 不做按用户灰度 | 契约 v1 就是按 `versionCode` 控制（本项目不采集任何设备标识） |
| `flags.live2d` / `panorama` | 壳只负责缓存与读取；真正生效要网站侧消费（区块 8），本块不注入页面 |
| 公告的 `url` 未做点击跳转 | v1 只展示文本，避免在对话框里引入 WebView/Intent 分支 |
| release 包的签名比对 | 只有区块 7 生成正式 keystore 后才能做"跨版本真实升级"的端到端验收 |

---

## 8. 待确认项

1. 服务器 nginx location 与目录（第 5 节）——**等你确认后执行**；
2. `version.properties` 是否在区块 3 就 bump 到 2（当前策略：**不动**，区块 7 发首个 Release 时再 bump）；
3. 是否需要把「检查更新」也放到长按菜单/顶栏（当前只在错误面板，避免在没有 ActionBar 的主题里加菜单）。

---

## 9. 回滚

- 代码：`git revert` 合并提交即可（本块不涉及数据库、不涉及线上配置）；
- 服务器：把 `/etc/nginx/conf.d/club.conf.bak.app-update` 复回原位 → `nginx -t && nginx -s reload`；
  `/opt/club/app-update/` 目录可以留着（不生效也无害）；
- 线上开关：把 manifest 的 `latest` 置回 `null`，即刻停止推更新（`Cache-Control: no-cache`）。
