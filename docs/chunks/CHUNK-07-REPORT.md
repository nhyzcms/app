# 区块 7 报告 · 签名与首个 Release（第一步：密钥与签名闸门）

日期：2026-10-17
分支：`feat/07-signing-release`
状态：**区块 7 已完成** —— 密钥与 Secrets 就绪、发版闸门已上、首个 Release `v0.1.0` 已发布、
自托管镜像已生效；仅剩真机验收。

---

## 1. 这一块在做什么

区块 7 的目标是"产出可对外分发的正式 APK"。它比看起来危险，因为签名是**更新路径的信任根**：

- 站点侧（L0）随时可改，App 不用动；
- 远程开关与公告（L1）走 `app-update/manifest.json`，也不用动 App；
- 但**只要发一次 APK（L2）**，往后所有版本就必须用**同一把密钥**签名，
  否则已装用户 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` —— 装不上，且一旦有人装了新包，
  退回旧密钥也覆盖不了，几乎不可回滚。

所以这一次没有直接"生成密钥 → 发第一个包"，而是先把**"发错包"这件事变成不可能**：
在 CI 构建之前加一道指纹闸门。理由见 §3.1。

---

## 2. 交付物

| 文件 | 作用 |
|---|---|
| `deploy/signing-fingerprint.txt` | 发布证书指纹登记（`KEY_ALIAS` / `CERT_SHA256` / `CERT_SHA1` / `DN` / `ALGORITHM` / 有效期）。**公开信息，刻意入库**，是 CI 与文档的唯一真源 |
| `scripts/verify-keystore.sh` | **发版闸门**：读 `ANDROID_KEYSTORE_PATH`（或 `ANDROID_KEYSTORE_BASE64`）+ 口令，比对证书 SHA-256 指纹、alias 一致性、证书有效期；从不打印口令 |
| `scripts/preflight-signing.sh` | 静态预检：登记文件格式、`.gitignore` 屏蔽 `*.jks/*.keystore`、仓库无密钥文件、闸门脚本未被删、4 个 Secret 名字齐全、口令不得被 `echo` 进日志、脚本里不得出现明文口令 |
| `.github/workflows/android.yml` | ① `release` 作业新增"校验签名指纹"步骤（构建前阻断）；② 新增"校验产物签名证书"步骤（构建后用 `apksigner` 验产物本身）；③ 新增 `verify-signing` 作业（手动触发，不发包，只验证 Secrets） |
| 文档 | `docs/BUILD.md` §3 重写为手册（现状速查 / 配 Secrets / 自检 / 轮换 / 备份 / 原始命令）、`docs/SECURITY.md` §5、`docs/CI-WORKFLOW.md` §1 §5 §13、`docs/CODE-MAP.md` §9 §10 §12、`docs/ROADMAP.md` 区块 7、`README.md` 状态表 |

密钥本体（`nhyzcms-release.jks`，PKCS12，4096-bit RSA，alias `clubapp`，2026-10-16 → 2056-10-08 有效）
**不在仓库里**：已交付管理员，并要求云盘 + 离线双备份、口令与文件分开存放。

仓库 `nhyzcms/app` 的 4 个 Secrets 已于 2026-10-17 写入：
`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`。

---

## 3. 关键决策与理由

### 3.1 为什么把证书指纹写进仓库

指纹不是秘密——它本来就在每个 APK 的签名块里，任何人 `apksigner verify --print-certs` 都看得到。
把它登记进仓库后，CI 就能在**构建之前**断言"仓库里配的这把钥匙 = 登记的那把"，从而堵住：

- Secrets 被误改 / 误传成别人的 keystore；
- 有人"顺手"换了密钥却忘了同步；
- 手工构建后误把别的签名包发出去。

这类错误的共同特征是：**构建成功、CI 全绿、Release 发得出去，只有用户装不上**。
如果不在构建前拦，它会在"用户反馈装不上"那一刻才暴露，而那时已经有人装了新包。

### 3.2 为什么闸门要放"发版前"而不是"发版后校验"

`release` 作业的顺序是：预检 → 单测 → **解码 Secrets → 校验指纹（失败即停）** → `assembleRelease`。
放在构建前，代价是 0.5 秒；放在构建后，代价是"包已经签好、Release 已经发出"。

### 3.3 为什么口令走 stdin 而不是命令行参数

`gh secret set < 文件`（或用交互输入）避免口令留在 shell history 与 `ps` 的进程列表里。
`docs/BUILD.md` §3.2 与 `scripts/preflight-signing.sh` 都固化了这条。

### 3.4 为什么是 PKCS12 而不是 JKS

PKCS12 是密钥库的现代标准格式，且被 Android Studio / Gradle / keytool / openssl 全线支持。
代价是 store 口令与 key 口令**必须相同**（JKS 才允许不同），这一点已写进 BUILD.md §3.6。

### 3.5 为什么不顺便做"证书固定"（Certificate Pinning）

与站点侧同一个理由（见 `docs/SECURITY.md` §2）：站点证书是 ZeroSSL DV、SAN 是 IP、
90 天换一次，固定必在换证日全量失联。**发版签名**与**站点 TLS**是两件不同的事，不要混。

---

## 4. 验证记录（本地，2026-10-17）

`scripts/verify-keystore.sh` 做了 6 组测试，全部符合预期：

| # | 场景 | 结果 |
|---|---|---|
| ① | 正向：`ANDROID_KEYSTORE_PATH` 指向真密钥 + 真口令 + `clubapp` | ✅ 通过（打印剩余有效期 10949 天） |
| ② | 正向：`ANDROID_KEYSTORE_BASE64` 模式（`verify-signing` 作业走这条代码路径） | ✅ 通过 |
| ③ | 反向：口令错 | ✅ 按预期失败（exit 1） |
| ④ | 反向：`ANDROID_KEY_ALIAS=otherapp` | ✅ 按预期失败并指出与登记值不一致 |
| ⑤ | 反向：把 keystore 换成另一把（模拟 Secrets 被误改） | ✅ 按预期失败，打印实际 vs 期望指纹 |
| ⑥ | 反向：把登记文件里的指纹改成 `AB:CD`（模拟手滑） | ✅ 按预期失败（格式校验先拦） |

另外确认：**口令从头到尾没有出现在任何输出里**（keytool 的报错也只说 "keystore password was incorrect"）。

静态预检：`bash scripts/preflight.sh` ✅、`bash scripts/preflight-feel.sh` ✅
（后者连带 `preflight-perf.sh` / `preflight-push.sh` / `preflight-signing.sh` 全绿）。
新增的 `preflight-signing.sh` 首跑就抓到两处**它自己的误报**（注释里提到 "Secrets"、脚本源码里含
`-storepass` 字面量），已按语义收窄规则（只查赋值行的口令字样、跳过检查脚本自身）。

> 说明：本地没有 Android SDK，Gradle 编译与 release 打包仍由 CI 完成；
> 本块只改文本与 shell，未触碰 Kotlin。

### 4.1 补记：首个 tag 红了，以及它是怎么被修掉的（2026-10-17）

推 tag `v0.1.0` 后 `release` 作业在第 12 步失败：

```
NullPointerException: SigningConfig "release" is missing required property "keyPassword"
```

前 11 步（含 keystore 指纹闸门）全绿 —— 说明密钥与 Secrets 都没问题，问题在 Gradle 脚本。
根因是 `app/build.gradle.kts` 里 `keyAlias = keyAlias` / `keyPassword = keyPassword` 的**自赋值**
（带接收者的 lambda 里两侧都解析成接收者自身的属性），已用 `kotlinc` 最小复现证实，
修复与复盘见 [FIX-20261017-SIGNING-SELF-ASSIGN.md](../FIX-20261017-SIGNING-SELF-ASSIGN.md)。

顺带补上了这块最根本的短板：**以前 release 构建只能用 tag 去试**，现在有了「试跑 Release」
（`workflow_dispatch` + `dry_run_release=true`，完整构建但不发 Release）。
修复后的试跑（run 38934792986）结果：

| 步骤 | 结果 |
|---|---|
| 11 校验签名指纹 | ✅ |
| 12 构建 release APK | ✅ |
| 14 校验产物签名证书（apksigner） | ✅ |
| 16 创建 GitHub Release | ⏭ skipped（试跑模式按设计不发版） |

---

## 5. 收尾结果（区块 7 已完成，仅剩真机验收）

1. ✅ 密钥 / 闸门 / 文档 PR #21 已合并；签名配置自赋值的修复 PR #22 已合并。
2. ✅ `verify-signing` 手动作业在 main 上跑通（run 38932598026：解码 keystore ✅ + 校验指纹 ✅）。
3. ✅ 首个正式 Release 已发布：tag `v0.1.0` 打在 `24f3ee1` 上，
   run 38936344629 全绿（含构建前 keystore 指纹闸门、构建后产物 `apksigner` 校验），
   [Release 页面](https://github.com/nhyzcms/app/releases/tag/v0.1.0)：
   - `app-release.apk` —— 664,818 字节
   - `app-release.apk.sha256` —— `6e5f62298a6e0001adf63d9bc15af6e6f2615e5ed48f0f9589439ef6bc530809`
   > 首次推送的 tag `v0.1.0` 因签名配置自赋值构建失败、未产出任何 Release，
   > 修复后已重打到新 main（tag 本身的沿革写进了 tag message）。

4. ✅ 自托管镜像已发布并端到端验证：

   | 项 | 值 |
   |---|---|
   | 目录 | `/opt/club/data/app-update/`（0755 / 文件 0644） |
   | manifest | `latest` = v0.1.0（versionCode 1）、sha256、size 664818、强制更新 false |
   | `manifest.json` | 200 + `application/json` |
   | `latest.apk` | 200 + `application/vnd.android.package-archive` + 664818 字节 |
   | 端到端 | `curl -skSL …/latest.apk \| sha256sum` = manifest 里的 sha256 ✔ |
   | 站点 | 首页 200；nginx / club / redis / postgresql-17 全 active |

   > 过程中发现一个运维陷阱：原来的 `/opt/club/app-update/` 被网站自动更新
   > （rsync `--delete` 语义）在 2026-10-17 00:42 **整个删掉**，端点 404。
   > 已迁到更新器排除项内的 `data` 根，并把新路径写进 `scripts/publish-apk.sh` 默认值、
   > `scripts/nginx-app-update.conf` 与相关文档，防止下一个人重犯。
   > 同类问题：发布脚本原来用 `install -m 0640`（root:root），nginx 读不到会 403 —— 已改为 0755/0644。

5. ⏳ **只剩真机验收**：在真机上安装 Release 的 `app-release.apk`（与 debug 包可共存，`applicationId` 不同），
   确认能进站、能登录；再发一个同签名的新版本，确认**覆盖安装**成功（这才是签名闸门真正保护的那条路径）。

---

## 6. 红线（下次维护时先读这段）

- **keystore 与口令丢了** → 从丢失那版起的用户只能卸载重装（丢登录态与本地数据），无法用技术手段绕开。
- **登记文件不是"随手可改的配置"**：只有真的换了密钥才改它；Secrets 配错时应该改 Secrets。
- **每次发版前跑一次 §5.2 的自检**（30 秒），比事后查"为什么用户装不上"便宜得多。
- 轮换密钥必须配 Release 公告 —— 用户看到的只是"安装失败"，不解释就变成客服问题。
