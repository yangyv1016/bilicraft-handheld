import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Zip
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

/**
 * Bilicraft Handheld 外部插件构建模板。
 *
 * 复制本文件作为插件项目的 build.gradle.kts 后，只需要修改 BhPluginBuildConfig。
 * 产物：build/outputs/bhplugin/<packageName>-<version>.bhplugin
 */
data class BhPluginBuildConfig(
    val pluginId: String,
    val pluginName: String,
    val pluginDescription: String,
    val pluginVersion: String,
    val apiVersion: Int,
    val entryClass: String,
    val namespace: String,
    val packageName: String,
    val permissions: List<String> = emptyList()
)

val bhPlugin = BhPluginBuildConfig(
    pluginId = "com.example.myplugin",
    pluginName = "示例插件",
    pluginDescription = "一个 Bilicraft Handheld 外部插件。",
    pluginVersion = "0.1.0",
    apiVersion = 1,
    entryClass = "com.example.myplugin.ExamplePlugin",
    namespace = "com.example.myplugin",
    packageName = "example-plugin",
    permissions = emptyList()
)

version = bhPlugin.pluginVersion
group = bhPlugin.namespace

android {
    namespace = bhPlugin.namespace
    compileSdk = 34

    defaultConfig {
        minSdk = 24
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
    }
}

dependencies {
    // settings.gradle.kts 中需要 include(":plugin-api")，并指向本体仓库的 plugin-api 目录。
    compileOnly(project(":plugin-api"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    compileOnly(composeBom)
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.foundation:foundation")
    compileOnly("androidx.compose.material3:material3")
    compileOnly("androidx.compose.material:material-icons-extended")
    compileOnly("androidx.activity:activity-compose:1.9.2")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
}

fun androidSdkDir(): File {
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!fromEnv.isNullOrBlank()) return file(fromEnv)

    val localProperties = Properties()
    val localFile = file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use(localProperties::load)
        localProperties.getProperty("sdk.dir")?.let { return file(it) }
    }

    error("找不到 Android SDK。请设置 ANDROID_HOME，或在 local.properties 中写入 sdk.dir。")
}

fun d8Executable(): File {
    val buildTools = androidSdkDir().resolve("build-tools")
    val d8Name = if (System.getProperty("os.name").lowercase().contains("windows")) "d8.bat" else "d8"
    return buildTools.listFiles()
        .orEmpty()
        .filter { it.isDirectory }
        .sortedBy { it.name }
        .map { it.resolve(d8Name) }
        .lastOrNull { it.exists() }
        ?: error("Android SDK build-tools 中找不到 $d8Name")
}

fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    append('"')
}

fun jsonArray(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { jsonString(it) }

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val pluginManifestDir = layout.buildDirectory.dir("bhplugin/manifest")
val pluginManifestFile = pluginManifestDir.map { it.file("plugin.json") }
val extractedClassesDir = layout.buildDirectory.dir("bhplugin/classes")
val dexOutputDir = layout.buildDirectory.dir("bhplugin/dex")
val bhPluginOutputDir = layout.buildDirectory.dir("outputs/bhplugin")
val bhPluginArchiveName = "${bhPlugin.packageName}-${bhPlugin.pluginVersion}.bhplugin"
val bhPluginArchiveFile = bhPluginOutputDir.map { it.file(bhPluginArchiveName) }

val writePluginManifest by tasks.registering {
    outputs.file(pluginManifestFile)
    doLast {
        val file = pluginManifestFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "id": ${jsonString(bhPlugin.pluginId)},
              "name": ${jsonString(bhPlugin.pluginName)},
              "description": ${jsonString(bhPlugin.pluginDescription)},
              "version": ${jsonString(bhPlugin.pluginVersion)},
              "apiVersion": ${bhPlugin.apiVersion},
              "entryClass": ${jsonString(bhPlugin.entryClass)},
              "permissions": ${jsonArray(bhPlugin.permissions)}
            }
            """.trimIndent()
        )
    }
}

val extractReleaseClassesJar by tasks.registering(Copy::class) {
    dependsOn("bundleReleaseAar")
    val aarFile = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar")
    from({ zipTree(aarFile.get().asFile) }) {
        include("classes.jar")
    }
    into(extractedClassesDir)
}

val dexReleaseClasses by tasks.registering(Exec::class) {
    dependsOn(extractReleaseClassesJar)
    val androidJar = androidSdkDir().resolve("platforms/android-34/android.jar")
    doFirst { dexOutputDir.get().asFile.mkdirs() }
    commandLine(
        d8Executable().absolutePath,
        "--release",
        "--min-api", "24",
        "--lib", androidJar.absolutePath,
        "--output", dexOutputDir.get().asFile.absolutePath,
        extractedClassesDir.get().file("classes.jar").asFile.absolutePath
    )
}

tasks.register<Zip>("packageBhPlugin") {
    dependsOn(writePluginManifest, dexReleaseClasses)
    archiveFileName.set(bhPluginArchiveName)
    destinationDirectory.set(bhPluginOutputDir)
    from(pluginManifestFile) {
        rename { "plugin.json" }
    }
    from(dexOutputDir) {
        include("classes.dex")
    }
    from("src/main/assets") {
        into("assets")
    }
    from("src/main/config") {
        into("config")
    }
}

tasks.register("printBhPluginSha256") {
    dependsOn("packageBhPlugin")
    doLast {
        val file = bhPluginArchiveFile.get().asFile
        println("${file.name}  ${sha256(file)}")
    }
}