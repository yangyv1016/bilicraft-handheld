pluginManagement {
    repositories {
        // 国内镜像优先（命中即走加速通道）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        // 官方源兜底（镜像未命中时回落）
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像优先（命中即走加速通道）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        // 官方源兜底（镜像未命中时回落）
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "BilicraftHandheld"
include(":app")