# 更新分发目录（/app-update/）

这个目录是 **L1（清单）与 L2（更新包）的唯一分发点**，走 nginx 静态文件，不经 Django。
字段契约见 [docs/APP-MANIFEST.md](../../docs/APP-MANIFEST.md)，策略见 [docs/UPDATE-STRATEGY.md](../../docs/UPDATE-STRATEGY.md)。

| 文件 | 作用 |
|---|---|
| `manifest.json` | 壳每次检查更新时拉的清单（起始路由、开关、公告、最新版本、停用版本） |
| `latest.apk` | 自托管的更新包（首选下载源） |
| `latest.apk.sha256` | 上者的 sha256（便于人工核对，壳读的是 manifest 里的值） |

服务器上的实际目录是 **`/opt/club/data/app-update/`**。

> ⚠️ **为什么是 `data/` 下面（2026-10-17 实测，血泪）**：
> 网站更新器应用发布包时是「rsync `-a --delete` 等价语义」——`/opt/club` 下
> **不在 `SYNC_EXCLUDES`**（`.env` / `db.sqlite3*` / `media` / `private_media` / `data` /
> `run` / `backups` / `.venv` / `.git`，见网站仓库 `common/updater.py`）里的顶层条目**会被删掉**。
> 最初放在 `/opt/club/app-update/`，结果 **2026-10-17 00:42 的自动更新把它整个删了**，
> `/app-update/` 端点立刻 404（nginx 配置还在，只是目录没了）。
> `data` 是网站侧的统一数据根、在排除项里，所以放到这里才不会被更新清掉。
>
> 顺带纠正一条旧结论：**「更新器不会碰 app-update/」是错的**（CHUNK-03 里那个"未实测"的问题，
> 答案就是会删；详见 `docs/PITFALLS.md` §8）。

---

## 1. 首次部署（需要服务器管理员执行）

```bash
# ⓪ 确认 nginx worker 用户（决定权限怎么给，写错会 403）
grep ^user /etc/nginx/nginx.conf      # 本机是 nginx；Debian 系常见是 www-data

# ① 建目录（必须在 /opt/club/data/ 下，理由见上方警告）
#    0755 + 文件 0644：连 other 都可读，最简单也最不容易出错；
#    若想收紧，可 root:nginx 0750 + 文件 0640（注意 install 默认生成 root:root）
sudo install -d -m 0755 /opt/club/data/app-update

# ② 放一份初始清单（本仓库里的模板）
sudo install -m 0644 deploy/app-update/manifest.json /opt/club/data/app-update/manifest.json

# ③ 把 scripts/nginx-app-update.conf 的内容并进 /etc/nginx/conf.d/club.conf 的 HTTPS server 块
#    （先备份原文件；片段里已含 no-cache、只读限制与 HSTS/Alt-Svc 重复声明）
sudo cp -a /etc/nginx/conf.d/club.conf /etc/nginx/conf.d/club.conf.bak.app-update-$(date +%Y%m%d-%H%M%S)
sudo nginx -t
sudo nginx -s reload

# ④ 验证（应返回 200，Content-Type: application/json，Cache-Control: no-cache）
curl -sSI https://8.153.145.175/app-update/manifest.json
```

### 1.1 本机部署记录（2026-10-11 22:58，已完成）

- 备份：`/etc/nginx/conf.d/club.conf.bak.app-update-20261011-225834`
- 片段插入位置：HTTPS `server { }` 块内，`location / {`（gunicorn 反代）之前
- 目录：~~`/opt/club/app-update/` → `root:nginx 0750`~~（**已被更新器删除，见 §1.2**）
- 验证：`manifest.json` → **200 + application/json + cache-control: no-cache**；`latest.apk` → 404（还没发包，符合预期）；首页仍 200；nginx / club / redis / postgresql-17 / club-updater-tunnel 均 active
- ~~注意：更新器不会碰 `app-update/`~~ → **这条是错的，2026-10-17 被证伪，见 §1.2**

### 1.2 迁移到 data 根 + 首个正式包（2026-10-17，已完成）

