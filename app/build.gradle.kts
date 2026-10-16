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

    // 仅测试类路径（不进 APK）：区块 2 起的纯逻辑单测（UploadLimit / DownloadPolicy / LongPressPolicy /
    // PermissionsPolicy / NavPolicy）。理由：本仓库没有本地 Android 构建环境，CI 是唯一能验证的地方，
    // 纯函数不该只靠人眼审查（见 docs/chunks/CHUNK-02-REPORT.md §2.7）。
    testImplementation("junit:junit:4.13.2")
}
