# 修复：一进 App 就闪退（2026-10-12）

> 状态：已修复（本 PR）。
> 现象：真机（HUAWEI P40 Pro+ / HarmonyOS 4.2 = Android 12 / API 31）安装 debug 包后，
> 点图标进 App 约 1 秒即闪退，重复复现，100% 必现。

## 1. 根因（不是猜测，有据可查）

**壳在后台线程读了 WebView，被 Android 运行时当场抛异常，异常没人接住 → 进程被杀。**

证据链：

1. AOSP `WebView.java` 构造函数里：
   `sEnforceThreadChecking = context.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.JELLY_BEAN_MR2;`
   —— 只要 targetSdk ≥ 18（本工程是 34），**线程检查就是“抛异常”而不是“打日志”**：

   ```java
   private void checkThread() {
       if (mWebViewThread != null && Looper.myLooper() != mWebViewThread) {
           Throwable throwable = new Throwable(
                   "A WebView method was called on thread '" + Thread.currentThread().getName() + "'...");
           Log.w(LOGTAG, Log.getStackTraceString(throwable));
           StrictMode.onWebViewMethodCalledOnWrongThread(throwable);
           if (sEnforceThreadChecking) {
               throw new RuntimeException(throwable);   // ← 命中就是这里
           }
       }
   }
   ```

2. `WebView.getSettings()` 第一行就 `checkThread()`；`WebView.settings.userAgentString`
   （Kotlin 属性语法）因此等价于一次“必须在主线程”的调用。

3. 我们自己的调用链（修复前）：

   ```
   MainActivity.onResume()
     └─ updates.checkIfDue()                       // 首次安装 lastCheckAtMs=0，必定进入
          └─ checkNow()
               └─ thread("clubapp-update-check") { fetcher.fetch(...) }
                    └─ fetcher 是 by lazy → **在后台线程初始化**
                         └─ userAgentProvider() = { webView.settings.userAgentString }
                              └─ WebView.getSettings() → checkThread() → RuntimeException
                                   └─ 后台线程未捕获 → 进程被杀（闪退）
   ```

   同一处还有第二个触发点：`onShellReady()` 在冷启动后 3 秒也会走同一条路 ——
   所以“等一会儿才崩”的解释是：`onResume()` 那次先崩，用户看到的就是“点进去一秒就闪退”。

4. 时间线吻合：`onResume()` 紧跟 `onCreate()`，异常在毫秒内抛出；用户感知为“刚进去就没了”。
   且因为 `lastCheckAtMs` 只在“成功拉到清单”后才落盘，而线程在成功前就死了 →
   **每次启动都必崩**，与“重复测试依旧复现”一致。

5. 为什么 CI 没抓到：单元测试是纯 JVM（没有 WebView），真机行为不在 CI 覆盖范围内；
   静态检查也只能查“模式”，查不到“线程”。这次把教训固化进了代码注释与本文档。

## 2. 修复内容

### 2.1 主修：UA 在主线程取好再进后台线程（`shell/update/UpdateController.kt`）

```kotlin
val userAgent = userAgentOnMainThread()          // ← 主线程取（新增的安全入口）
thread(name = "clubapp-update-check") {
    val text = ManifestFetcher(userAgent, AppConfig.UPDATE_ALLOWED_HOSTS).fetch(...)
```

同时删掉 `fetcher` 的 `by lazy`（它正是“在后台线程里初始化”的元凶），
并把所有 UA 获取收口到新增的 `userAgentOnMainThread()`：
不在主线程时**退回 null（本次请求不带 App UA 标记）而不是抛异常**，
从设计上保证“即使将来有人在后台线程调，也只是丢一个请求头，不会杀进程”。

### 2.2 加装：崩溃自记录（下次启动弹出来，可复制/分享）

| 文件 | 作用 |
| --- | --- |
| `shell/CrashText.kt` | 纯逻辑：把异常排版成“时间/版本/设备/线程/异常/堆栈”（JVM 单测覆盖） |
| `shell/CrashReporter.kt` | 接管未捕获异常 → 落盘；下次启动首个 Activity 前台时弹出（复制/分享/知道了） |
| `ClubApp.kt` | `Application`，只做一件事：装上上面两个（清单里 `android:name=".ClubApp"`） |

落盘位置：`filesDir/crash/last-crash.txt`，另存一份到 `getExternalFilesDir(null)/last-crash.txt`
（用户能用系统「文件管理」直接找到并发出来）。不采集账号、不采集页面内容，也不上传服务器。

### 2.3 为什么值得加崩溃自记录

这次排查暴露出一个现实约束：**用户端只有一台手机** ——
普通应用读不到 logcat（无 `READ_LOGS`），没开无线调试也用不了 adb，
系统“应用已停止运行”的弹窗一闪而过抄不下来。
所以下一次任何闪退，用户点一下“分享日志”就能把堆栈发回来，不用再靠读代码反推。

## 3. 验证

- 本地：`bash scripts/preflight.sh`、`python3 /workspace/ktcheck.py`（括号/字符串/注释配平）、
  `bash /workspace/klocaltest.sh`（真实 Kotlin 编译 + JVM 单测，含新增 `CrashTextTest`）。
- CI：`preflight` + `:app:testDebugUnitTest` + `:app:assembleDebug`。
- 真机：安装本 PR 构建的 debug 包 → 冷启动进 App，**不再闪退**；
  若仍有闪退，下次启动会看到“上次运行崩了”弹窗，把日志发回即可。

## 4. 教训（红线）

1. **WebView 的所有方法只能在主线程调用**；`webView.settings.*` 也算（它会 `checkThread()`）。
   后台线程需要 UA/配置时：**在主线程取快照，把值带进去**，不要把“读 WebView”这件事本身带进线程。
2. 后台线程里**任何未捕获异常都会杀掉整个进程**（不只是那个线程）。
   因此“线程入口处能出错的东西”要么主线程做完，要么 `runCatching` 兜住。
3. 凡是“只在真机上才可能出现”的行为（线程、权限、ROM 差异），CI 抓不到 —— 交付前必须真机过一遍。
