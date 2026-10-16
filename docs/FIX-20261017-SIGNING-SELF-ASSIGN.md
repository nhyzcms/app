# FIX：首个 Release 构建失败 —— 签名配置自赋值

日期：2026-10-17
触发：首个正式 tag `v0.1.0`（区块 7）
分支：`fix/07-release-signing-config`

---

## 1. 现象

推 tag `v0.1.0` 后，`release` 作业在**第 12 步「构建 release APK」**失败，前 11 步全绿：

| 步骤 | 结果 |
|---|---|
| 静态预检（两套） | ✅ |
| 校验 tag 与 `version.properties` 一致 | ✅ |
| 纯逻辑单测（发版阻断） | ✅ |
| 准备签名 keystore | ✅ |
| **校验签名指纹（keystore 闸门）** | ✅ ← 说明 Secrets 与密钥本身都是对的 |
| **构建 release APK** | ❌ |

报错（取自 `gh run view --log-failed`）：

```
Execution failed for task ':app:packageRelease'.
Caused by: java.lang.NullPointerException:
  SigningConfig "release" is missing required property "keyPassword".
```

关键点：**指纹闸门通过**说明 keystore 与口令没错；错误发生在 Gradle 的签名配置上。

---

## 2. 定位

第一反应是核对 Secrets 是否存在/是否为空，但闸门那步已经证明 store 口令可用。
于是去看 `app/build.gradle.kts` 的签名段，发现可疑写法：

```kotlin
val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val keyAlias = System.getenv("ANDROID_KEY_ALIAS")
val keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
...
create("release") {
    storeFile = file(keystorePath!!)
    storePassword = keystorePassword   // 右边名字不同
    keyAlias = keyAlias                // 同名！
    keyPassword = keyPassword          // 同名！
}
```

**用 `kotlinc` 写最小复现（不需要 Android SDK）证实**：把 `val` 放在外层类里（模拟 `.gradle.kts`
的“脚本属性”层级），结构与构建脚本一致，运行结果：

```
storeFile     = /tmp/x.jks     ← 函数调用，正常
storePassword = STOREPASS      ← 右边名字不同 → 解析到脚本属性，正常
keyAlias      = null           ← 同名 → 右侧解析成接收者自己的属性 → 自赋值
keyPassword   = null           ← 同上
```

与 CI 报错完全对上。

> 顺带记一个反直觉点：如果把 `val` 放在**函数内**（我们第一版复现就是这么写的），
> 编译器会报 `error: 'val' cannot be reassigned`——局部变量优先于隐式接收者。
> 也就是说这个坑**只在 `.gradle.kts` 这种“val 是脚本类属性”的层级**下才会静默发生，
> 而且“一半对一半错”（`storePassword` 侥幸正常），非常难靠肉眼发现。

---

## 3. 根因

在**带接收者的 lambda**（`create("release") { ... }`）里，裸写的名字优先解析为**接收者自身的属性**。
因此 `keyPassword = keyPassword` 两侧都指向 `SigningConfig.keyPassword`，等价于没赋值，
直到 AGP 打包签名时才因 `keyPassword == null` 抛 NPE。

这个 bug 从区块 1 就存在，但**只有真正执行 `assembleRelease` 才会暴露**——
而此前项目从未发过 Release（一直是 debug 包），所以一直没被发现。

---

## 4. 修复

`app/build.gradle.kts`：

1. 环境变量变量名统一加 `env` 前缀：`envKeystorePath` / `envKeystorePassword` / `envKeyAlias` / `envKeyPassword`
   （与接收者属性名不再冲突）；
2. 赋值显式写 `this.xxx = ...`；
3. 就地写下踩坑注释（避免后人“顺手改回简洁写法”）。

`scripts/preflight-signing.sh`：新增规则——禁止签名配置里的 `key = key` 形式自赋值
（已用反向注入验证：把自赋值塞回去，预检立即报错）。

`docs/PITFALLS.md` §1.9、`docs/BUILD.md` §7 同步收录。

---

## 5. 这次还补了一个更根本的短板

原来的工作流**只能在 tag 上跑 release 构建**，所以“只有发版才暴露的问题”只能靠发版去试——
这次就是红了一个 tag 才发现的。现在加了**试跑 Release**：

```bash
gh workflow run android.yml --repo nhyzcms/app --ref <分支> -f dry_run_release=true
```

它会完整走一遍：预检 → 单测 → keystore 指纹闸门 → `assembleRelease` → 产物 `apksigner` 校验 →
上传 artifact，但**跳过「创建 GitHub Release」**。也就是说，正式打 tag 之前就能知道包能不能出来。

验证记录（分支 `fix/07-release-signing-config`，run 38934792986）：

| 步骤 | 结果 |
|---|---|
| 11 校验签名指纹 | ✅ |
| 12 构建 release APK | ✅（修复生效） |
| 14 校验产物签名证书（apksigner） | ✅（产物确实是登记那把密钥签的） |
| 16 创建 GitHub Release | ⏭ skipped（试跑模式按设计不发版） |

---

## 6. 教训

1. **“静态预检全绿”不等于“能发版”**：预检与单测都不碰 Gradle 的签名 DSL；
   凡是「只在 release 变体才走到」的路径，都要有能在非 tag 场景跑一遍的入口。
2. **同名赋值在带接收者的 lambda 里是可疑写法**，一律显式 `this.`，已经用预检规则钉住。
3. 出问题时先看“哪一步成功”同样重要：指纹闸门成功 = 密钥没问题，把排查范围直接缩到 Gradle 配置。
4. 本地没有 Android SDK 时，**`kotlinc` + 最小 stub** 依然是定位 Kotlin 语义问题的有效手段
   （本次 5 分钟内复现）。
