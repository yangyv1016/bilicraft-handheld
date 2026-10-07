pluginManagement {
    repositories {
        // GitHub Actions 直接使用官方源，本地保留国内镜像加速。
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://maven.aliyun.com/repository/google")
        }
        // 官方源兜底（镜像未命中时回落）
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // GitHub Actions 直接使用官方源，本地保留国内镜像加速。
        if (System.getenv("GITHUB_ACTIONS") != "true") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        // 官方源兜底（镜像未命中时回落）
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "BilicraftHandheld"
include(":plugin-api")
include(":app")
