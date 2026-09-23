# NetherLink MC 端 · Fabric 版

Minecraft **Fabric** 服务端模组，与 [AstrBot 侧的 NetherLink 插件](https://github.com/MoeDawn/astrbot_plugin_netherlink)建立 WebSocket 长连，实现服务器与 QQ 群的双向消息互通。

**必须先装好 AstrBot 侧插件**，本模组才能工作（它是客户端，主动连入 AstrBot）。

> 📌 另有 [Paper / Purpur / Folia 版](https://github.com/MoeDawn/netherlink-plugin-server)（`netherlink-plugin/` 目录）。
> 两个版本**协议完全相同**，接同一个 AstrBot 插件，服务端不用改配置。

---

## ⚠️ 当前状态：**骨架阶段，尚不可用**

本工程目前**只验证了构建链**，功能代码还没写。

| 项 | 状态 |
|---|---|
| Gradle 构建链（MC 26.3 + Fabric Loom + Fabric API） | ✅ **已跑通** |
| WebSocket 客户端（连接/重连/心跳） | ⬜ 未开始 |
| 事件上报（聊天 / 进服退服 / 死亡 / 成就） | ⬜ 未开始 |
| 下行广播与指令执行 | ⬜ 未开始 |

**别把它装到生产服上**——现在加载后只会往日志打一行「骨架加载成功」。

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

## 目录结构

```text
netherlink-fabric/
├── build.gradle               # ⚠️ 去混淆版的写法，见上文
├── gradle.properties          # 版本号都在这儿
├── settings.gradle
├── build.cmd                  # ⚠️ 纯 ASCII，用 wrapper
├── gradlew / gradlew.bat      # Gradle wrapper（9.7.1）
└── src/main/
    ├── java/dev/eyf/netherlink/fabric/
    │   └── NetherLinkFabric.java
    └── resources/
        └── fabric.mod.json
```

---

## 许可

[MIT License](LICENSE)
