# Bilicraft 掌机

一个**完全本地运行**的 Android 版 Minecraft 聊天客户端，定位类似 Minecraft Console Client（MCC）的手机版。纯本地架构，无云端 Core，微软登录、协议连接、插件全部在手机本地完成。

---

## 特性

- **纯本地微软登录**：OAuth 2.0 设备码流程，全程无需输入密码。完整链路 `Microsoft Token → XBL → XSTS → MC Token → 验权`，refresh_token 静默刷新，用户无感知。
- **Token 安全存储**：使用 Android `EncryptedSharedPreferences`（主密钥存于 Keystore/TEE），AES-256-GCM 加密，**永不明文落盘**。
- **全量版本下拉框**：内置协议表（1.7.10 → 26.2）+ 在线拉取 Mojang manifest 缓存合并，分组展示（最新版 / Release / Snapshot / Old），默认「自动识别」。
- **本地直连 MC Java 服务器**：Netty 手写协议栈（帧长度 / 压缩 / AES-CFB8 加密 / 版本档案），只做聊天收发。
- **插件系统**：支持本体自定义 `.bhplugin` 外部插件包，插件通过 `plugin-api` 注册 App 内页面入口并访问稳定宿主能力；底部导航提供独立“插件管理”页用于导入、启停、卸载和官方源安装/更新，服务器会话页提供悬浮“插件入口”按钮用于进入具体插件页面。旧 JS 沙箱插件仍作为内置脚本插件保留。
- **CDK 定时展示**：设置页内置 CDK 模块，App 只读取官方 CDN 的 `cdk/index.json`；维护者更新该文件即可按 `startsAt` / `endsAt` 控制指定时间段内展示的兑换码。
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
plugin-api 稳定外部插件 API：BhPlugin / BhPluginHost / BhPluginPanel
   ▲
externalplugin
           .bhplugin 安装、manifest 校验、DexClassLoader 加载、插件生命周期隔离
   ▲
pluginmarket
           官方插件源 index.json 拉取、包下载、SHA-256 校验、安装/更新编排
   ▲
cdk        官方 CDN cdk/index.json 拉取、时间窗过滤、设置页 CDK 展示
   ▲
plugin     旧 Rhino JS 沙箱，作为内置脚本插件保留
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

## 插件开发与官方插件源

本项目现在支持两类插件：

- **外部插件**：推荐方式，使用本体自定义 `.bhplugin` 包，不是 APK；通过 `plugin-api` 注册 App 内页面入口并访问宿主能力。插件安装、更新、启停和卸载在底部导航的“插件管理”页完成；具体插件页面从服务器会话页的悬浮“插件入口”按钮进入。
- **内置脚本插件**：旧 Rhino JS 插件，仍随本体一起加载，主要用于兼容已有内置脚本。

开发者可直接复制官方构建模板创建插件项目：

- [`.bhplugin` Gradle 构建模板](templates/bhplugin/build.gradle.kts)
- [`settings.gradle.kts` 接入示例](templates/bhplugin/settings.gradle.kts)
- [`gradle.properties` 基础配置](templates/bhplugin/gradle.properties)

完整开发流程、`.bhplugin` 包结构、API 说明和官方源发布格式见：

- [插件开发与官方插件源](docs/plugin-development.md)

官方插件市场以仓库内配置为源头，由 CDN workflow 生成最终给 App 使用的静态索引：

- [`plugin-market/index.json`](plugin-market/index.json)：人工维护的基础官方索引 / 兜底索引
- [`plugin-market/discovery.json`](plugin-market/discovery.json)：可信插件仓库自动发现白名单
- [`plugin-market/index.example.jsonc`](plugin-market/index.example.jsonc)：带字段备注的 JSONC 示例，不作为 App 正式市场数据

新增插件时，优先在 `plugin-market/discovery.json` 加入可信仓库。插件作者在 GitHub Release 上传 `.bhplugin` 与 `market-entry.json` 后，CDN workflow 会自动读取 latest Release、校验插件 ID / 权限 / 下载地址 / SHA-256，并把插件包镜像到 `https://bccdn.yanguiofficial.cn/plugins/...`，最终输出 `https://bccdn.yanguiofficial.cn/plugin-market/index.json`。不走自动发现的插件，才需要手动维护 `plugin-market/index.json` 的完整 release 条目。

---

## CDK 展示配置

设置页的 CDK 模块读取官方 CDN 文件：

- [`cdk/index.json`](cdk/index.json)
- CDN 地址：`https://bccdn.yanguiofficial.cn/cdk/index.json`

