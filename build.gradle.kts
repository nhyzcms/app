// 根构建脚本：只声明插件版本，具体配置在各模块。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// 便捷任务：gradle :app:versionInfo
tasks.register("versionInfo") {
    group = "help"
    description = "打印当前版本号（来自 version.properties）"
    doLast {
        val f = file("version.properties")
        println(if (f.exists()) f.readText().trim() else "version.properties 不存在")
    }
}
