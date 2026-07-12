# 插件开发与官方插件源

本项目的外部插件不是 APK。插件包格式为 `.bhplugin`，本质是一个 zip 文件，由本体在运行时通过 `DexClassLoader` 从外部文件加载。

当前插件 API 版本：`BH_PLUGIN_API_VERSION = 1`。

---

## 1. 插件包结构

一个标准 `.bhplugin` 至少包含：

```text
demo-plugin-1.0.0.bhplugin
├── plugin.json       # 插件清单，宿主安装前先读取并校验
├── classes.dex       # 插件 Kotlin/Java 编译后的 dex
├── assets/           # 可选，插件静态资源
└── config/           # 可选，插件默认配置
```

`plugin.json` 示例：

```json
{
  "id": "com.example.bhplugin.demo",
  "name": "示例插件",
  "description": "在聊天客户端内提供一个示例面板。",
  "version": "1.0.0",
  "apiVersion": 1,
  "entryClass": "com.example.bhplugin.demo.DemoPlugin",
  "permissions": ["network"]
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 插件唯一 ID，只允许字母、数字、点、下划线和横线。 |
| `name` | 是 | 插件显示名称。 |
| `description` | 否 | 插件说明。 |
| `version` | 是 | 插件版本，官方源安装时会与索引版本校验。 |
| `apiVersion` | 是 | 插件包声明的 API 版本，不能高于本体支持版本。 |
| `entryClass` | 是 | 插件入口类完整类名。 |
| `permissions` | 否 | 插件能力声明。官方源安装时会校验不能超出索引登记权限。 |

---

## 2. 推荐项目结构

独立插件建议单独建一个 Android Library 项目，例如：

```text
bhplugin-demo
├── settings.gradle.kts
├── build.gradle.kts
├── local.properties
└── src/main
    ├── AndroidManifest.xml
    └── java/com/example/bhplugin/demo
        ├── DemoPlugin.kt
        ├── DemoModels.kt
        ├── DemoRepository.kt
        └── DemoCommandGateway.kt
```

插件项目只依赖本体公开的 `plugin-api` 模块，不依赖 `app` 内部实现。这样插件只能看到稳定 API，不能反向耦合本体内部状态机。

本仓库提供了可直接复制的构建模板：

```text
templates/bhplugin/
├── settings.gradle.kts   # 本地接入 plugin-api 的示例配置
├── build.gradle.kts      # 插件项目主构建脚本，内置 .bhplugin 打包任务
└── gradle.properties     # AndroidX / JVM / Kotlin 基础配置
```

开发者新建插件项目后，先复制这三个文件，再修改 `build.gradle.kts` 顶部的 `BhPluginBuildConfig`：

```kotlin
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
```

本地开发时可把 `plugin-api` 作为本地路径依赖接入；发布前确保插件编译产物里不要重复打包宿主已经提供的 API 类。

---

## 3. 插件入口实现

插件入口类实现 `BhPlugin`：

```kotlin
package com.example.bhplugin.demo

import androidx.compose.runtime.Composable
import com.bilicraft.handheld.pluginapi.BH_PLUGIN_API_VERSION
import com.bilicraft.handheld.pluginapi.BhPlugin
import com.bilicraft.handheld.pluginapi.BhPluginDescriptor
import com.bilicraft.handheld.pluginapi.BhPluginEntrypoint
import com.bilicraft.handheld.pluginapi.BhPluginHost
import com.bilicraft.handheld.pluginapi.BhPluginPanel

class DemoPlugin : BhPlugin {
    override val descriptor = BhPluginDescriptor(
        id = "com.example.bhplugin.demo",
        name = "示例插件",
        description = "演示外部插件页面入口。",
        version = "1.0.0",
        minApiVersion = BH_PLUGIN_API_VERSION
    )

    override fun onLoad(host: BhPluginHost) {
        host.log("示例插件已加载")
    }

    override fun entrypoints(host: BhPluginHost): List<BhPluginEntrypoint> = listOf(
        BhPluginEntrypoint(
            id = "dashboard",
            title = "示例面板",
            description = "展示插件如何注册 App 内页面入口。",
            order = 10
        )
    )

