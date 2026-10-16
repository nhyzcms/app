# 构建与发版（BUILD）

---

## 1. 环境要求

| 项 | 版本 | 备注 |
|---|---|---|
| JDK | 17 | AGP 8.x 强制 |
| Android SDK | Platform 35 + Build-Tools 35 | `compileSdk = 35` |
| Gradle | 8.13（由 CI 指定） | 本地可不装 wrapper |
| minSdk / targetSdk | 26 / 34 | 见 ARCHITECTURE ADR-A04 |

> 开发机（如服务器）没有 Android SDK 也没关系：**开发只改文本，构建交给 CI**。
> 但注意：本项目的服务器只有 2 vCPU / 2 GB 内存，**不建议在上面跑 Gradle 构建**（会吃光内存）。

---

## 2. 本地构建

```bash
# 方式 A：有 gradle 命令
gradle :app:assembleDebug

# 方式 B：先生成 wrapper（Android Studio 会自动提示）
gradle wrapper --gradle-version 8.13
./gradlew :app:assembleDebug

# 安装到真机
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 看 WebView 控制台（debug 包才有）
# 浏览器打开 chrome://inspect，或 edge://inspect
```

### 指向本地开发服务器（可选）

想让 App 加载你电脑上的 webpack dev server：

```bash
# 修改 AppConfig.SITE_ORIGIN 后重新构建（仅限 debug 包；release 不要改）
gradle :app:assembleDebug
```

> ⚠️ 如果目标不是 HTTPS，会被 network-security-config 拦住。调试本地 HTTP 需要在
> `res/xml/network_security_config.xml` 的 `debug-overrides` 里临时开明文域（不要提交）。

---

## 3. 签名（发版必需）

> **现状（2026-10-17）**：密钥已生成、4 个 Secrets 已配置、CI 已加**指纹闸门**。
> 本节同时是「怎么用」与「出事怎么办」的手册。

### 3.1 现状速查

| 项 | 值 |
|---|---|
| key alias | `clubapp` |
| 证书 SHA-256 指纹 | `EF:D9:A5:CC:F9:BB:C2:35:63:9F:D9:CE:6E:4C:FF:63:19:E6:49:5E:B4:0F:EA:92:6F:D3:C1:D0:A9:1F:3C:A2` |
| 证书 SHA-1 指纹 | `17:C0:AE:2A:36:66:FF:D4:1C:B7:52:E4:0C:FB:E1:C4:01:64:C4:2E` |
| 算法 | 4096-bit RSA / SHA256withRSA |
| 有效期 | 2026-10-16 → **2056-10-08**（30 年） |
| keystore 载体 | 管理员本地 `nhyzcms-release.jks`（PKCS12）+ 离线备份；**永不入库、永不进聊天群** |
| Secrets | 仓库 `nhyzcms/app` 的 4 个（见 §3.2），配置于 2026-10-17 |
| 唯一真源 | `deploy/signing-fingerprint.txt`（指纹登记；公开信息，刻意入库） |

**为什么指纹要入库**：证书指纹本来就在每个 APK 的签名块里，人人可见，不是秘密。
把它写进仓库后，CI 就能在**构建之前**断言「Secrets 里那把钥匙 = 登记的这一把」，
堵住最难查的一类事故 —— 用错密钥发版：构建会成功、Release 会发出，
但已装用户装不上（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），而且一旦有人装了新包，
退回旧密钥也覆盖不了，几乎不可回滚。

### 3.2 配置 GitHub Secrets（已配置；这里是复现/迁移步骤）

