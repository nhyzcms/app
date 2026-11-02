# 区块 9 报告 · 测试与验收

日期：2026-11-02
仓库：`nhyzcms/app`
分支：`feat/09-test-and-acceptance`
状态：**代码 + 文档完成，待 CI 首轮与真机按清单回归**

---

## 1. 这一块在做什么

ROADMAP 区块 9 的验收标准只有一句：**后续改动不能把可用功能搞坏。**
到本块开工时的实际情况是"测试很多，但覆盖不到最贵的那类错误"：

| 现象 | 数量 | 说明 |
|---|---|---|
| 纯函数单测 | 29 个文件 / 269 个用例 | 导航裁决、manifest 解析、sha256、内容探测、推送协议……覆盖得很好 |
| **壳级测试** | **0** | 没有任何一条用例把 `MainActivity` 跑起来 |
| 静态预检 | 6 个 `preflight*.sh` | 但三条历史红线里只有"浮层自动撤销"被写成规则 |
| 人工回归清单 | 散在 8 份 CHUNK 报告的第 5 节 | 想跑一遍得翻 8 个文件，没法勾选 |
| 发布前检查表 | 散在 CHUNK-07 | 同上 |

于是区块 1 的三起真机故障（启动闪退、黑/白屏、证书链）都有一个共同点：
**单测全绿、CI 全绿、真机不可用**。本块就是把这类"接线错误"变成能自动跑或能照着走的检查。

---

## 2. 交付物

| # | 交付物 | 文件 | 说明 |
|---|---|---|---|
| 1 | **壳级启动测试**（8 个用例） | 新增 `app/src/test/java/com/nhyzcms/clubapp/MainActivityShellTest.kt` | Robolectric 在 JVM 上跑真实 Activity：安全配置、骨架撤除、错误分类、返回键裁决 |
| 2 | **三红线预检** | 新增 `scripts/preflight-regress.sh` | 后台线程不许碰 WebView / 浮层必须有撤除路径 / 兜底必须纯计时 |
| 3 | **一条命令验证** | 新增 `scripts/verify.sh`（`--static` / `--unit` / 全跑） | 本地与 CI 共用；CI 两个作业都改成调它 |
| 4 | **人工回归清单** | 新增 `docs/REGRESSION.md` | A~H 八组，对照 FEATURE-MATRIX 逐行，每条"步骤 → 预期" |
| 5 | **发布前检查表** | 新增 `docs/RELEASE-CHECKLIST.md` | 试跑 Release、签名闸门、sha256 回读、回滚预案 |
| 6 | 构建配置 | `app/build.gradle.kts` | `testOptions.unitTests.isIncludeAndroidResources = true`；`testImplementation("org.robolectric:robolectric:4.14.1")` |
| 7 | CI 接线 | `.github/workflows/android.yml` | 两处 `preflight.sh` + `preflight-feel.sh` → `verify.sh --static`；两处单测 → `verify.sh --unit` |
| 8 | 文档同步 | `README.md`、`docs/ROADMAP.md` | 状态表、文档索引、区块进度 |

**新增运行期依赖：0**；**新增权限：0**；**数据库变更：0**；**APK 体积变化：0**（Robolectric 只在测试类路径）。

---

## 3. 关键决策

### 3.1 为什么用 Robolectric，而不是 `androidTest` + 模拟器

ROADMAP 原文写的是"一个最小仪器测试（启动 → 加载 → 错误页）"。落地时选择在 JVM 上跑，
理由按重要性排序：

1. **本项目没有本地 Android 构建环境**（服务器 2 GB 内存、无 JDK/SDK）。任何 Gradle 验证都只能走 CI，
   而模拟器作业一轮 ~10 分钟、还要 `ReactiveCircus/android-emulator-runner` 这类第三方 action，
   失败时只能靠日志猜 —— 与本项目"用最少的失败轮次换反馈"的原则冲突。
