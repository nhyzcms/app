// 根构建脚本：只声明插件版本，具体配置在各模块。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

/**
 * 便捷任务：gradle versionInfo
 *
 * ⚠️ 注意它是**根项目**的任务，调用时不要写 `:app:versionInfo`（会报任务找不到）。
 * CI 里不再调用它（改为直接 cat version.properties，少一次 Gradle 调用就少一个失败点）。
 */
tasks.register("versionInfo") {
    group = "help"
    description = "打印当前版本号（来自 version.properties）"
    doLast {
        val f = rootProject.file("version.properties")
        println(if (f.exists()) f.readText().trim() else "version.properties 不存在")
    }
}
