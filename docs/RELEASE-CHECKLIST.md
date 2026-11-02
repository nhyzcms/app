# 发布前检查表（RELEASE-CHECKLIST）

> **怎么用**：每发一版照抄一份到 PR 描述里，逐项打勾。任何一项不通过就**停**。
> 历史教训：v0.1.0 是在 `assembleRelease` 才失败的（签名配置自赋值），
> 而当时预检、单测、指纹校验全绿 —— 所以「试跑 Release」这一项不是可选项。
>
> 相关文档：[BUILD.md](BUILD.md)（构建环境）、[UPDATE-STRATEGY.md](UPDATE-STRATEGY.md)（三级更新）、
> [deploy/app-update/README.md](../deploy/app-update/README.md)（分发目录）、[REGRESSION.md](REGRESSION.md)（人工回归）。

---

## 0. 范围与版本

- [ ] 本次发布**只**包含已合并到 `main` 的提交（`git log --oneline origin/main | head` 核对）
- [ ] 没有遗留的 `TODO` / 调试日志 / `BuildConfig.DEBUG` 分支被误当成正式逻辑
- [ ] `version.properties` 的 `versionName` / `versionCode` 已按语义化版本递增
- [ ] 发版分支上的 `docs/ROADMAP.md`、`README.md` 状态表已更新

## 1. 自动化验证（本地或 CI 任一即可，CI 更权威）

- [ ] `bash scripts/verify.sh --static` 全绿（含 `preflight-regress.sh` 三条红线）
- [ ] `gradle :app:testDebugUnitTest` 全绿（含 `MainActivityShellTest`）
- [ ] `gradle :app:assembleDebug` 成功
- [ ] PR 上四个检查（Debug APK / 静态预检 / 单测 / 签名自检）全绿

## 2. 签名与密钥（红线，任何一项异常都停）

- [ ] `deploy/signing-fingerprint.txt` 未被改动（`git diff origin/main -- deploy/signing-fingerprint.txt` 为空）
- [ ] `bash scripts/verify-keystore.sh <keystore> <alias> <storepass> <keypass>` 通过（若本地有 keystore）
- [ ] GitHub Secrets 四项仍在：`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` /
      `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`
- [ ] **没有**把 keystore、口令、`*.jks` 提交进仓库（`git status --porcelain` 核对）
- [ ] 若本次涉及换钥：已按 [ROADMAP 区块 7](ROADMAP.md) 的红线准备 Release 公告（老用户只能卸载重装）

## 3. 试跑 Release（打 tag 之前必须做）

- [ ] 在 Actions 里手动触发 `Android` 工作流，勾选 `dry_run_release=true`，`ref` 选**待发分支**
- [ ] 该次运行通过：keystore 指纹闸门 → `assembleRelease` → `apksigner` 产物校验（不创建 Release）
- [ ] 确认产物体积没有异常增长（对比上一版 `app-release.apk` 的大小；区块 8 的 ZXing 约 +0.7 MB 属预期）

## 4. 打 tag 与正式构建

- [ ] tag 名与 `version.properties` 逐字一致（`v<versionName>`），例如 `v0.2.0`
- [ ] tag 打在 `main` 的合并提交上（不是功能分支的中间提交）
- [ ] `release` 作业全绿，GitHub Release 已创建
- [ ] Release 附件包含 `app-release.apk` 与 `.sha256`
- [ ] 记录 Release 的 sha256：`sha256sum app-release.apk`

## 5. 自托管分发（`/app-update/`）

> ⚠️ 目录必须是 `/opt/club/data/app-update/`（在更新器的 `SYNC_EXCLUDES` 里）。
> 放 `/opt/club/app-update/` 会被下一次自动更新整个删掉（2026-10-17 实测）。

- [ ] 把 Release 的 APK 传到服务器，用脚本发布（不要手改 manifest）：

  ```bash
  bash scripts/publish-apk.sh \
    --apk /path/app-release.apk \
    --version-code <code> --version-name <name> \
    --notes "<本次更新说明>"
  # 需要强制更新时加 --mandatory
  ```

- [ ] **回读核对**（必须做，不能只看脚本输出）：

  ```bash
  curl -sS https://8.153.145.175/app-update/manifest.json | head -40
  curl -sS -o /tmp/latest.apk https://8.153.145.175/app-update/latest.apk
  sha256sum /tmp/latest.apk     # 必须等于 manifest 里的 sha256 与 Release 的 sha256
  ```

- [ ] `manifest.json` 的 `latest.versionCode` / `versionName` / `sha256` / `size` 与 Release 一致
- [ ] `flags` / `notice` / `startRoute` / `blockedVersions` 没有被脚本意外改写
- [ ] `Cache-Control: no-cache` 仍生效（清单不能被代理缓存）

## 6. 真机验收

- [ ] 覆盖安装旧版本 → 新版本能装上（签名链路的最终验证）
- [ ] [REGRESSION.md](REGRESSION.md) 的 A / B / C 三组通过（改动涉及哪组就加跑哪组）
- [ ] 本次改动涉及的分组（D 推送 / E 扫码 / F 弱网 / G 更新 / H 安全）全部通过
- [ ] 老设备（Android 8~9）至少跑一次 A1 / A8
- [ ] 在 Release 说明里写清已知限制（例如"富文本编辑器不支持手机"）

## 7. 收尾

- [ ] `docs/ROADMAP.md` 的对应区块标为完成，写明验收人/日期/机型
- [ ] `README.md` 状态表与 `docs/chunks/CHUNK-0N-REPORT.md` 已同步
- [ ] 本次踩到的坑写进 [PITFALLS.md](PITFALLS.md)，并在预检里加一条规则（若可自动化）
- [ ] 若改了网站侧（UA 闸门、入口、缓存头），网站仓库 PR 已合并并部署
- [ ] 公告/群通知已发（含更新内容与"必须更新"与否）

---

## 回滚预案

| 场景 | 动作 |
|---|---|
| 新 APK 装不上 / 一装就崩 | 把 `manifest.json` 的 `latest` 改回上一版（`versionCode` 与 `sha256` 一起改），或临时置 `latest: null` 停掉推送 |
| 新版本有严重问题 | 把坏版本号加进 `blockedVersions`，同时发布修复版；必要时置 `--mandatory` 引导升级 |
| 网站侧改坏 | 网站仓库回滚到上一个 Release tag，走它自己的发布流程（本仓库只负责 APK 与清单） |
| 清单端点 404 | 先查 `/opt/club/data/app-update/` 是否还在（更新器排除项），再查 nginx `location /app-update/` |
