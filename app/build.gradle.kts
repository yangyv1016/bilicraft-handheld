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
        versionCode = 17
        versionName = "1.0.4"

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

// region syncAppIcons 顶层辅助声明
// 说明：这些声明必须放在脚本顶层，不能内联进 syncAppIcons 的 doLast{} 块。
// Kotlin 2.0 编译 build.gradle.kts 时，对「脚本 lambda 上下文内的局部函数/局部 data class」
// 会在 IR lowering 阶段崩溃（TYPE_PARAMETER IR_EXTERNAL_DECLARATION_STUB）。提升到顶层可规避。
data class SyncedIconEntry(val id: String, val alias: String, val drawable: String)

// 文件名 → 合法资源 id（小写、非 [a-z0-9_] 转 _、确保字母开头）
fun iconFileNameToId(fileName: String): String {
    val base = fileName.substringBeforeLast('.').lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9' || it == '_') it else '_' }
        .joinToString("")
    return if (base.firstOrNull()?.isLetter() == true) base else "icon_$base"
}

// id → alias 类名（PascalCase）
fun iconIdToPascal(id: String): String =
    id.split('_').filter { it.isNotEmpty() }
        .joinToString("") { it.replaceFirstChar(Char::uppercase) }

fun replaceMarkedRegion(text: String, startMarker: String, endMarker: String, replacement: String): String {
    val startIndex = text.indexOf(startMarker)
    val lineStart = text.lastIndexOf('\n', startIndex) + 1
    val endIndex = text.indexOf(endMarker, startIndex) + endMarker.length
    return text.substring(0, lineStart) + replacement + text.substring(endIndex)
}
// endregion syncAppIcons 顶层辅助声明

/**
 * 展开成三处编译期产物——drawable 图片、AppIconCatalog 数据表、Manifest 的 activity-alias。
 *
 * 幂等：图标集不变则生成内容一致，不触发写入（避免无谓的重编译与脏 diff）。
 * 命名：文件名即 id（小写化、非法字符转下划线），alias 类名为其 PascalCase，
 *       drawable 名为 app_icon_<id>；名为 icon/default 的视为默认图标（置顶且 enabled=true）。
 * 挂到 preBuild：本地与 CI 的每次构建都会先跑，无需改 release.yml。
 */
val syncAppIcons by tasks.registering {
    val iconsDir = rootProject.file("icons")
    val drawableDir = file("src/main/res/drawable")
    val catalogFile = file("src/main/java/com/bilicraft/handheld/appicon/AppIconCatalog.kt")
    val manifestFile = file("src/main/AndroidManifest.xml")

    doLast {
        val pngFiles = (iconsDir.listFiles { f -> f.isFile && f.extension.equals("png", true) }
            ?: emptyArray()).sortedBy { it.name.lowercase() }
        if (pngFiles.isEmpty()) {
            logger.warn("syncAppIcons: icons/ 下没有 png，跳过图标同步")
            return@doLast
        }

        val seenIds = mutableSetOf<String>()
        val rawEntries = pngFiles.mapNotNull { png ->
            val id = iconFileNameToId(png.name)
            if (!seenIds.add(id)) {
                logger.warn("syncAppIcons: 图标 id 冲突「$id」(${png.name})，已跳过")
                null
            } else {
                SyncedIconEntry(id = id, alias = ".ui.alias.${iconIdToPascal(id)}", drawable = "app_icon_$id")
            }
        }
        // 默认图标（icon/default）置顶，其余保持字母序 —— 保证 catalog.first 与 manifest enabled=true 一致
        val entries = rawEntries.sortedByDescending { it.id == "icon" || it.id == "default" }
        val defaultDrawable = entries.first().drawable

        // 1. 同步 drawable：复制当前图标，删除不再存在的 app_icon_*.png
        val wantedPng = entries.associate { entry ->
            "${entry.drawable}.png" to pngFiles.first { iconFileNameToId(it.name) == entry.id }
        }
        drawableDir.listFiles { f -> f.isFile && f.name.startsWith("app_icon_") && f.extension == "png" }
            ?.forEach { if (it.name !in wantedPng) it.delete() }
        wantedPng.forEach { (name, src) -> src.copyTo(drawableDir.resolve(name), overwrite = true) }

        // 2. 重写 AppIconCatalog.kt 标记区
        val catalogBody = buildString {
            appendLine("    // region APP-ICON AUTO-GENERATED (由 syncAppIcons 生成，勿手改)")
            appendLine("    val all: List<AppIcon> = listOf(")
            entries.forEach { entry ->
                appendLine("        AppIcon(")
                appendLine("            id = \"${entry.id}\",")
                appendLine("            aliasSuffix = \"${entry.alias}\",")
                appendLine("            displayName = \"${entry.id}\",")
                appendLine("            description = \"${entry.id}\",")
                appendLine("            previewResId = R.drawable.${entry.drawable}")
                appendLine("        ),")
            }
            appendLine("    )")
            append("    // endregion APP-ICON AUTO-GENERATED")
        }
        val newCatalog = replaceMarkedRegion(
            catalogFile.readText(),
            "region APP-ICON AUTO-GENERATED",
            "endregion APP-ICON AUTO-GENERATED",
            catalogBody
        )
        if (catalogFile.readText() != newCatalog) catalogFile.writeText(newCatalog)

        // 3. 重写 AndroidManifest.xml 标记区
        val manifestBody = buildString {
            appendLine("        <!-- APP-ICON AUTO-GENERATED START -->")
            entries.forEach { entry ->
                appendLine("        <activity-alias")
                appendLine("            android:name=\"${entry.alias}\"")
                appendLine("            android:targetActivity=\".ui.MainActivity\"")
                appendLine("            android:icon=\"@drawable/${entry.drawable}\"")
                appendLine("            android:label=\"@string/app_name\"")
                appendLine("            android:exported=\"true\"")
                appendLine("            android:enabled=\"${entry.drawable == defaultDrawable}\">")
                appendLine("            <intent-filter>")
                appendLine("                <action android:name=\"android.intent.action.MAIN\" />")
                appendLine("                <category android:name=\"android.intent.category.LAUNCHER\" />")
                appendLine("            </intent-filter>")
                appendLine("        </activity-alias>")
            }
            append("        <!-- APP-ICON AUTO-GENERATED END -->")
        }
        val newManifest = replaceMarkedRegion(
            manifestFile.readText(),
            "<!-- APP-ICON AUTO-GENERATED START -->",
            "<!-- APP-ICON AUTO-GENERATED END -->",
            manifestBody
        )
        if (manifestFile.readText() != newManifest) manifestFile.writeText(newManifest)

        logger.lifecycle("syncAppIcons: 已同步 ${entries.size} 个图标 -> ${entries.joinToString { it.id }}")
    }
}

tasks.named("preBuild") { dependsOn(syncAppIcons) }

dependencies {
    implementation(project(":plugin-api"))

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

    // 注解：@DrawableRes 等资源 id 编译期校验。
    // 直接用 -jvm 工件：annotation 主坐标是 KMP 占位（无字节码），
    // 靠变体重定向到 annotation-jvm 才有 DrawableRes.class；此处直连避免重定向失效。
    implementation("androidx.annotation:annotation-jvm:1.8.1")

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