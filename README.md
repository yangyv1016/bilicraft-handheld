# Bilicraft 掌机

一个**完全本地运行**的 Android 版 Minecraft 聊天客户端，定位类似 Minecraft Console Client（MCC）的手机版。纯本地架构，无云端 Core，微软登录、协议连接、插件全部在手机本地完成。

---

## 特性

- **纯本地微软登录**：OAuth 2.0 设备码流程，全程无需输入密码。完整链路 `Microsoft Token → XBL → XSTS → MC Token → 验权`，refresh_token 静默刷新，用户无感知。
- **Token 安全存储**：使用 Android `EncryptedSharedPreferences`（主密钥存于 Keystore/TEE），AES-256-GCM 加密，**永不明文落盘**。
- **全量版本下拉框**：内置协议表（1.7.10 → 1.21.x）+ 在线拉取 Mojang manifest 缓存合并，分组展示（最新版 / Release / Snapshot / Old），默认「自动识别」。
- **本地直连 MC Java 服务器**：Netty 手写协议栈（帧长度 / 压缩 / AES-CFB8 加密 / 版本档案），只做聊天收发。
- **插件系统**：类 MCC ChatBot 的 JS 沙箱（Rhino 引擎），生命周期 `onLoad / onChat / onUnload`，插件只见归一化 `ChatEvent`，崩溃隔离不拖垮主进程，内置 `KeywordReply` 示例。
- **强制签名可选**：主控页可切换「强制签名」；开启后取 Mojang 玩家证书并对聊天做真实签名，适配强制安全档案的正版服务器；私钥只在内存中使用，不落盘。
- **锁屏不断线**：前台 Service（dataSync）+ PARTIAL_WAKE_LOCK + 指数退避断线重连。

---

## 架构

模块职责分明，数据单向流动：

```
storage    只负责加密存取字节（EncryptedSharedPreferences），不理解 token 语义
   ▲
auth       Device Code → XBL → XSTS → MC Token → entitlements → profile，静默刷新
           AuthClient(单步 HTTP 变换) + AuthManager(编排+状态机)
   ▲
version    内置 ProtocolTable(id→协议号) + Mojang manifest(清单) + ping(自动识别)
   ▲
protocol   Netty pipeline: [加密]-[帧长度]-[压缩]-[包编解码]
           MinecraftClient 状态机: HANDSHAKE→LOGIN→(CONFIGURATION)→PLAY
           对外只暴露 ChatEvent / ConnectionState，原始 packet 封死在内部
   ▲
plugin     Rhino JS 沙箱，PluginManager 广播 ChatEvent，逐插件 try/catch 隔离
   ▲
session    SessionController：纯 Kotlin 业务核心，编排连接/重连/插件分发（可单测）
   ▲
service    ConnectionService：Android 容器，前台通知 + WakeLock，只托管 SessionController
   ▲
ui         Compose：登录页 / 主控页（版本下拉+服务器+聊天），MainViewModel 聚合
```

关键隔离边界：**插件与 UI 永远看不到原始 packet**，只消费 `ChatEvent`。协议版本变化不影响上层。

---

## 一键启动

### 前置要求
- Android Studio（Ladybug / 2024.2 或更新）
- JDK 17+
- Android SDK（API 34），首次打开 Android Studio 会自动下载

### 步骤
1. 用 Android Studio 打开本项目根目录（会自动生成 Gradle wrapper 并同步依赖）。
2. 连接 Android 设备（Android 7.0 / API 24 及以上）或启动模拟器。
3. 点击 **Run ▶**，即可安装运行。

命令行方式（需已安装 Android SDK 并配置 `local.properties`）：

```bash
# 首次：用本机 gradle 生成 wrapper（若无 wrapper jar）
gradle wrapper --gradle-version 8.9
# 构建 + 安装
./gradlew installDebug
```

> 使用流程：打开 App → 「使用微软账户登录」→ 浏览器输入设备码授权 → 回到 App → 选版本（默认自动识别）→ 填服务器地址 → 连接 → 聊天。

---

## 微软登录 client_id

默认使用 PrismLauncher 的公开 Azure client_id（开源启动器通用），开箱即用。

如需替换为自建 Azure 应用：
1. 在 [Azure Portal](https://portal.azure.com) 注册应用（Personal Microsoft accounts）。
2. 启用 **Allow public client flows**（设备码流程必需）。
3. 把 `app/build.gradle.kts` 中 `MS_CLIENT_ID` 换成你的 Application (client) ID。

scope 固定为 `XboxLive.signin offline_access`（硬性要求，含离线刷新）。

---

## 插件开发

插件是一段 JS，约定三个回调（同 MCC ChatBot）：

```javascript
function onLoad() {
    bot.log("插件已加载");
}

// event = { text, sender, raw }
function onChat(event) {
    if (event.text.indexOf("你好") >= 0) {
        bot.sendChat("你好呀~");
    }
}

function onUnload() {}
```

插件可用的宿主能力（受限面）：
- `bot.sendChat(text)` — 发送聊天
- `bot.log(text)` — 输出到 App 内日志

内置示例见 `plugin/KeywordReplyPlugin.kt`。插件在独立 Rhino 作用域运行（解释模式），单次执行有指令上限防死循环，任何异常都被捕获为错误日志，不影响主进程与其他插件。

---

## 持续集成（CI）

`.github/workflows/` 下有两条 GitHub Actions 流水线：

- **ci.yml**：`push` / PR 到 main/master 触发。装 JDK 17 → 用官方 gradle 生成 wrapper → `assembleDebug` → 上传 `app-debug` APK 为构建产物。
- **release.yml**：推送 `v*` tag 触发。构建 `assembleRelease`（未签名）→ 自动挂到 GitHub Release。

> 因为本仓库未提交 Gradle wrapper 的二进制 jar，两条流水线都先用 `gradle wrapper` 生成它再构建。若你在本地补交了 wrapper jar，可删掉「Generate Gradle wrapper」步骤直接用 `./gradlew`。release 产物为未签名 APK，需要正式签名时在仓库 Secrets 配置 keystore 并补充签名步骤。

---

## 已知限制

- **协议覆盖**：按「聊天核心优先」策略，近代版本（1.20.2–1.21.x 现代档案 / 1.13–1.20.1 传统档案）聊天收发经过重点设计；不同小版本的 play 阶段聊天包 id 用集合宽松匹配。远古版本（1.7–1.12）已在协议表登记协议号，但包 id 档案尚未逐一适配，建议配合「自动识别」使用。
- **1.19+ 聊天签名**：提供「强制签名」开关（主控页）。关闭时发送未签名结构，兼容离线服；开启时用 Mojang 玩家证书 + 私钥对消息做 SHA256withRSA 签名，面向 `enforce-secure-profile=true` 的正版服务器。签名路径针对 1.19/1.19.1（协议 759/760）实现，lastSeen 采用空链（适配刚进服、无消息上下文场景）；更高版本的签名链细节与不同小版本的待签字节布局可能仍需按真实服务器校准。私钥仅在内存流转、不落盘。
- **配置阶段包 id**：现代档案的 configuration/play 包 id 以 1.20.2–1.21 主流值为基准，跨小版本可能有偏移，遇到异常可切换到相邻版本或自动识别。
- **构建验证**：本仓库代码未在缺少 Android SDK 的环境中编译验证，请以 Android Studio 同步结果为准。

---

## 许可

仅供学习研究。Minecraft 是 Mojang / Microsoft 的商标。