    override fun createPanel(host: BhPluginHost): BhPluginPanel = DemoPanel()

    override fun onUnload(host: BhPluginHost) {
        host.log("示例插件已卸载")
    }
}

private class DemoPanel : BhPluginPanel {
    @Composable
    override fun Content(host: BhPluginHost, onClose: () -> Unit) {
        host.log("示例面板已打开")
    }
}
```

入口类的 `descriptor.id` 必须与 `plugin.json.id` 一致；否则宿主会拒绝加载。

---

## 4. 插件生命周期

宿主按以下顺序处理插件：

1. 读取 `.bhplugin` 内的 `plugin.json`。
2. 校验 `id`、`version`、`entryClass`、`apiVersion`。
3. 安装到本体私有目录。
4. 使用 `DexClassLoader` 加载 `classes.dex`。
5. 实例化 `entryClass`。
6. 校验 `BhPlugin.descriptor` 与 `plugin.json` 是否一致。
7. 调用 `onLoad(host)`。
8. 宿主读取 `entrypoints(host)` 并登记为服务器会话页悬浮“插件入口”按钮中的候选页面。
9. 用户打开某个入口时调用 `createPanel(host, entrypointId)` 并渲染 Compose 面板。
10. 卸载、更新或本体结束插件运行时调用 `onUnload(host)`。

插件异常会被隔离为插件加载失败或运行日志，不应拖垮宿主主流程。

---

## 5. 插件 API

插件 API 位于：`plugin-api/src/main/java/com/bilicraft/handheld/pluginapi/PluginApi.kt`。

### `BhPlugin`

插件主入口：

```kotlin
interface BhPlugin {
    val descriptor: BhPluginDescriptor
    fun entrypoints(host: BhPluginHost): List<BhPluginEntrypoint> = listOf(
        BhPluginEntrypoint(id = "main", title = descriptor.name, description = descriptor.description)
    )
    fun createPanel(host: BhPluginHost): BhPluginPanel
    fun createPanel(host: BhPluginHost, entrypointId: String): BhPluginPanel = createPanel(host)
    fun onLoad(host: BhPluginHost) = Unit
    fun onUnload(host: BhPluginHost) = Unit
}
```

职责：

- `descriptor`：声明插件 ID、名称、版本和最低 API 版本。
- `entrypoints`：声明插件在 App 内可展示的页面入口；未覆写时宿主会提供默认主入口。
- `onLoad`：插件加载完成后调用，适合初始化仓库、注册状态监听。
- `createPanel(host, entrypointId)`：按入口 ID 创建插件 UI 面板；单入口插件可只实现 `createPanel(host)`。
- `onUnload`：释放资源，停止插件内部协程或监听。

### `BhPluginDescriptor`

```kotlin
data class BhPluginDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val minApiVersion: Int = BH_PLUGIN_API_VERSION
)
```

`id` 是插件身份，必须稳定。升级插件时保持同一个 `id`，只递增 `version`。

### `BhPluginPanel`

```kotlin
interface BhPluginPanel {
    @Composable
    fun Content(host: BhPluginHost, onClose: () -> Unit)
}
```

插件 UI 使用 Compose。`onClose` 由宿主提供，插件不应自行操作宿主导航栈。

### `BhPluginHost`

```kotlin
interface BhPluginHost {
    val appContext: Context
    val pluginDataDir: File
    val connectionState: StateFlow<BhConnectionState>
    val chatEvents: Flow<BhChatEvent>

