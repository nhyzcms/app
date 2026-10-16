# CI 工作流说明

**状态：已启用**（首次上线 2026-10-11，文件位置 `.github/workflows/android.yml`）。
本文是 CI 的"使用 + 排障"手册：先在 §1/§2 搞清它做什么、为什么这么排；失败时直接跳到 §7/§8。

---

## 1. 它做什么（总览）

| 事件 | 作业 | 步骤顺序 | 产物 |
|---|---|---|---|
| 任意分支 push / PR / 手动触发 | `debug` | 预检 → 单测 → `assembleDebug` | `app-debug-apk`（artifact，保留 14 天） |
| 推 tag `vX.Y.Z` | `release` | 预检 → 单测 → **签名闸门（Secrets 齐备 + 指纹一致）** → `assembleRelease` | 签名 APK + `.sha256`（artifact 90 天）+ GitHub Release |
| 手动触发（非 tag） | `verify-signing` | 解码 Secrets → 比对证书指纹 | 不发包；只回答"Secrets 配对了没"（见 §5.2） |

两个"先把关"的步骤，都是为了**不浪费构建周期**：

1. `scripts/preflight.sh`：静态预检，**约 5 秒**，只需要 `python3`。用 grep/正则拦下 XML 语法与注释、
   Kotlin `R.*` 资源引用必须真实存在、权限声明形状、安全红线（禁 `addJavascriptInterface`、
   禁 `onReceivedSslError` 里 `proceed()` 等）。
2. `bash scripts/preflight-feel.sh`：区块 4 起的专项预检，会**连带执行同目录下所有 `preflight-*.sh`**
   （当前包含 `preflight-perf.sh`、`preflight-push.sh`、`preflight-signing.sh`），同样不需要 JDK/SDK。
   → **新增专项预检只要命名成 `scripts/preflight-*.sh` 就会自动被 CI 跑到**，不必改工作流。
3. `gradle :app:testDebugUnitTest`：区块 2 起的纯逻辑单测（不需要设备）。
   * `debug` 作业把单测放在 APK 之前：测试挂了就不必再花时间打包，反馈更快；
   * `release` 作业把单测当作**发版阻断**：测试不过就不发版。

> 为什么这么在意"早失败"：本项目**本地没有 Android SDK**，如果靠"跑一遍 CI 才知道错在哪"，
> 一次小错误就要白白消耗一个完整构建周期（约 2 分钟）外加一次红 run。预检 5 秒就能拦下的错，
> 不该让它走到 Gradle。

---

## 2. 触发策略与并发

```yaml
on:
  push:
    branches: ["**"]      # 任意分支
    tags: ["v*"]          # 版本 tag
  pull_request:
  workflow_dispatch:      # 便于手动重跑

permissions:
  contents: write         # 创建 Release
  pull-requests: write    # 失败时把日志贴成 PR 评论

concurrency:
  group: android-${{ github.ref }}
  cancel-in-progress: true
```

- `concurrency` + `cancel-in-progress`：**同一个 ref 重复推送会取消上一次未跑完的任务**，
  节省额度，也避免"旧提交的结果盖住新提交"。
- `debug` 作业带 `if: ${{ !startsWith(github.ref, 'refs/tags/v') }}`：
  推 tag 时不会同时跑 debug，只有 `release`。
- 手动重跑：Actions 页面 → 选中工作流 → `Run workflow`；或用 API：
  `gh api -X POST repos/nhyzcms/app/actions/workflows/android.yml/dispatches -f ref=main`。

---

## 3. 作业逐步拆解（`debug`）

对齐 `.github/workflows/android.yml` 的真实步骤：

| # | 步骤 | 命令 / action | 说明 |
|---|---|---|---|
| 1 | 取代码 | `actions/checkout@v4` | |
| 2 | 静态预检 | `bash scripts/preflight.sh` | 5 秒，失败即停 |
| 3 | 专项预检 | `bash scripts/preflight-feel.sh` | 连带 `preflight-*.sh` |
| 4 | 装 JDK | `actions/setup-java@v5`（temurin 17） | 不能用 v4 以下的旧版 |
| 5 | 准备 Android SDK | `bash scripts/ci-prepare-android-sdk.sh` | **自写脚本**，见 §4 |
| 6 | 装 Gradle | `gradle/actions/setup-gradle@v4`（8.13） | 仓库不提交 wrapper jar |
| 7 | 打印环境 | `cat version.properties` | 便于对齐"跑的是哪个版本" |
| 8 | 纯逻辑单测 | `gradle :app:testDebugUnitTest --stacktrace` | 输出 tee 到 `unit-test.log` |
| 9 | 建 debug APK | `gradle :app:assembleDebug --stacktrace` | 输出 tee 到 `build.log` |
| 10 | 失败贴日志 | `if: failure()` → `scripts/ci-extract-errors.sh` | 贴成 PR 评论，见 §7 |