2. **要验的不是 Chromium**。三起事故分别是"后台线程读 `settings`"、"浮层没人撤"、
   "兜底挂在 `onPageFinished` 上"，全部发生在**壳自己的接线**里。Robolectric 能跑真实
   `Activity` 生命周期、真实布局 inflate、真实 `WebViewClient` 回调，足以覆盖。
3. **真实 WebView 的网络/TLS/渲染本来就只能靠人工**（R46 根证书问题只能在老设备复现），
   这部分已经写进 `docs/REGRESSION.md` 的 A8，不再假装自动化能替代。

代价与边界写在测试类的 KDoc 里：真实网络、TLS 握手、相机、文件选择器、推送连接**不覆盖**。

### 3.2 为什么 `MainActivityShellTest` 只挑 8 个点

不是"能断言就断言"，而是**只钉历史事故与安全红线**：

| 用例 | 钉住的事故/红线 |
|---|---|
| `coldStart_webViewSecuritySettingsFollowsSecurityDoc` | 文件访问必须关、UA 必须带壳标记（区块 8 的版本闸门依赖它） |
| `coldStart_showsSkeletonButNotErrorPanel` | 冷启动顺序：先骨架、后 load（区块 1 白闪） |
| `mainFrameNetworkError_*` / `mainFrameHttp429_*` | 主文档失败要有原生错误页（2026-10 限流导致的空白页） |
| `subResourceError_doesNotShowPanel` | 子资源失败不许打断整页 |
| `sslError_neverProceedsAndShowsTlsText` | `onReceivedSslError` 只许 cancel，绝不 proceed（安全红线） |
| `pageFinished_hidesProgressAndRemovesSkeleton` | 浮层必须撤（区块 1 黑屏） |
| `backPress_whileErrorPanelVisible_*` | 错误面板盖着时返回先收面板（区块 4） |

### 3.3 为什么三红线写成"定点断言"而不是通用静态分析

项目里没有 detekt/ktlint 之类的工具链，引进来要改 CI、要维护规则版本。
而这三条红线的"违规形态"非常固定（后台模块里出现 `webView` 标识符、
`hideSkeleton` 里不再有 `View.GONE`、`hardWatchdog` 里出现 `onPageFinished`），
用 30 行正则 + 精确切片就够，误报时一眼能看懂。**每条规则都做了反向注入**（见 §4.2）。

---

## 4. 验证记录

### 4.1 本地（无 JDK / 无 Android SDK，只能跑静态部分）

```
$ bash scripts/verify.sh --static
[预检] 全部通过 ✅
--- 连带预检：scripts/preflight-perf.sh ---        全部通过 ✅
--- 连带预检：scripts/preflight-push.sh ---        全部通过 ✅
--- 连带预检：scripts/preflight-regress.sh ---     三条红线规则全部通过 ✅
--- 连带预检：scripts/preflight-scan.sh ---        全部通过 ✅
--- 连带预检：scripts/preflight-security.sh ---    全部规则通过
--- 连带预检：scripts/preflight-signing.sh ---     全部通过 ✅
[verify] 全部通过 ✅（模式：static）
```

### 4.2 反向注入（证明规则真的会报错，3/3 命中）

| 注入 | 手段 | 结果 |
|---|---|---|
| ① 后台模块碰 WebView | 往 `shell/push/PushSocket.kt` 追加 `private val leak = webView` | 规则命中（1 条失败） |
| ② 删掉纯计时兜底 | 注释掉 `revealHandler.postDelayed(hardWatchdog, …)` | 规则命中（1 条失败） |
| ③ 骨架不再撤除 | 把 `skeleton.visibility = View.GONE` 改成 `VISIBLE` | 规则命中（1 条失败） |

三次注入后均已恢复原文件，复跑全绿。

### 4.3 CI（2026-11-02 已跑通）

