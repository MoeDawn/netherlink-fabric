# 更新日志

> 本文件用于 GitHub Release 的说明。格式参考另外两个端
> （`netherlink-plugin-server` 的 `CHANGELOG.md`、`astrbot_plugin_netherlink` 的同名文件）。

## v0.0.1

Fabric 版**首个版本**。

⚠️ 版本号与另外两端**独立**（Paper 端 `netherlink-plugin-server` 是 0.1.0，
AstrBot 插件是 0.1.0）——Fabric 是全新的第三个成品，从 0.0.1 起算。

### 功能

- **WebSocket 长连**：握手鉴权、指数退避重连（3→6→12→24→48→60 秒）、15 秒心跳
- **MC → QQ**：聊天（唤醒词开头分流为 `bot_chat` 交给 AI）、进服、退服、死亡、成就
- **QQ → MC**：下行整行文本广播到公屏（支持 `§` 染色码）；以控制台身份执行指令
  并回传捕获到的输出

### 实机验证状态

| 功能 | 状态 |
|---|---|
| 指令执行 + 输出捕获 + `ok` 语义 | ✅ **端到端实测**，5 条指令与 Paper 端逐一对照一致 |
| 成就上报（mixin 注入） | ✅ **端到端实测** |
| 配置生成与生效 | ✅ **实测**（改端口/唤醒词后重启，行为确实跟着变） |
| 连接 / 重连 / 心跳 | ✅ 实测（退避曲线逐档确认） |
| 聊天 / 进服 / 退服 / 死亡 | ⚠️ **仅编译验证**，未跑真人端到端 |

### 环境要求

- Minecraft **26.3**
- Fabric Loader `>= 0.19.0`
- Fabric API `0.161.0+26.3`
- **Java 25**
- 配套的 AstrBot 侧插件 `astrbot_plugin_netherlink`

### 配置

首次启动自动生成 `config/netherlink.json`，键名与默认值**与 Paper 端的
`config.yml` 逐字对齐**。

> ⚠️ 用 JSON 而不是 YAML：Fabric 侧没有内置 YAML（实测 MC 26.3 的 jar 与运行时
> classpath 里都没有 snakeyaml），要读 YAML 得自带依赖并 shade 打包，收益只是
> 「后缀好看」——改用 Fabric 本来就有的 Gson，零新依赖。

### 已知限制

- **成就上报需要 mixin**：Fabric API 没有「玩家获得成就」事件
  （`AdvancementEvents` 是构建/加载成就用的，不是 grant），
  所以本模组注入 `PlayerAdvancements.award`。
- **`config.json` 与 Paper 端的 `config.yml` 格式不同**（语义与键名相同）。
- 与 Paper 端**协议完全相同**，接同一个 AstrBot 插件，两端可混用（不同服务器各装一个）。
