# NetherLink MC 端 · Fabric 版

Minecraft **Fabric** 服务端模组，与 [AstrBot 侧的 NetherLink 插件](https://github.com/MoeDawn/astrbot_plugin_netherlink)建立 WebSocket 长连，实现服务器与 QQ 群的双向消息互通。

**必须先装好 AstrBot 侧插件**，本模组才能工作（它是客户端，主动连入 AstrBot）。

> 📌 另有 [Paper / Purpur / Folia 版](https://github.com/MoeDawn/netherlink-plugin-server)（`netherlink-plugin/` 目录）。
> 两个版本**协议完全相同**，接同一个 AstrBot 插件，服务端不用改配置。

---

## 状态

| 功能 | 状态 |
|---|---|
| WebSocket 连接 / 握手 / 指数退避重连 / 心跳 | ✅ 已实现，**实机验证过** |
| 指令执行 + **输出捕获** + `ok` 语义 | ✅ 已实现，**实机验证过**（见下） |
| 聊天上报（含唤醒词分流 `bot_chat`） | ✅ 已实现 |
| 进服 / 退服上报 | ✅ 已实现 |
| 死亡上报 | ✅ 已实现 |
| **成就上报** | ⬜ **未实现**——Fabric 没有「玩家获得成就」事件（`AdvancementEvents` 是构建/加载成就用的，不是 grant），需要 mixin 或刮 `GAME_MESSAGE` |

> ⚠️ 除指令执行外，事件类功能**只做了编译验证，没有跑过真人进服/聊天/死亡的端到端**。
> 上手前建议先在测试服上跑一轮。

### 指令执行：实机验证结果

在真 Fabric 26.3 服务端 + 独立 WS 探针上实测（5 条指令全过，**结果与 Paper 端逐一对照一致**）：

| 指令 | `ok` | 捕获到的输出 |
|---|---|---|
| `list` | true | `There are 0 of a max of 20 players online:` |
| `time set day` | true | `Set minecraft:overworld to time marker minecraft:day` |
| `give @a apple 1` | true | `No player was found`（指令确实执行了）|
| `nonexistentcommand123` | **false** | `Unknown or incomplete command...` |
| `say 测试广播` | true | （空输出，但确实执行了并广播成功）|

---

## 环境要求

- Minecraft **26.3**
- **[Fabric Loader](https://fabricmc.net/use/)** `>= 0.19.0`
- **[Fabric API](https://modrinth.com/mod/fabric-api)** `0.161.0+26.3`
- **Java 25**
- 已装好并运行 [AstrBot 侧插件](https://github.com/MoeDawn/astrbot_plugin_netherlink)

---

## ⚠️ 关于 MC 26.1+ 的构建配置（**踩过坑，改动前必读**）

Minecraft 自 **26.1 起不再混淆代码**（[Mojang 公告](https://www.minecraft.net/zh-hans/article/removing-obfuscation-in-java-edition)、
[Fabric 公告](https://fabricmc.net/2025/10/31/obfuscation.html)）。
Fabric 官方文档原话：

> Minecraft 26.1 is unobfuscated and includes parameter names, so there is no need for any obfuscation mappings.

这带来三条与老版本**完全不同**的配置（已实测，写错会直接构建失败）：

| 配置项 | 写法 | 写错会怎样 |
|---|---|---|
| Loom 插件 id | `net.fabricmc.fabric-loom` | 用 `-remap` 那个是**混淆版架构**，不通用 |
| Loom 版本 | `1.17-SNAPSHOT` | 发布版 `1.18.2` **不支持 26.3**，报 "Failed to set up Minecraft" |
| `mappings` | **完全不写** | 写 `loom.officialMojangMappings()` 会报 "**Failed to find official mojang mappings for 26.3**" |

为什么 `officialMojangMappings()` 会失败：Mojang 从 26.1 起**不再发布 `client_mappings`**
（版本元数据里只剩 `client` / `server`；1.21.11 还有 `client_mappings` / `server_mappings`），
而 **Yarn 也已停止维护**（对 26.1 / 26.2 / 26.3 一个版本都没有）。**两条映射路线全断**——
不是配置问题，是去混淆版本本来就不需要映射层。

另外依赖写法也不同：官方模板用 `implementation` 而不是 `modImplementation`。

**这些写法照抄自 [Fabric 官方示例模组](https://github.com/FabricMC/fabric-example-mod/tree/26.3)的
`26.3` 分支**，不是猜的。

---

## 从源码构建

```bash
cd netherlink-fabric
./build.cmd          # Windows
```

产物：`build/libs/netherlink-fabric-0.1.0.jar`

### ⚠️ 两件容易踩的事

**1. 必须用工程自带的 Gradle wrapper，不能用系统 Gradle。**
Fabric Loom `1.17-SNAPSHOT` 要求 Gradle **9.7+**，而本机装的是 9.1.0，
直接用会报 `No matching variant ... plugin.api-version 9.7.0`。
`build.cmd` 已经调 wrapper，首次运行会自动下载 Gradle 9.7.1。

**2. 本目录的完整路径含 `&` 字符，`.bat` 里不能把完整路径拼进 `call`。**
`d:\eyf\astrbot&mc\netherlink-fabric` 里的 `&` 会被 cmd 当成命令分隔符。
`build.cmd` 用 `pushd "%~dp0"` 切目录后再用相对名调用；另外该文件**刻意保持纯 ASCII**
（cmd 按 GBK 读 `.bat`，UTF-8 中文会变成乱码里的杂散分隔符）。

---

## 配置

本模组**没有配置文件**——连接参数走 JVM 系统属性（在服务端的启动脚本里加）：

| 属性 | 默认 | 说明 |
|---|---|---|
| `netherlink.host` | `127.0.0.1` | AstrBot 侧 WS 服务端地址 |
| `netherlink.port` | `8765` | 端口（须与 AstrBot 的 `ws_ports` 对应）|
| `netherlink.token` | `change-me` | 必须与 AstrBot 的 `auth_token` 一致 |

例：`java -Dnetherlink.port=8766 -Dnetherlink.token=你的token -jar fabric-server.jar nogui`

> ⚠️ 这是**临时实现**：Paper 端用的是 `config.yml`，Fabric 侧还没有。
> 后续应换成 Fabric 的配置 API 以保持一致。

---

## 目录结构

```text
netherlink-fabric/
├── build.gradle                      # ⚠️ 去混淆版的写法，见上文
├── gradle.properties                 # 版本号都在这儿
├── build.cmd                         # ⚠️ 纯 ASCII，用 wrapper
├── gradlew / gradlew.bat             # Gradle wrapper（9.7.1）
└── src/main/
    ├── java/dev/eyf/netherlink/fabric/
    │   ├── NetherLinkFabric.java     # 主类：事件注册 + 下行处理
    │   ├── AstrBotWsClient.java      # WS 客户端（纯 JDK）
    │   ├── CommandCapture.java       # 指令输出 + 成败信号收集
    │   ├── LegacyText.java           # § 染色码解析
    │   └── MainThreadExecutor.java   # 主线程调度
    └── resources/fabric.mod.json
```

---

## 与 Paper 端的实现差异（都踩过）

| 关注点 | Paper 版 | Fabric 版 |
|---|---|---|
| 事件注册 | `Bukkit.getPluginManager()` | Fabric API 静态事件总线 |
| 调度器 | Bukkit 调度器（tick 单位）| JDK `ScheduledExecutorService`（时间单位，免换算）|
| 指令执行 | `Bukkit.createCommandSender` + `dispatchCommand`（返回 boolean）| `createCommandSourceStack().withSource().withCallback()` + `performPrefixedCommand`（**返回 void**）|
| `ok` 判据 | `dispatchCommand` 的返回值 | **`CommandResultCallback.onResult` 有没有被调用过** |
| § 码渲染 | Adventure `LegacyComponentSerializer` | 自己写解析（原版 `Component` API 没有 legacy 反序列化）|
| 成就上报 | `PlayerAdvancementDoneEvent` | **没有对应事件**，未实现 |

> ⚠️ **`ok` 的判据是本次最反直觉的一处**：原版 `onResult(wasSuccess, ...)` 的
> `wasSuccess` 语义与 AstrBot 需要的「指令有没有被执行」**不一致**——实测
> `give @a apple 1`（没人在线）原版报 `false`，但 Paper 端报 `true`。
> 取「回调有没有被调用」才能与 Paper 端对齐：**被调用 ⟺ 指令通过了 Brigadier
> 解析并进入执行**；解析失败时原版直接返回，根本不通知 callback。

---

## 许可

[MIT License](LICENSE)
