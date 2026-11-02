import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 版本号 ────────────────────────────────────────────────────────────────
// 默认取 version.properties；CI 打 tag 时会传 VERSION_NAME 覆盖。
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val appVersionCode = (System.getenv("VERSION_CODE")
    ?: versionProps.getProperty("versionCode", "1")).trim().toInt()
val appVersionName = (System.getenv("VERSION_NAME")
    ?: versionProps.getProperty("versionName", "0.1.0")).trim()

// ── 签名 ──────────────────────────────────────────────────────────────────
// 只有 CI 注入了 keystore 才配置 release 签名。
// 未配置时 assembleRelease 会直接报错退出，**不会**产出一个装不上的未签名包。
// ⚠️ 变量名一律带 env 前缀，**绝不能**叫 keyAlias / keyPassword：
// 与下面 signingConfigs 里接收者的属性同名会导致赋值解析错误（详见那段注释）。
val envKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val envKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val envKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val envKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning = !envKeystorePath.isNullOrBlank() &&
    !envKeystorePassword.isNullOrBlank() &&
    !envKeyAlias.isNullOrBlank() &&
    !envKeyPassword.isNullOrBlank()

android {
    namespace = "com.nhyzcms.clubapp"
    compileSdk = 35

    defaultConfig {
        // ⚠️ 包名一经发布不可更改（见 docs/ROADMAP.md 区块 1 待确认项）
        applicationId = "com.nhyzcms.clubapp"
        minSdk = 26          // Android 8.0：支持纯 XML 自适应图标，见 ADR-A04
        targetSdk = 34       // 有意不跟 35：避开强制 edge-to-edge，沉浸式在区块 4 单独做
        versionCode = appVersionCode
        versionName = appVersionName
        resourceConfigurations += listOf("zh", "en")
        // 不引入任何 vectorDrawables 兼容库：minSdk 26 原生支持 VectorDrawable
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // ⚠️ 这里必须写 `this.xxx = ...`，而且右边**不能**是同名的脚本属性。
                //
                // 2026-10-17 首个 Release（tag v0.1.0）就死在这上面：
                // 在“带接收者的 lambda”里，裸写的名字会优先解析成**接收者自己的属性**，
                // 于是 `keyPassword = keyPassword` 变成自己赋给自己，值仍是 null；
                // 构建一直到给 APK 签名那一步才报
                //   NullPointerException: SigningConfig "release" is missing required property "keyPassword"。
                // 更隐蔽的是 `storePassword = keystorePassword`（右边名字不同）恰好正常，
                // 于是现象变成“静态预检过、指纹校验过、单测过，最后打包才失败”。
                // 本地复现：kotlinc + 同名 stub（见 docs/PITFALLS.md §签名配置自赋值）。
                this.storeFile = file(envKeystorePath!!)
                this.storePassword = envKeystorePassword
                this.keyAlias = envKeyAlias
                this.keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // debug 包用独立包名，可与正式版共存（方便对比、不丢正式版登录态）
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // 第一阶段先保证能出包；lint 报告单独查看，区块 9 再收紧。
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        // 区块 9：壳级启动测试要在 JVM 上 inflate 真实布局 / 读真实资源，
        // 所以必须把 Android 资源打进单元测试的 classpath（AGP 会生成 merged manifest 与资源表）。
        // 代价：`:app:testDebugUnitTest` 首次会慢一些，但不引入模拟器（CI 省 ~10 分钟且更稳）。
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// 未配置签名时阻止 release 构建（避免误发不可用包）。
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (!hasReleaseSigning) {
            throw GradleException(
                "拒绝构建 release：缺少签名配置。\n" +
                    "请设置环境变量 ANDROID_KEYSTORE_PATH / ANDROID_KEYSTORE_PASSWORD / " +
                    "ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD（CI 走 GitHub Secrets，见 docs/BUILD.md §3）",
            )
        }
    }
}

dependencies {
    // 运行期依赖刻意保持最少：体积与供应链风险最小化（ADR-A05）。
    // 区块 4 新增 swiperefreshlayout（下拉刷新）：Jetpack 官方组件，只带
    // annotation / core / interpolator 三个小传递依赖，APK 增量约 40 KB；
    // 若手写下拉刷新的手势状态机，代码量更大且本地无真机可调参，不划算。
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")   // 后续 JS 桥接（WebMessageListener）用

    // 区块 8（原生扫码）：ZXing 解码 + 相机取景（CaptureActivity / DecoratedBarcodeView）。
    // 为什么不直接在 WebView 里调摄像头：国产 ROM 上 WebView 的 getUserMedia 极不稳定，
    // 而网页侧扫码还要按需下载 ~950 KB 的 wasm（服务器只有 3 M 带宽）；原生解码更快也更稳。
    // 体积代价：zxing-android-embedded ≈150 KB + zxing core ≈600 KB（未压缩），
    // APK 约 +0.7 MB —— 换来「取景框立刻出来、扫完才发一次请求」。
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // 仅测试类路径（不进 APK）：区块 2 起的纯逻辑单测（UploadLimit / DownloadPolicy / LongPressPolicy /
    // PermissionsPolicy / NavPolicy）。理由：本仓库没有本地 Android 构建环境，CI 是唯一能验证的地方，
    // 纯函数不该只靠人眼审查（见 docs/chunks/CHUNK-02-REPORT.md §2.7）。
    testImplementation("junit:junit:4.13.2")

    // 区块 9：壳级「启动 → 加载 → 错误页」测试。
    // 为什么不是 androidTest：本仓库没有本地 Android SDK，CI 上跑模拟器要多花 ~10 分钟、
    // 还要依赖第三方 emulator action，失败率与排查成本都高；而这块要验的其实是
    // **壳自己的接线**（安全配置 / 错误分类 / 浮层撤除 / 返回键裁决），不是 Chromium 的渲染。
    // Robolectric 在 JVM 上跑真实 Activity 生命周期 + 真实布局，足以覆盖这些点；
    // 真实 WebView 的网络与渲染仍由 docs/REGRESSION.md 的人工清单兜底。
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Mockito 只用于 mock 那几个「构造函数不可见」的 Android 抽象类
    // （SslErrorHandler / SslError / WebResourceError）；不进 APK。
    testImplementation("org.mockito:mockito-core:5.14.2")
}
