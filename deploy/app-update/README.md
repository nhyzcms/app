# 更新分发目录（/app-update/）

这个目录是 **L1（清单）与 L2（更新包）的唯一分发点**，走 nginx 静态文件，不经 Django。
字段契约见 [docs/APP-MANIFEST.md](../../docs/APP-MANIFEST.md)，策略见 [docs/UPDATE-STRATEGY.md](../../docs/UPDATE-STRATEGY.md)。

| 文件 | 作用 |
|---|---|
| `manifest.json` | 壳每次检查更新时拉的清单（起始路由、开关、公告、最新版本、停用版本） |
| `latest.apk` | 自托管的更新包（首选下载源） |
| `latest.apk.sha256` | 上者的 sha256（便于人工核对，壳读的是 manifest 里的值） |

服务器上的实际目录是 `/opt/club/app-update/`（与发布包解压目录分离：更新器只替换 Django 应用文件，
不会动 `app-update/`）。

---

## 1. 首次部署（需要服务器管理员执行）

```bash
# ① 建目录（root 拥有，www-data 只读）
sudo install -d -m 0750 -o root -g www-data /opt/club/app-update

# ② 放一份初始清单（本仓库里的模板）
sudo install -m 0640 -o root -g www-data \
     /opt/club/deploy/app-update/manifest.json \
     /opt/club/app-update/manifest.json

# ③ 把 scripts/nginx-app-update.conf 的内容并进 /etc/nginx/conf.d/club.conf 的 server 块
#    （先备份原文件；片段里已含 no-cache 与只读限制）
sudo cp -a /etc/nginx/conf.d/club.conf /etc/nginx/conf.d/club.conf.bak.app-update
sudo nginx -t
sudo nginx -s reload

# ④ 验证（应返回 200 且 Content-Type: application/json）
curl -sSI https://8.153.145.175/app-update/manifest.json
```

> 注意：本仓库的 `deploy/` 目录只是"要拷到服务器的那份文件"的副本，
> 服务器上的 `/opt/club/app-update/` 才是生效位置（与生产应用目录分离，更新器不会碰） 。

## 2. 发一版新 APK（L2）

```bash
# 在服务器上（或任何能写 /opt/club/app-update 的机器）
bash scripts/publish-apk.sh \
  --apk /tmp/app-release.apk \
  --version-code 2 \
  --version-name 0.2.0 \
  --notes "新增：文件上传与下载支持"
```

脚本会算出 sha256、把包复制成 `latest.apk`、更新 `manifest.json` 的 `latest` 字段，
其余字段（开关、公告、起始路由、停用版本）保持不动。

## 3. 只改配置（L1，不用发版）

- 起始页：改 `startRoute`（必须以 `/#/` 开头，壳会做前缀校验）；
- 紧急关掉某个功能：改 `flags`（例如 `"live2d": false`）；
- 公告：填 `notice`（同 `id` 只弹一次）；
- 停用某个旧壳版本：往 `blockedVersions` 加 `{"versionCode": N, "action": "block"}`；
- 停止推送更新：把 `latest` 置为 `null`。

改完直接生效（`Cache-Control: no-cache`），可用 `curl -sS` 复核内容。

## 4. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| `curl` 拿到的是 HTML | nginx 的 location 没生效（被 SPA 兜底路由吞了）→ 检查 `nginx -t` 与 location 是否在 server 块内 |
| App 里"检查更新失败" | 清单 404/非法 JSON → 壳会**静默**回退到缓存与默认开关（设计如此），手动检查才会提示失败 |
| App 提示"更新包校验未通过" | `manifest.json` 里的 `sha256`/`size` 与 `latest.apk` 不一致 → 重跑 `publish-apk.sh` |
| App 提示"签名不一致，拒绝安装" | 新包与已装版本的签名不同（例如用 debug 包测 release 包）→ 用同一签名重发 |
| 开关改了但没生效 | 冷启动才读缓存清单 → 杀掉进程重开；仍不生效则检查 JSON 是否合法 |