几个容易忽略的细节：

- **`set +e` + `tee` + `${PIPESTATUS[0]}`**:管道会让退出码变成最后一个命令的，必须用 PIPESTATUS 取 Gradle 的真实退出码，
  否则"构建失败但作业仍绿"。当前 yml 就是这么写的。
- **`runs-on: ubuntu-24.04`**：固定版本，不要改回 `ubuntu-latest`（见 §6）。
- **`timeout-minutes: 30`**：防止卡死占额度。

---

## 4. 为什么不用 `android-actions/setup-android`

首次真实编译踩过：`android-actions/setup-android@v3` **8 秒就失败**（在那个 runner 镜像上跑不通），
`setup-android` 内部会调用 `sdkmanager --licenses`，而该命令在 runner 上是交互式的、**必然失败**。

替代方案（现在的做法，见 `scripts/ci-prepare-android-sdk.sh`）：

```bash
# 1) 用 runner 预装的 SDK（ANDROID_HOME / ANDROID_SDK_ROOT 一般已存在）
# 2) 直接把许可证写进去，绕过 sdkmanager --licenses
mkdir -p "$SDK/licenses"
printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee' > "$SDK/licenses/android-sdk-license"
printf '\n84831b9409646a918e30573bab4c9c91346d8abd' > "$SDK/licenses/android-sdk-preview-license"
# 3) 只装必需组件（platform-tools / platforms;android-35 / build-tools）
```

要点：**许可证文件直接写**，不要试图让 `sdkmanager` 去"接受"。

---

## 5. 权限、Secrets 与发版前置条件

**状态（2026-10-17）：4 个 Secrets 已配置，指纹闸门已在工作流里。**
首次配置 / 迁移 / 备份 / 轮换的完整流程见 [BUILD.md](BUILD.md) §3。

`release` 作业必须先配好 4 个 Secrets，否则它会**主动失败**。
这是故意的：**宁可失败，也不发出一个"装不上的未签名包"**。

