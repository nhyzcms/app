# CI 工作流说明

**状态：已启用**（首次上线 2026-10-11，文件位置 `.github/workflows/android.yml`）。

---

## 1. 它做什么

| 事件 | 作业 | 产物 |
|---|---|---|
| 任意分支 push / PR / 手动触发 | `debug` | `app-debug-apk`（artifact，保留 14 天） |
| 推 tag `vX.Y.Z` | `release` | 签名 APK + `.sha256`（artifact 90 天），并自动创建 GitHub Release |

## 2. 首次启用时踩的坑（沿革，留着以后别再踩）

通过 GitHub API 写入 `.github/workflows/**` 的凭据**必须具备 `workflow` 权限**，
否则返回：

```
403 Resource not accessible by personal access token
```

区块 1 的骨架 PR 阶段就撞上了这个错误（已用两个不同路径验证，不是文件内容问题），
当时把工作流先放在 `ci/android.yml`。用户给 token 加上 `workflow` 权限后，
已由 PR `chore/01b-enable-ci` 移入正式位置并删除旧副本。

> 结论：**以后不要往 `ci/` 放工作流副本**，直接写 `.github/workflows/`；
> 如果又报 403，就是 token 的 `workflow` 权限又没了。

## 3. 发 Release 的前置条件

必须先配好 4 个 Secrets（见 [BUILD.md](BUILD.md) §3），否则 `release` 作业会主动失败。
这是故意的：宁可失败，也不发出一个「装不上的未签名包」。

| Secret | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 clubapp-release.jks` 的输出 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | `clubapp` |
| `ANDROID_KEY_PASSWORD` | key 口令 |

另外，tag 必须与 `version.properties` 的 `versionName` 一致（工作流会校验并在不一致时报错）。

## 4. 其他约定

- 本仓库**不提交 `gradle-wrapper.jar`**，CI 用 `gradle/actions/setup-gradle` 安装 Gradle 8.13；
- 同一个 ref 重复推送会取消上一次未跑完的任务（`concurrency`），节约额度；
- 如果构建失败，先看 “构建 debug APK” 这一步的报错；常见原因见 [BUILD.md](BUILD.md) §7。