| Secret 名 | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 nhyzcms-release.jks` 的输出（单行，无换行） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | `clubapp` |
| `ANDROID_KEY_PASSWORD` | key 口令（PKCS12 下与 store 口令相同） |

```bash
# 用 gh CLI（需 repo 权限）
base64 -w0 nhyzcms-release.jks > keystore.b64
gh secret set ANDROID_KEYSTORE_BASE64   --repo nhyzcms/app < keystore.b64
gh secret set ANDROID_KEYSTORE_PASSWORD --repo nhyzcms/app < 口令文件
gh secret set ANDROID_KEY_PASSWORD      --repo nhyzcms/app < 口令文件
gh secret set ANDROID_KEY_ALIAS         --repo nhyzcms/app --body clubapp
gh secret list --repo nhyzcms/app       # 核对 4 个都在
```

- 口令请走 `< 文件` 或交互输入，**不要写成命令参数**（会进 shell history 与进程列表）；
- Secret 名字要一字不差：写错的表现是「Secrets 明明配好了，CI 却说它是空的」，很难查
  （`scripts/preflight-signing.sh` 会在本地/CI 静态检查这 4 个名字是否都在工作流里被引用）。

### 3.3 验证（配完必做，且不需要发版）

```bash
# ① 手动触发 CI 的签名自检作业：只读 Secrets、只比对指纹，不构建也不发任何包
gh workflow run android.yml --repo nhyzcms/app --ref main
gh run list --repo nhyzcms/app --limit 3
```
跑完看 Actions 里 `签名自检（手动触发）` 这个作业；Step Summary 会打印登记的指纹与有效期。

```bash
# ② 有 keystore 文件的机器上直接比对
ANDROID_KEYSTORE_PATH=./nhyzcms-release.jks \
ANDROID_KEYSTORE_PASSWORD='***' \
ANDROID_KEY_ALIAS=clubapp \
bash scripts/verify-keystore.sh
```

脚本会核对别名、证书 SHA-256 指纹、剩余有效期，**从不打印口令**。
反向验证（确认闸门真的会拦，换一把密钥应立刻失败）：

```bash
ANDROID_KEYSTORE_PATH=/tmp/other.jks ANDROID_KEYSTORE_PASSWORD='***' \
  bash scripts/verify-keystore.sh; echo $?      # → 1
```

### 3.4 轮换密钥（什么时候做、代价是什么）

**什么时候**：keystore 或口令泄露/丢失。证书到 2056 年，正常运维不需要轮换。

**代价先说清楚**：
- 已装用户**无法覆盖安装**（签名不一致）→ 只能卸载重装，**会丢本地数据**；
- 自托管更新链（`/app-update/`）对这些老用户直接断掉；
- 若将来上架应用商店，还要走商店的换钥流程。

**步骤（顺序不能反）**：
1. 生成新 keystore（§3.6 的命令，换个文件名）；
2. 更新 4 个 Secrets（§3.2）；
3. 更新 `deploy/signing-fingerprint.txt` 的 `CERT_SHA256` / `CERT_SHA1` / `GENERATED_AT` / `VALID_UNTIL`；
4. 跑 §3.3 的自检，通过后再打新 tag 发版；
5. 在该版 Release 说明里**明确写出「本版更换了签名，老用户需卸载重装」**。

> 反过来：如果只是 Secrets 配错了（钥匙本身没变），**不要**去改登记文件 ——
> 按 §3.2 把 Secrets 改回来。闸门报错时先自问：是 Secrets 错，还是我确实要换钥？

### 3.5 备份与丢失处置

- 备份两份：**私有云盘（加密压缩）+ 离线介质**；口令与 keystore **分开存放**（放一起等于一把钥匙开两个锁）；
- 备份上传完成后，删除手机「下载」目录里的明文副本；
- **丢失的后果**：从丢失那版起的用户全部无法覆盖安装，只能卸载重装（丢登录态与本地数据）。
  这一点没有技术手段可以绕开 —— 签名一致性是 Android 系统强制的，不是本项目的设计选择。
- 因此 §3.4 的换钥流程**必须**配合 Release 公告，否则用户只会看到"安装失败"。

### 3.6 生成新 keystore 的原始命令

```bash
keytool -genkeypair -v \
  -keystore nhyzcms-release.jks \
  -alias clubapp \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10950 \
  -storetype PKCS12 \
  -storepass '<强口令>' -keypass '<强口令>' \
  -dname "CN=Nanhui No.1 Middle School Media Club, OU=Club IT, O=nhyzcms, L=Shanghai, ST=Shanghai, C=CN"