| Secret | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 nhyzcms-release.jks` 的输出（单行） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | `clubapp` |
| `ANDROID_KEY_PASSWORD` | key 口令（PKCS12 下与 store 口令相同） |

### 5.1 三道发版闸门

`release` 作业依次卡三道，任一不过就不发包：

1. **Secrets 齐备**：解码 `ANDROID_KEYSTORE_BASE64` 前先判空，缺了直接 `exit 1`；
2. **构建前 · 指纹一致**：`scripts/verify-keystore.sh` 断言解码出来的证书 SHA-256 指纹
   等于 `deploy/signing-fingerprint.txt` 的登记值（并校验 alias 与证书未过期）；
3. **构建后 · 产物一致**：`apksigner verify --print-certs` 校验打出来的
   `app-release.apk` 的签名证书等于登记值 —— 第 2 道验的是"钥匙对不对"，
   第 3 道验的是"这个包确实是那把钥匙签的"（本地没有 Android SDK，这一道只能在 CI 做）。

第 2、3 道防的是**用错密钥发版**：那种包构建一样成功、Release 一样发出，
但已装用户会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，而且一旦有人装了新包，
退回旧密钥也覆盖不了 —— 几乎不可回滚。把比对前置到构建之前、并在构建之后再验一次产物，
就把它挡在源头、也挡在出口。

### 5.2 手动触发的 `verify-signing` 作业

`workflow_dispatch`（且不是 tag）会额外跑一个 `verify-signing` 作业：
只做「解码 Secrets → 比对指纹」，**不构建、不发包**，约 20 秒。

```bash
gh workflow run android.yml --repo nhyzcms/app --ref main
gh run list --repo nhyzcms/app --limit 3
```

用途：① 首次配完 Secrets 的验证；② 每次发版前的 30 秒体检；③ 换人维护时的交接自检。
Step Summary 会打印登记的别名、指纹与有效期。

> `gh secret list --repo nhyzcms/app` 只能确认 4 个 Secret **存在**；
> 「内容对不对」只有 `verify-signing` 能回答（Secret 值写入后无法读回，这是设计）。

### 5.3 试跑 Release（`dry_run_release`）—— 别再用 tag 去试

`release` 作业原本**只在 tag 上跑**，于是“只有发版才暴露”的问题只能靠发版去试。
2026-10-17 首个 tag `v0.1.0` 就是这么红的（Gradle 签名配置自赋值，见
[FIX-20261017](FIX-20261017-SIGNING-SELF-ASSIGN.md)）。现在可以试跑：

```bash
gh workflow run android.yml --repo nhyzcms/app --ref <分支或 main> -f dry_run_release=true
```

试跑会完整走一遍 release 路径（预检 → 单测 → keystore 指纹闸门 → `assembleRelease` →
产物 `apksigner` 校验 → 上传 artifact），只**跳过「创建 GitHub Release」**；
非 tag 时会跳过 tag 与 `versionName` 的一致性校验（按 `version.properties` 构建）。

> 什么时候用：改动了 `app/build.gradle.kts`、`proguard-rules.pro`、依赖版本、
> 签名相关脚本之后，打 tag 之前先跑一次。约 4~6 分钟，比"红一个 tag"便宜得多。

### 5.4 另外两条硬约束

- tag 必须与 `version.properties` 的 `versionName` 一致（工作流会校验，不一致直接报错）；
- 更新机制会比对 APK 的签名证书（见 `docs/UPDATE-STRATEGY.md`），
  所以**发布包必须始终用同一把 release key**，否则老用户升不上去。

> 关于"写入 `.github/workflows/**` 需要 token 具备 `workflow` 权限"这件事的沿革见 §6 第 1 条。

---

## 6. 历史沿革：首次启用与首次编译踩的坑

### 6.1 写入工作流需要 `workflow` 权限

通过 GitHub API 写入 `.github/workflows/**` 的凭据**必须具备 `workflow` 权限**，否则返回：

```
403 Resource not accessible by personal access token
```

区块 1 的骨架 PR 阶段就撞上了这个错误（当时已用两个不同路径验证，不是文件内容问题），
于是把工作流先放在 `ci/android.yml`；用户给 token 加上 `workflow` 权限后，
已由 PR `chore/01b-enable-ci` 移入正式位置并删除旧副本。

> **结论：以后不要往 `ci/` 放工作流副本**，直接写 `.github/workflows/`。
> 如果又报 403，就是 token 的 `workflow` 权限又没了。

### 6.2 首次真实编译失败过的 7 个坑（均已修复，别再踩）

1. `android-actions/setup-android@v3` 直接失败 → 改自写脚本（§4）；
2. `gradle :app:versionInfo` 任务不存在（`versionInfo` 注册在**根项目**，不是 `:app`）→ 改为纯 shell 打印 `version.properties`；
3. runner 默认 `ubuntu-latest` 将于 2026-10-19 迁到 Ubuntu 26 → 已固定 `ubuntu-24.04`；
4. `sdkmanager --licenses` 在 runner 上必然失败 → 改为直接写 licenses 文件；
5. **XML 注释里不能出现连续两个短横线**（AAPT 直接中断构建；这条踩过两次，第二次是"把报错原文照抄进注释"）；
6. `setWebViewClient(null)` 在 API 35 上编译不过（参数是 `@NonNull`）→ 清理时要传空 `WebViewClient()`/`WebChromeClient()`；
7. `WebSettings` 的 safe browsing 只有 setter 没有 getter → 用 `setSafeBrowsingEnabled(true)`，不能写属性语法。

区块 6 又追加一条：**Kotlin 属性在自身初始化块里自引用**会报
`Type checking has run into a recursive problem.`（症状与修法见 [PITFALLS.md](PITFALLS.md) §1.4）。

这些坑现在都固化进了 `scripts/preflight*.sh`：能静态拦下的，不再让它走到 CI。

---

## 7. 失败了怎么读报错（重要）

Actions 的**原始日志需要仓库管理员权限**才能通过 API 读（助手侧通常是 403），
Step Summary / annotations 也拿不到完整报错。**PR 评论才是可读通道。** 因此：

- `debug` 作业失败时，`scripts/ci-extract-errors.sh` 会把 `unit-test.log` 与 `build.log` 的
  关键报错一起贴成 **PR 评论**（含 `FAILED` / `AssertionError` / `expected:<` / `error:` / `Unresolved reference` 等行）；
- 在非 PR 触发（例如直接 push 或手动跑）时，日志只输出到控制台，需要在网页上展开。

读评论的命令：

```bash
# 取 PR 评论列表（注意：该接口不支持 sort/direction 参数）
gh api repos/nhyzcms/app/issues/<PR_NUMBER>/comments --jq '.[-1].body'

# 只要最近新增的评论（避免翻历史）
gh api "repos/nhyzcms/app/issues/<PR_NUMBER>/comments?since=2026-10-14T00:00:00Z" --jq '.[].body'
```

排障提示：单条评论正文较长时，某些读取通道（如网页抓取）会有约 9~10 KB 的截断，
所以 `ci-extract-errors.sh` 的输出必须**精简**（只留关键报错行），不要把整份日志倒进去。

读红 run 的推荐顺序：

1. 先看是**哪个步骤**红的（预检？单测？打包？贴日志？）；
2. 预检红 → 直接按报错里的文件名/行号本地改，跑 `bash scripts/preflight.sh` 复现；
3. 单测红 → 看 `AssertionError` 与期望/实际值；多数是纯逻辑断言，本地 `bash /workspace/klocaltest.sh` 能复现；
4. 打包红 → 看 `Unresolved reference` / `Type mismatch` / 资源名错误；本地 `bash /workspace/kandroidcheck.sh` 多数能复现；
5. **一次改完所有已知问题再推**，不要"改一条推一条"。

---

## 8. 失败分类与处置速查

| 症状 / 报错片段 | 常见根因 | 处置 |
|---|---|---|
| `AAPT: error: ... --` / 构建在解析 XML 时中断 | XML 注释里出现连续两个短横线 | 改注释；跑 `preflight.sh` 复现 |
| `Unresolved reference: R.drawable.xxx` | 引用不存在的资源 | 改正资源名或补资源；`preflight.sh` 会拦 |
| `Unresolved reference: SomeClass` | 漏 import | 补 import；`kandroidcheck.sh` 多数能复现 |
| `Type checking has run into a recursive problem` | Kotlin 属性在自身初始化块里自引用 | 进 lambda 前先 `val self: Runnable = this`（见 PITFALLS §1.4） |
| `AssertionError at XxxTest.kt:NN` | 单测断言失败 | 看期望/实际；`klocaltest.sh` 本地复现 |
| `FAILED` + `expected:<...> but was:<...>` | 纯逻辑行为变了 | 判断是"实现错了"还是"测试该更新"，改对的一方 |
| `No such file or directory: gradle/wrapper/...` | 误加了 wrapper 相关配置 | 本仓库不提交 wrapper jar，用 setup-gradle |
| `Resource not accessible by personal access token` | token 缺 `workflow` 权限 | 让用户补权限；不要往 `ci/` 放副本 |
| `release` 作业在签名步骤失败 | 缺 Secrets / tag 与 versionName 不一致 | 按 §5 配齐；tag 用 `vX.Y.Z` 且与 `version.properties` 对齐 |
| 构建超时 / runner 卡住 | 依赖下载或 Gradle daemon 卡 | 重跑；必要时清缓存（`gradle/actions/setup-gradle` 的 cache） |

---

## 9. 本地能做的等价验证（推送前必跑）

CI 里跑什么，本地就能挡住一大半：

```bash
bash scripts/preflight.sh          # 与 CI 第 2 步完全一致
bash scripts/preflight-feel.sh     # 与 CI 第 3 步一致（连带 preflight-*.sh）
python3 /workspace/ktcheck.py .    # Kotlin 结构配平
bash /workspace/klocaltest.sh      # 纯逻辑单测（kotlinc + JDK + JUnit，即 CI 第 8 步的本地版）
bash /workspace/kandroidcheck.sh   # stub 编译 Android 依赖层（近似 CI 第 9 步的编译期检查）
```

注意：`klocaltest.sh` / `kandroidcheck.sh` 有**文件白名单**，新增文件要手工加进去，否则会"跑了但没覆盖"。
stub 只覆盖实际用到的 API 子集，**CI 仍是最终判据**。

---

## 10. 推送纪律（血泪版）

1. **每批单看都必须可编译、CI 必绿**：独立新文件先行，接线/既有文件最后；中间态不得引用尚不存在的符号。
   仓库历史里有一批红 run 全是"分批中间态"造成的。
2. 推送前把 §9 全跑一遍；推送后做逐字复核（`git show FETCH_HEAD:<path> | diff -`，见 [ENGINEERING-ENV.md](ENGINEERING-ENV.md) §5）。
3. 用 `GITHUB_TOKEN` 推的提交**不会触发 CI**，之后要自己再推一次（哪怕只改文档）让 CI 验证新 head。
4. PR 被用户合并后，后续提交必须**另开分支/PR**。

---

## 11. Release 与部署的衔接

1. 在 `main` 上把 `version.properties` 的 `versionCode` / `versionName` 提上去；
2. 打 tag：`git tag vX.Y.Z && git push origin vX.Y.Z`（tag 必须与 `versionName` 一致）；
3. `release` 作业产出**签名 APK + `.sha256`**，并自动创建 GitHub Release；
4. 站点侧自托管分发：把 APK 与清单放到服务器 `/app-update/`（nginx 静态 location，只允许 GET/HEAD、
   `Cache-Control: no-cache`），清单形如 `{"versionCode":..,"versionName":"..","url":"https://.../latest.apk","sha256":"..."}`；
   发布脚本见 `scripts/publish-apk.sh`、说明见 `deploy/app-update/README.md`；
5. App 侧的三级更新（L0 网站发版即生效 / L1 清单检查 / L2 APK 升级）见 [UPDATE-STRATEGY.md](UPDATE-STRATEGY.md)。

> 为什么自托管优先：站点没有域名、不能用 CDN，但**能用同一张证书提供静态文件**；
> GitHub Release 作为备用源（`ApkDownloader` 支持多源回退 + Range 续传 + 边下边算 sha256）。

---

## 12. 常用命令速查

```bash
# 看最近一次 run 的结论（需要 repo 读权限）
gh run list --repo nhyzcms/app --limit 5

# 看某个 run 的作业列表
gh run view <RUN_ID> --repo nhyzcms/app

# 手动触发工作流
gh api -X POST repos/nhyzcms/app/actions/workflows/android.yml/dispatches -f ref=<branch>

# 读取失败作业贴到 PR 的报错
gh api repos/nhyzcms/app/issues/<PR_NUMBER>/comments --jq '.[-1].body'
```

> 若 `gh run view --log` 提示权限不足，不要纠结——走 §7 的 PR 评论通道。

---

## 13. 变更记录

- 2026-10-11：初版（启用 CI、记录 403 沿革与 Secrets 要求）。
- **2026-10-14**：扩写为完整手册：补作业逐步拆解、SDK 准备脚本的做法、失败分类与处置表、
  本地等价验证链、推送纪律、Release→部署衔接、常用命令。
  同时澄清：早期因"单次写入/推送载荷约 1 万字符"而采用的临时通道**已作废**（见 [ENGINEERING-ENV.md](ENGINEERING-ENV.md) §1）。
- **2026-10-17（区块 7）**：4 个签名 Secrets 配置完成；`release` 作业加**签名指纹闸门**
  （`scripts/verify-keystore.sh` ↔ `deploy/signing-fingerprint.txt`）与**产物签名校验**（`apksigner`）；
  新增手动触发的 `verify-signing` 作业（不发包，专验 Secrets）；
  新增静态预检 `scripts/preflight-signing.sh`（由 `preflight-feel.sh` 连带执行）；
  新增 `workflow_dispatch` 输入 **`dry_run_release`（试跑 Release，不发 Release）** —— 详见 §5。
  同日首个 tag `v0.1.0` 因签名配置自赋值失败，修复见 [FIX-20261017](FIX-20261017-SIGNING-SELF-ASSIGN.md)。

<!-- EOF-SENTINEL-CI-WORKFLOW-20261017 -->