**背景**：2026-10-17 00:42 的自动更新（应用的发布包 `b6c065a`）把 `/opt/club/app-update/`
连同内容一起删掉了 —— 更新器是 rsync `--delete` 语义，而 `app-update` 不在 `SYNC_EXCLUDES` 里。
同时网站侧已迁移到统一数据根：`/opt/club/data/{media,private_media,app-update}`，
nginx 的三个 alias 也随之指向 `/opt/club/data/...`（`club.conf` 于 01:49 更新）。

**结果（当前生效的部署）**：

| 项 | 值 |
|---|---|
| 目录 | `/opt/club/data/app-update/`（0755，文件 0644） |
| nginx alias | `/opt/club/data/app-update/`（备份 `club.conf.bak.appupdirdir-20261017-092944`） |
| manifest | `latest` = v0.1.0（versionCode 1），sha256 `6e5f62298a6e0001adf63d9bc15af6e6f2615e5ed48f0f9589439ef6bc530809`，size 664818 |
| 验证 | `manifest.json` 200 + `application/json`；`latest.apk` 200 + `application/vnd.android.package-archive` + content-length 664818；**从公网下载再算 sha256 = manifest 里的值**（端到端过了） |
| 服务 | nginx / club / redis / postgresql-17 全部 active；首页 200 |

**为什么这样就安全了**：`data` 在更新器的 `SYNC_EXCLUDES` 里，之后的自动更新不会再删这个目录。
（已加进 `scripts/publish-apk.sh` 的默认 `--dir` 与 `scripts/nginx-app-update.conf` 的 alias，
下次换机器不会重犯。）

## 2. 发一版新 APK（L2）

```bash
# 在服务器上（或任何能写 /opt/club/data/app-update 的机器）
bash scripts/publish-apk.sh \
  --apk /tmp/app-release.apk \
  --version-code 2 \
  --version-name 0.2.0 \
  --notes "新增：文件上传与下载支持"

# 发完做端到端核对：下载回来的包哈希必须等于脚本打印的 APK_SHA
curl -skSL https://8.153.145.175/app-update/latest.apk | sha256sum
```

脚本会算出 sha256、把包复制成 `latest.apk`、更新 `manifest.json` 的 `latest` 字段，
其余字段（开关、公告、起始路由、停用版本）保持不动；同时把权限设成
「目录 0755 + 文件 0644」（nginx 以 `nginx` 用户读文件，0640 root:root 会 403）。

> 注意脚本用 `install -m 0640` 落盘，默认属主是当前用户；用 `--dir` 指定目录时若需要
> worker 可读，请自行 `chown root:nginx`（或你的 worker 用户）。

## 3. 只改配置（L1，不用发版）

- 起始页：改 `startRoute`（必须以 `/#/` 开头，壳会做前缀校验）；
- 紧急关掉某个功能：改 `flags`（例如 `"live2d": false`）；
- 公告：填 `notice`（同 `id` 只弹一次）；
- 停用某个旧壳版本：往 `blockedVersions` 加 `{"versionCode": N, "action": "block"}`；
- 停止推送更新：把 `latest` 置为 `null`。

改完直接生效（`Cache-Control: no-cache`），可用 `curl -sS` 复核内容。

> 壳侧读清单的时机：冷启动后 3 秒 / 回到前台距上次超过 30 分钟 / 手动点错误面板的「检查更新」。
> 起始路由与开关是**落盘后下次冷启动生效**（首屏不等网络）。

## 4. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| `curl` 拿到的是 HTML | nginx 的 location 没生效（被 SPA 兜底路由吞了）→ 检查 `nginx -t` 与 location 是否在 HTTPS server 块内 |
| 访问返回 403 | 目录/文件属主不是 nginx worker 用户（见 §1 第 ⓪ 步） |
| App 里"检查更新失败" | 清单 404/非法 JSON → 壳会**静默**回退到缓存与默认开关（设计如此），手动检查才会提示失败 |
| App 提示"更新包校验未通过" | `manifest.json` 里的 `sha256`/`size` 与 `latest.apk` 不一致 → 重跑 `publish-apk.sh` |
| App 提示"签名不一致，拒绝安装" | 新包与已装版本的签名不同（例如用 debug 包测 release 包）→ 用同一签名重发 |
| 开关改了但没生效 | 冷启动才读缓存清单 → 杀掉进程重开；仍不生效则检查 JSON 是否合法 |