`debug` 与 `release` 作业都改成 `scripts/verify.sh`。**首次接入共红了 3 轮**，都是 compileSdk 35
的 SDK stub 约束，记录在此避免重犯：

| 轮次 | 报错 | 根因 | 修法 |
|---|---|---|---|
| 1 | `Cannot access 'constructor(): SslErrorHandler': it is package-private` | `SslErrorHandler` / `SslError` / `WebResourceError` 在 SDK 里构造函数不可见，匿名子类写不出来 | 改用 Mockito mock（只进测试类路径），顺带断言 `handler.proceed()` 一次都没调 |
| 1 | `emptyMap()` 推断不出 `Map<String, String>` | Kotlin 类型推断 | 显式 `emptyMap<String, String>()` |
| 2 | `None of the following candidates is applicable` | `WebResourceResponse` **没有**「5 参 + headers」重载，只有 3 参与 6 参（带 `InputStream`） | 补 `ByteArrayInputStream(ByteArray(0))` |

**第 3 轮通过**：`Debug APK` 58 秒内跑完 `verify.sh --static` + `:app:testDebugUnitTest` +
`assembleDebug`（单测 54 s，其中 8 个 Robolectric 用例各启动一次真实 Activity）。

教训写进 `PITFALLS.md` 的口径：**Android SDK 的抽象类不一定能匿名实现**（构造函数可能是
package-private），单元测试里遇到这类参数优先 mock，不要跟 SDK stub 较劲。

---

## 5. 验收步骤（需要管理员做）

1. **看 CI**：本 PR 的 Debug APK 作业必须绿（它现在包含 8 个壳级用例）。
2. **跑一遍清单**：按 [`docs/REGRESSION.md`](../REGRESSION.md) 的 A 组（启动与基础）+ B 组（入口可达）。
   其他组按本次改动范围选跑；`D/E/F` 组的完整回归留到下一版发版前。
3. **本地一条命令**（有 JDK 17 + Android SDK 的机器）：

   ```bash
   bash scripts/verify.sh          # 预检 + 单测
   bash scripts/verify.sh --static # 只跑预检（5~20 秒）
   ```

4. 若发现清单里有条目"步骤写错/预期不对"，直接改 `REGRESSION.md` 并在 PR 里说一声 ——
   清单的价值在于可执行，不在于好看。

---

## 6. 红线与已知限制

- **不要在服务器上跑 `verify.sh` 的 `--unit` 模式**：2 GB 内存跑不动 Android 构建，CI 才是唯一验证点。
- Robolectric **不覆盖**真实网络与 TLS；证书链（R46）问题只能在老设备复现，见 `FIX-20261012-CERT-R46.md`。
- 新增的 `preflight-regress.sh` 依赖文件路径与函数名（`hideSkeleton` / `hardWatchdog` / `revealTick`）。
  **重命名这些符号时会连带报错** —— 这是刻意的：重命名可以，但要同步改规则并在报告里说明。
- `verify.sh --unit` 在找不到 `gradle` 时默认"跳过并提示"，只有 `VERIFY_REQUIRE_GRADLE=1` 才判失败。
  CI 已设置该变量，避免出现"以为跑了其实没跑"的假绿。

---

## 7. 与 ROADMAP 原文的偏差

| 原文 | 实际 | 原因 |
|---|---|---|
| "一个最小仪器测试" | Robolectric 的 8 个壳级用例 | §3.1：无本地 SDK、模拟器成本高、要验的是接线 |
| "单元测试（导航裁决 / manifest / 版本对比 / sha256 / 内容探测）" | 已有 269 个用例 + 新增 8 个壳级用例 | 前五类在区块 2~8 已覆盖 |
| "本地/CI 均可一条命令跑完" | `scripts/verify.sh` | 本地无 JDK 时只跑静态，并明确提示"未跑单测" |
| "清单里每条都有操作步骤 + 预期结果" | `REGRESSION.md` A~H 八组 | 已按 FEATURE-MATRIX 逐行对照 |
