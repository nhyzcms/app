# 远程配置清单契约（app-manifest.json）

> 这是 **L1 层（温更新）** 的唯一载体：不重装 APK 就能改起始页、开关功能、公告、强制/灰度更新。

---

## 1. 位置与部署

- **URL（固定，写死在壳里）**：`https://8.153.145.175/app-update/manifest.json`
- **实现状态（区块 3）**：壳侧已按本文件实现（解析见 `shell/update/UpdateManifest.kt`，
  安全判定见 `shell/update/UpdatePolicy.kt`）。**服务器侧已于 2026-10-11 部署完成**：
  nginx 片段在 `scripts/nginx-app-update.conf`，目录与步骤在 [`../deploy/app-update/README.md`](../deploy/app-update/README.md)。
- **为什么不放 `/media/` 或 `/app/`**：
  - `/media/` 是用户上传目录（7 天缓存 + 语义不清）；
  - `/app/...` 会被 SPA 兜底路由拦住（实测返回 index.html 200，容易认错成功）；
  - `/app-update/` 走 nginx 静态 `location`，**不经 Django / gunicorn**，服务器负载为 0，且目录与发布包解压目录分离（不会被更新器覆盖）。
- **部署形态**：
  ```nginx
  # /etc/nginx/conf.d/club.conf 的 HTTPS server { } 内
  location /app-update/ {
      alias /opt/club/app-update/;
      autoindex off;
      add_header Cache-Control "no-cache" always;   # 每次协商，拿到即时配置
      add_header Strict-Transport-Security "max-age=31536000" always;
      add_header Alt-Svc 'h3=":443"; ma=86400' always;
      types { application/json json; application/vnd.android.package-archive apk; }
      default_type application/octet-stream;
      limit_except GET HEAD { deny all; }
  }
  ```
  目录：`/opt/club/app-update/{manifest.json, latest.apk, latest.apk.sha256}`，权限 `root:<nginx worker 用户> 0750`（文件 0640）——
  worker 用户以机器实际为准（本机是 `nginx`；Debian 系常见 `www-data`），**写错会 403**。
  location 内一旦用 `add_header` 就不再继承 server 级，所以 HSTS / Alt-Svc 要重复声明。
- **缓存**：绝不能长缓存（否则开关不生效）；用 `no-cache` + ETag/Last-Modified。

---

## 2. 字段契约（v1）

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `schema` | int | ✅ | — | 清单格式版本，当前 `1`。壳不认识更高版本时忽略未知字段。 |
| `generatedAt` | string(ISO8601) | | — | 仅作运维参考，壳不使用 |
| `startRoute` | string | | `/#/m` | 起始路由（必须以 `/#/` 开头，壳会做前缀校验） |
| `flags` | object | | `{}` | 功能开关（见 §3） |
| `notice` | object | | null | 公告横幅：`{id, text, level:info 或 warn, url?, dismissible:bool}` |
| `minShellVersionCode` | int | | 0 | 低于此版本码：弹不可跳过的更新提示（紧急兼容性） |
| `latest` | object | | null | 最新 APK 信息：`{versionCode, versionName, notes, mandatory:bool, apk:{url, sha256, size}}` |
| `blockedVersions` | array | | `[]` | `[{versionCode, action:"block" 或 "warn", message}]` |
| `updateSources` | array[string] | | 内置默认 | APK 下载源优先级（自托管在前） |

### 校验规则（壳侧强制，防被篡改利用）

1. `startRoute` 必须是本站路径（`/#/...`），否则忽略；
2. `apk.url` 必须是 `https://`，且 host ∈ {`8.153.145.175`, `github.com`, `objects.githubusercontent.com`}，否则拒绝；
3. `apk.sha256` 必须为 64 位十六进制，缺失或格式不对 → **拒绝安装**（但保留提示）；
4. 清单解析失败/字段非法 → 全部回退到内置默认值（绝不影响 App 可用性）。

---

## 3. 功能开关（flags）

| 键 | 默认 | 作用 |
|---|---|---|
| `live2d` | `false`（移动端） | Live2D 看板娘（素材 ~60 MB，弱网默认不开） |
| `panorama` | `true` | 全景图入口（WebGL） |
| `autoUpdateCheck` | `true` | 启动后是否检查 APK 更新 |
| `externalLinkInBrowser` | `true` | 外部链接是否交系统浏览器（安全开，不建议关） |
| `forceRefreshEntry` | `false` | 启动时强制重取入口 HTML（调试缓存问题用） |
| `pullToRefresh` | `true` | 下拉刷新总开关（区块 4） |
| `showBackToTop` | `true` | "返回顶部"浮动按钮（区块 4） |
| `edgeToEdge` | `false` | 内容铺到状态栏下（区块 4；**默认关**，原因见 [chunks/CHUNK-04-REPORT.md](chunks/CHUNK-04-REPORT.md) §3.1） |
| `blockLive2dAssets` | `true` | **壳侧**兜底拦截 Live2D 素材（`/static/live2d/`，模型 + 4096 纹理约 60 MB，区块 6）；置 `false` 即放行。与上面的 `live2d`（网站侧是否挂载看板娘）是两件事：网站侧已按视口宽度不挂载，本开关防的是"网站改了规则" |

开关键名一旦发布就不要改名，只能新增。

---

## 4. 示例

```json
{
  "schema": 1,
  "generatedAt": "2026-10-11T07:00:00+08:00",
  "startRoute": "/#/app",
  "flags": {
    "live2d": false,
    "panorama": true,
    "autoUpdateCheck": true,
    "externalLinkInBrowser": true,
    "forceRefreshEntry": false,
    "pullToRefresh": true,
    "showBackToTop": true,
    "edgeToEdge": false,
    "blockLive2dAssets": true
  },
  "notice": {
    "id": "2026-10-open-day",
    "text": "本周五晚 18:30 社团开放日，欢迎带设备来试拍",
    "level": "info",
    "url": "/#/news/128",
    "dismissible": true
  },
  "minShellVersionCode": 1,
  "latest": {
    "versionCode": 2,
    "versionName": "0.2.0",
    "notes": "新增：文件上传与下载支持；修复：深色模式状态栏",
    "mandatory": false,
    "apk": {
      "url": "https://8.153.145.175/app-update/latest.apk",
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
      "size": 3500000
    }
  },
  "blockedVersions": [],
  "updateSources": [
    "https://8.153.145.175/app-update/latest.apk",
    "https://github.com/nhyzcms/app/releases/latest/download/app-release.apk"
  ]
}
```

> ⚠️ 示例里的 sha256 是占位值（全 0）。真实发布时必须用 `sha256sum` 算出的真值。

---

## 5. 灰度和强制更新

v1 故意做简单：

- **强制**：`latest.mandatory=true`，或 `minShellVersionCode > 本机版本码` → 不可跳过；
- **提醒**：`mandatory=false` → 可稍后；
- **灰度**（v2 再加）：按 `versionCode` 或服务端下发的 `rolloutPercent` 控制，先在少量设备上验证；
- v1 不做"按用户"灰度（需要设备标识，本应用**不采集任何设备标识**）。

---

## 6. 版本演进约定

- 新增字段：直接加，旧壳忽略未知字段；
- 删字段/改语义：只升 `schema`，壳必须仍能处理旧 `schema`；
- 紧急回退：把 `latest` 置 `null`（停止推送更新）；用 `notice` 发公告即可。
