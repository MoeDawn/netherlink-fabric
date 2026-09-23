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
| 成就上报 | ✅ 已实现，**端到端验证过**（mixin 注入，见下）|

> ⚠️ 除指令执行与成就上报外，聊天 / 进退服 / 死亡**只做了编译验证，
> 没跑过真人进服 / 聊天 / 死亡的端到端**。上手前建议先在测试服上跑一轮。

### 成就上报：为什么要 mixin

Fabric API **没有**「玩家获得成就」事件——`AdvancementEvents` 只有
REPLACE / MODIFY / ALL_LOADED，那是用来**构建与加载**成就定义用的，不是 grant。

所以本模组用 **mixin** 注入 `PlayerAdvancements.award(AdvancementHolder, String)`。
注入点刻意不选「成就播报的聊天消息」（`GAME_MESSAGE`）：那条路上
「是不是成就、是哪个成就」得**解析本地化文本**反推，多语言环境下必然出错；
`award` 能直接拿到 `AdvancementHolder`。

过滤条件（与 Paper 端逐条对齐）：
1. `award` 返回 false → 跳过（未真正新授予）
2. id 含 `:recipes/` → 跳过（配方解锁，合成一次就通知一次）
3. `display()` 为空 → 跳过（根成就，是分类标题不是玩家感知的成就）
4. `progress.isDone()` 为假 → 跳过

> ⚠️ 第 4 条容易误判：**`award` 对每个判据都会调一次**，而「获得成就」的语义是
> **整体完成**。多判据成就（如 `adventuring_time` 需访问所有生物群系）授予单个
> 判据时 `isDone` 仍为假——这是设计，不是 bug。

**实机验证结果**：

```
服务端: [mixin-probe] award 回调触发 hook=minecraft:adventure/arbalistic ret=true
探针:   ★ 成就上报: player=AdvProbeBot advancement=[Arbalistic] key=minecraft:adventure/arbalistic
```

（验证用的临时调试入口已移除，并重跑确认移除后服务端仍能启动——
mixin 配的是 `required: true`，注入点写错会直接崩。）

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

产物：`build/libs/netherlink-fabric-0.0.1.jar`

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

首次启动会**自动生成** `config/netherlink.json`：

| 键 | 默认 | 说明 |
|---|---|---|
| `host` | `127.0.0.1` | AstrBot 侧 WS 服务端地址 |
| `port` | `8765` | 端口（须与 AstrBot 的 `ws_ports` 对应）|
| `token` | `change-me` | 必须与 AstrBot 的 `auth_token` 一致 |
| `server-name` | `mc` | 本服务器标识（握手时上报）|
| `wake-prefixes` | `ai,助手` | 游戏内唤醒词（逗号分隔）|

**键名与默认值与 Paper 端的 `config.yml` 逐字对齐**——换端时配置可以直接照搬。

### ⚠️ 为什么是 JSON 不是 YAML

Paper 端用 `config.yml`（Bukkit 自带 YAML 支持），而 Fabric 侧**既没有 Bukkit
也没有内置 YAML**——实测 MC 26.3 的 jar 与运行时 classpath 里**都没有 snakeyaml**
（它只是 Gradle 构建期的传递依赖，不进服务端）。

要读 YAML 就得自带 snakeyaml 并 shade 进 jar，多一个依赖与打包环节，收益只是
「后缀好看」。改用 Fabric 本来就有的 Gson（MC 依赖里就有），零新依赖、零打包风险。
**格式不同，语义与键名相同。**

> ⚠️ JSON 不支持注释，所以说明放在 `_comment*` 字段里（加载时会忽略）。
>
> ⚠️ `wake-prefixes` 支持**中文全角分隔符**：全角逗号「，」、顿号「、」、
> 分号「；」都会被归一化成半角逗号再切分——中文输入法下很容易打出这些，
> 直接 `split(",")` 会把整串当成一个词。

坏配置一律**回退默认值并记 warning**，绝不因为配置写错而崩掉服务端。

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
    │   ├── MainThreadExecutor.java   # 主线程调度
    │   ├── NetherLinkConfig.java     # 配置（config/netherlink.json）
    │   └── mixin/
    │       └── PlayerAdvancementsMixin.java   # 成就上报（Fabric 无此事件）
    └── resources/
        ├── fabric.mod.json
        └── netherlink.mixins.json
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
| 成就上报 | `PlayerAdvancementDoneEvent` | **没有对应事件**，用 mixin 注入 `PlayerAdvancements.award` |

> ⚠️ **`ok` 的判据是本次最反直觉的一处**：原版 `onResult(wasSuccess, ...)` 的
> `wasSuccess` 语义与 AstrBot 需要的「指令有没有被执行」**不一致**——实测
> `give @a apple 1`（没人在线）原版报 `false`，但 Paper 端报 `true`。
> 取「回调有没有被调用」才能与 Paper 端对齐：**被调用 ⟺ 指令通过了 Brigadier
> 解析并进入执行**；解析失败时原版直接返回，根本不通知 callback。

---

## 许可

[MIT License](LICENSE)
