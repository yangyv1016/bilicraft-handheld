plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.bilicraft.handheld"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bilicraft.handheld"
        minSdk = 24          // Android 7.0，覆盖绝大多数在用设备
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // PrismLauncher 使用的公开 Azure client_id（开源启动器通用）。
        // 如需替换为自建 Azure 应用，改这里即可，详见 README。
        buildConfigField(
            "String",
            "MS_CLIENT_ID",
            "\"c36a9fb6-4f2a-41ff-90bd-ae7cc92031eb\""
        )
    }

    // 签名密钥从环境变量读取（CI 由 GitHub Secrets 注入）。
    // 本地未设置这些变量时，release 保持未签名，不影响本地开发编译。
    val keystorePath = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 仅当注入了 keystore 才应用签名，否则退回未签名产物。
            signingConfig = if (keystorePath != null)
                signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 序列化（JSON）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // 安全存储：EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // HTTP：OAuth / Mojang 接口
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 网络协议底层：Netty（MC Java 协议 socket + 帧编解码）
    implementation("io.netty:netty-handler:4.1.112.Final")
    implementation("io.netty:netty-codec:4.1.112.Final")

    // 插件沙箱：Rhino（嵌入式 JS 引擎，纯 JVM，Android 可用）
    implementation("org.mozilla:rhino:1.7.15")

    testImplementation("junit:junit:4.13.2")
}