```

- 有效期 10950 天（30 年）：签名证书必须覆盖 APK 的整个生命周期，短了会「过期装不上」；
- PKCS12 下 store 口令与 key 口令**必须相同**（JKS 才允许不同）；
- 生成后立刻按 §3.5 备份，并按 §3.1 / §3.4 登记指纹与更新 Secrets。

**未配 Secrets 时**：`assembleDebug` 仍可跑（PR 校验不为难 fork），
但 `assembleRelease` 会直接报错退出，**不会发出未签名的包**。


---

## 4. CI（`.github/workflows/android.yml`）

| 事件 | 作业（job id） | 产物 |
|---|---|---|
| PR / push 到非 tag | `debug` | debug APK artifact（14 天） |
| **手动触发** `workflow_dispatch` | `verify-signing` | 不发包：只验证 Secrets 能解出登记在册的那把 keystore |
| push tag `vX.Y.Z` | `release` | 签名 APK + SHA-256 写入 GitHub Release |

`release` 作业有三道闸门，任一不过就直接失败、不发包：

1. 4 个 Secrets 齐备；
2. **构建前**：`scripts/verify-keystore.sh` 比对解码出的 keystore 证书指纹
   与 `deploy/signing-fingerprint.txt` 登记值一致；
3. **构建后**：`apksigner verify --print-certs` 校验**产物 APK 本身**的签名证书
   等于登记值（验的是包，不只是钥匙；本地无 SDK，这一道只能在 CI 做）。

版本号来源优先级：`GITHUB_REF` 的 tag（仅在发版时）> `version.properties`。

> 本仓库**不提交 `gradle-wrapper.jar`**（二进制不便审查），CI 用
> `gradle/actions/setup-gradle` 指定 Gradle 版本。

---

## 5. 版本号规则

`version.properties`：

```properties
versionCode=1
versionName=0.1.0
```

- `versionCode` 只能递增（Android 不允许降级安装）；
- `versionName` 用语义化版本；
- 发版时：改 `version.properties` → 提交 → 打 tag `vX.Y.Z`（tag 必须与 `versionName` 一致，CI 会校验）。

---

## 6. 发版步骤（SOP）

```bash
# 1. bump 版本
vim version.properties     # versionCode +1, versionName 更新
# 2. 写本版说明
vim docs/chunks/CHUNK-0N-REPORT.md
# 3. 提 PR → 审阅 → 合并到 main
# 4. 发版前自检（30 秒，且不用发包）：确认 Secrets 能解出登记在册的那把 keystore
gh workflow run android.yml --repo nhyzcms/app --ref main
gh run list --repo nhyzcms/app --limit 3        # 看 verify-signing 作业是否绿
# 5. 在 main 上打 tag（tag 必须与 versionName 一致，CI 会校验）
git tag v0.2.0 && git push origin v0.2.0
# 6. CI 自动构建（含指纹闸门）+ 发 Release
# 7. 同步 APK 到自托管目录并更新 manifest.json（见 UPDATE-STRATEGY §6.3）
```

---

## 7. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `SDK location not found` | 缺 `local.properties` 或 `ANDROID_HOME` | 本地指 Android SDK；CI 用 `android-actions/setup-android` |
| `Unsupported class file major version` | JDK 版本不对 | 用 JDK 17 |
| App 里白屏但浏览器正常 | WebView 版本旧 / 入口 HTML 被缓存 | 看 `chrome://inspect` 控制台；调 manifest `forceRefreshEntry` |
| 升级安装失败（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`） | 签名不一致 | 确认用了同一 keystore；用户需卸载重装 |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | 试图装更低的 versionCode | Android 不允许；必须升版本号 |
| 上传按钮点了没反应 | 未实现 `onShowFileChooser` | 区块 2 交付后解决 |
