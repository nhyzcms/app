# CI 工作流：为何没在 `.github/workflows/` 里，以及如何启用

## 1. 现状（区块 1 结束时）

工作流内容已写好在 **[`ci/android.yml`](../ci/android.yml)**，但**尚未生效**，因为它不在
`.github/workflows/` 目录下。

原因：GitHub 的安全限制 —— 通过 API 写入 `.github/workflows/**` 的凭据必须具有 `workflow` 权限。
当前助手使用的 GitHub token 不具备该权限，API 直接返回：

```
403 Resource not accessible by personal access token
```

（已用 `ci/` 下的文件与 `.github/workflows/probe.yml` 两次验证，均为同一错误，不是文件内容问题。）

## 2. 启用方式（二选一）

### 方式 A：给 token 加 `workflow` 权限（推荐）

1. GitHub → Settings → Developer settings → Personal access tokens → 找到助手使用的那枚 token；
2. 勾选 **`workflow`** 权限（细粒度 token 则为 `Workflows: Read and write`）；
3. 告诉一声助手，由它执行“把 `ci/android.yml` 移到 `.github/workflows/android.yml`”这个提交。

### 方式 B：手动在网页上创建（不需要改 token）

1. 打开 <https://github.com/nhyzcms/app/new/main>（或 PR 分支）：
   Add file → **Create new file**；
2. 文件名填 `.github/workflows/android.yml`；
3. 把 `ci/android.yml` 的全部内容粘进去 → Commit。

> 也可以用命令行：
> ```bash
> git checkout feat/01-shell-skeleton
> mkdir -p .github/workflows
> git mv ci/android.yml .github/workflows/android.yml
> git commit -m "ci: 启用 Android 构建工作流" && git push
> ```

启用后执行一次 push 就会自动跑，也可以在 Actions 页面点 **Run workflow** 手动触发。

## 3. 启用后会发生什么

| 事件 | 作业 | 产物 |
|---|---|---|
| 任意分支 push / PR | `debug` | `app-debug-apk`（artifact，14 天）|
| 推 tag `vX.Y.Z` | `release` | 签名 APK + `.sha256`，并自动建 GitHub Release |

## 4. 注意

- 发 Release **必须**先配好 4 个 Secrets（见 [BUILD.md](BUILD.md) §3），否则 `release` 作业会主动失败；
  这是故意的：宁可失败，也不发出一个“装不上的未签名包”。
- tag 必须与 `version.properties` 的 `versionName` 一致（工作流会校验并在不一致时报错）。
- 本仓库不提交 `gradle-wrapper.jar`，CI 用 `gradle/actions/setup-gradle` 安装 Gradle 8.13。
- 第一次跑 CI 前，请先确保 `.github/workflows/android.yml` 已在首个 PR 合并后存在于 `main`。