更新该文件并触发 CDN 部署后，App 会按当前时间过滤 `startsAt` / `endsAt`，只展示正在有效期内的 CDK。时间使用 ISO-8601 UTC 格式。

```json
{
  "schemaVersion": 1,
  "updatedAt": "2026-07-13T00:00:00Z",
  "entries": [
    {
      "id": "summer-2026",
      "title": "暑期活动 CDK",
      "code": "BILICRAFT-2026-SUMMER",
      "description": "活动期间可在设置页复制领取。",
      "startsAt": "2026-07-13T00:00:00Z",
      "endsAt": "2026-07-20T23:59:59Z"
    }
  ]
}
```

`startsAt` 或 `endsAt` 可省略，分别表示不限制开始时间或结束时间。无有效条目时，设置页只显示空状态。

---

## 提交与发布流程

日常提交与发布 Release 的完整约定见 [提交与发布流程](docs/commit-workflow.md)。核心一条：**日常提交不写更新公告，只有确认构建 Release、打 `v*` tag 时才在 GitHub Release 顶部编写面向玩家的公告。**

---

## 持续集成（CI）

`.github/workflows/` 下有两条 GitHub Actions 流水线：

- **ci.yml**：`push` / PR 到 main/master 触发。装 JDK 17 → 用官方 gradle 生成 wrapper → `assembleDebug` → 上传 `app-debug` APK 为构建产物。
- **release.yml**：推送 `v*` tag 触发。构建 `assembleRelease`（未签名）→ 自动挂到 GitHub Release。
- **deploy-cdn.yml**：手动、定时或 Release 成功后触发。运行 `scripts/build-cdn.mjs` 生成 App 更新索引、官方插件市场索引、插件包镜像和 CDK 配置，再部署到 Cloudflare Pages。

> 因为本仓库未提交 Gradle wrapper 的二进制 jar，两条流水线都先用 `gradle wrapper` 生成它再构建。若你在本地补交了 wrapper jar，可删掉「Generate Gradle wrapper」步骤直接用 `./gradlew`。release 产物为未签名 APK，需要正式签名时在仓库 Secrets 配置 keystore 并补充签名步骤。

---

## 已知限制

- **协议映射（palette）**：协议差异用 per-version 精确映射（`PacketPalette` + `PaletteRegistry`）收敛，逻辑包 `PacketKey` ↔ 数字 id 双向查表，取代旧的「集合宽松匹配」。login/configuration 阶段包 id 跨 1.20.2–26.x 稳定、可信度高；**play 阶段聊天/系统消息 id 版本敏感**，已按 MCCTeam/Minecraft-Console-Client 的权威逐版本表分段声明（见 `PacketPalette.modernPlayChatIds`），精确覆盖协议 767→776：767(1.21) / 768-769(1.21.2-1.21.4) / 770(1.21.5) / 771-772(1.21.6-1.21.8) / 773-774(1.21.9-1.21.11) / 775-776(26.1-26.2)。老版本（1.13–1.20.1）保留一份 legacy 基线，标注「未逐版校准」，建议配合「自动识别」使用。
- **聊天组件解析**：1.20.3（协议765）+ 服务器以「网络 NBT」下发文本组件，已用手写最小 NBT reader（`Nbt.kt`）解析；更早版本走 JSON 字符串路径。System Chat 内容在包首，提取精确；**Player Chat 包内容前有 sender/index/签名等复杂头部，当前按宽松策略处理**，失配则跳过而非崩溃，完整解析待后续按真实抓包细化。
- **1.19.3+ 聊天签名（session 体系）**：提供「强制签名」开关。关闭时发未签名结构，兼容离线服；开启时进 PLAY 后先发 Chat Session Update 上报玩家公钥 + Mojang 签名 + sessionId，之后每条消息带自增 index，用私钥做 SHA256withRSA 签名，`acknowledged` 为固定 20-bit BitSet（3 字节）。**仅支持 session 体系（协议 761/1.19.3 及以上）**；1.19–1.19.2 的旧逐条签名不支持，遇强制签名服会回退未签名。待签字节布局对字节序/字段顺序极敏感，**需对真实 `enforce-secure-profile=true` 服务器校准**（唯一无法纯离线验证的部分）。私钥仅在内存流转、不落盘。
- **测试与构建验证**：协议原语（NBT reader、Fixed BitSet、palette 双向映射、签名布局）有纯 JVM 单测（`app/src/test`），可离线跑。整包仍未在缺少 Android SDK 的环境编译验证，请以 Android Studio 同步结果为准。

---

## 许可

仅供学习研究。Minecraft 是 Mojang / Microsoft 的商标。