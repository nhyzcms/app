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

### 3.1 生成 keystore（用户自己执行，不要交给任何人）

```bash
keytool -genkeypair -v \
  -keystore clubapp-release.jks \
  -alias clubapp \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass '<强口令>' -keypass '<强口令>'
```

- 有效期 30 年（10950 天），避免 APK 过期；
- 生成后**立即双人备份**（云盘 + 离线）：丢了就只能让用户卸载重装；
- 永远不要把这个文件提交到任何仓库。

### 3.2 配置 GitHub Secrets

| Secret 名 | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 clubapp-release.jks` 的输出 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | `clubapp` |
| `ANDROID_KEY_PASSWORD` | key 口令 |

未配 Secrets 时：`assembleDebug` 仍可跑（PR 校验不为难 fork），但 `assembleRelease` 会直接报错退出，**不会发出未签名的包**。

---

## 4. CI（`.github/workflows/android.yml`）

| 事件 | 作业 | 产物 |
|---|---|---|
| PR / push 到非 tag | `build-debug` | debug APK artifact（14 天） |
| push tag `vX.Y.Z` | `build-release` → `publish-release` | 签名 APK + SHA-256 写入 GitHub Release |

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
# 4. 在 main 上打 tag
git tag v0.2.0 && git push origin v0.2.0
# 5. CI 自动构建 + 发 Release
# 6. 同步 APK 到自托管目录并更新 manifest.json（见 UPDATE-STRATEGY §6.3）
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
