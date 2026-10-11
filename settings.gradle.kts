// 依赖仓库与模块声明。
// 注意：本仓库不提交 gradle-wrapper.jar（二进制不便审查），
// CI 通过 gradle/actions/setup-gradle 指定 Gradle 版本（见 .github/workflows/android.yml）。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nhyzcms-club-app"
include(":app")