    fun sendChat(text: String): Boolean
    fun log(message: String)
    suspend fun httpGet(url: String): String
}
```

宿主能力边界：

| API | 说明 |
| --- | --- |
| `appContext` | Android application context。不要假设 Activity 存在。 |
| `pluginDataDir` | 插件私有数据目录，按插件 ID 隔离。 |
| `connectionState` | 当前连接状态。 |
| `chatEvents` | 宿主归一化后的聊天事件流。 |
| `sendChat(text)` | 发送聊天或命令文本。返回 `false` 表示当前不能发送。 |
| `log(message)` | 输出插件日志到宿主日志管道。 |
| `httpGet(url)` | 由宿主代理的简单 GET 请求。 |

### `BhChatEvent`

```kotlin
data class BhChatEvent(
    val plainText: String,
    val rawJson: String,
    val sender: String?,
    val timestamp: Long
)
```

插件只消费归一化聊天事件，不接触 Minecraft 原始 packet。这样协议版本变化不会扩散到插件侧。

### `BhConnectionState`

```kotlin
enum class BhConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    Failed
}
```

插件如果要发送聊天，应优先检查状态是否为 `Connected`，并处理 `sendChat` 返回 `false` 的情况。

---

## 6. 打包流程

复制 `templates/bhplugin/build.gradle.kts` 后，插件项目会得到两个公开任务：

| 任务 | 说明 |
| --- | --- |
| `packageBhPlugin` | 构建 Android Library，生成 `classes.dex`，写入 `plugin.json`，并打包为 `.bhplugin`。 |
| `printBhPluginSha256` | 先执行 `packageBhPlugin`，再输出最终 `.bhplugin` 的 SHA-256，供官方源索引使用。 |

执行：

```bash
./gradlew packageBhPlugin
./gradlew printBhPluginSha256
```

如果本地没有 Gradle Wrapper，也可以使用已安装的 Gradle 执行同名任务。

模板内置的打包链路：

1. 构建 Android Library。
2. 从 release AAR 提取 `classes.jar`。
3. 使用 Android SDK `d8` 生成 `classes.dex`。
4. 根据 `BhPluginBuildConfig` 写入 `plugin.json`。
5. 复制可选 `src/main/assets/`、`src/main/config/`。
6. zip 为 `<packageName>-<pluginVersion>.bhplugin`。
7. 可选输出 SHA-256，用于官方源索引。

打包结果示例：

```text
build/outputs/bhplugin/demo-plugin-1.0.0.bhplugin
```

安装方式：

- 本地调试：在底部导航打开“插件管理”，点击“导入插件包”选择 `.bhplugin` 文件。
- 手动目录：把 `.bhplugin` 放入“插件管理”页展示的插件目录，然后点击“重新扫描”。
- 官方源：发布到官方索引后，用户可在“插件管理”的“官方插件市场”安装或更新。
- 打开页面：插件启用后，回到服务器会话页，点击悬浮“插件入口”按钮，从候选列表进入具体插件页面。

---

## 7. 官方插件源

本体只内置官方源，不提供第三方源配置。

官方源清单维护在本仓库：

```text
plugin-market/index.json
```

App 默认拉取这个文件的 raw 地址：

```text
https://raw.githubusercontent.com/yangyv1016/bilicraft-handheld/main/plugin-market/index.json
```

维护规则：

- 官方源就是一个静态 `index.json`，不需要后台服务。
- 开发者发布 `.bhplugin` 到自己的 GitHub Releases 或其他稳定下载地址。
- 开发者通过 PR 修改 `plugin-market/index.json`，新增或更新自己的插件条目。
- PR 必须提供最终 `.bhplugin` 的 `downloadUrl`、`sha256`、版本、API 版本、权限声明和更新说明。
- 合并 PR 后，App 下一次刷新官方插件市场即可看到新条目。

推荐发布流程：

1. 插件项目执行 `packageBhPlugin` 生成 `.bhplugin`。
2. 执行 `printBhPluginSha256`，记录最终包的 SHA-256。
3. 创建 GitHub Release，并上传这个 `.bhplugin`。
4. 修改本仓库 `plugin-market/index.json`。
5. 提交 PR，等待官方审核合并。

官方源索引字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `schemaVersion` | 是 | 索引格式版本，当前为 `1`。 |
| `updatedAt` | 否 | 索引更新时间，建议 ISO-8601 字符串。 |
| `plugins[]` | 是 | 官方插件条目数组。 |
| `plugins[].id` | 是 | 插件唯一 ID，必须与 `.bhplugin` 内 `plugin.json.id` 一致。 |
| `plugins[].name` | 是 | 插件展示名称。 |
| `plugins[].summary` | 否 | 列表摘要。 |
| `plugins[].description` | 否 | 详情说明。 |
| `plugins[].repo` | 否 | 插件源码或主页地址。 |
| `plugins[].author` | 否 | 作者名称。 |
| `plugins[].category` | 否 | 分类，默认 `tools`。 |
| `plugins[].latestVersion` | 是 | 官方市场展示的最新版本。 |
| `plugins[].minAppVersion` | 否 | 最低 App 版本。 |
| `plugins[].minApiVersion` | 是 | 插件需要的最低插件 API 版本。 |
| `plugins[].permissions` | 否 | 官方登记权限，插件包内权限不能超出这里。 |
| `plugins[].releases[]` | 是 | 发布版本数组，至少包含 `latestVersion` 对应版本。 |
| `releases[].version` | 是 | 发布版本，必须与 `.bhplugin` 内 `plugin.json.version` 一致。 |
| `releases[].downloadUrl` | 是 | `.bhplugin` 下载地址。 |
| `releases[].sha256` | 是 | 最终 `.bhplugin` 文件 SHA-256。 |
| `releases[].size` | 否 | 文件大小，单位字节。 |
| `releases[].changelog` | 否 | 版本更新说明。 |

官方源索引带备注示例见 `plugin-market/index.example.jsonc`。示例内容只用于说明字段，不会被 App 当作正式市场数据；提交正式源时请去掉注释并写入 `plugin-market/index.json`：

```json
{
  "schemaVersion": 1,
  "updatedAt": "2026-07-12T00:00:00Z",
  "plugins": [
    {
      "id": "com.example.bhplugin.demo",
      "name": "示例插件",
      "summary": "用于演示官方源索引字段的占位插件。",
      "description": "这是索引格式示例。提交正式 PR 时，downloadUrl 必须指向真实 .bhplugin，sha256 必须来自最终上传文件。",
      "repo": "https://github.com/example/bhplugin-demo",
      "author": "Example Developer",
      "category": "example",
      "latestVersion": "1.0.0",
      "minAppVersion": "0.1.9",
      "minApiVersion": 1,
      "permissions": ["network"],
      "releases": [
        {
          "version": "1.0.0",
          "downloadUrl": "https://github.com/example/bhplugin-demo/releases/download/v1.0.0/demo-1.0.0.bhplugin",
          "sha256": "replace-with-final-bhplugin-sha256",
          "size": 123456,
          "changelog": "首次发布示例插件。"
        }
      ]
    }
  ]
}
```

正式 `plugin-market/index.json` 只能放已经有真实下载地址和真实 SHA-256 的插件；没有发布 `.bhplugin` 前不要提交占位条目。

宿主安装官方插件时会做这些校验：

1. 拉取官方 `index.json`。
2. 下载目标 `.bhplugin`。
3. 校验下载文件 SHA-256。
4. 读取插件包内 `plugin.json`。
5. 校验 `id` 与官方索引一致。
6. 校验 `version` 与 release 版本一致。
7. 校验 `apiVersion` 与官方索引登记的 API 版本一致。
8. 校验插件包权限没有超出官方索引登记权限。
9. 调用外部插件管理器安装并加载。

---

## 8. API 兼容策略

- `plugin-api` 是唯一稳定边界。
- 插件不要依赖 `app` 模块内部类，例如 `SessionController`、`MainViewModel`、协议 packet 实现等。
- 本体新增能力时优先扩展 `BhPluginHost`，不要让插件反射访问内部对象。
- 若未来 API 不兼容，递增 `BH_PLUGIN_API_VERSION`，旧插件继续按旧版本兼容处理。

---

## 9. 开发检查清单

发布前至少确认：

- `plugin.json.id` 与 `descriptor.id` 一致。
- `plugin.json.version` 与官方源 release 版本一致。
- `apiVersion` 不高于本体支持版本。
- `.bhplugin` 中存在 `classes.dex` 和 `plugin.json`。
- 官方源 `sha256` 来自最终上传的 `.bhplugin`，不是中间产物。
- 插件只把持久化数据写入 `host.pluginDataDir`。
- 插件 UI 通过 `BhPluginPanel.Content` 渲染，不直接假设宿主 Activity。