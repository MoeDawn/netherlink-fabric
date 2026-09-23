# 更新日志

> 本文件用于 GitHub Release 的说明。格式参考另外两端的 `CHANGELOG.md`。

## v0.0.1

NetherLink 的 MC 端 **Fabric** 模组——配套 AstrBot 上的 NetherLink 插件使用。

### 功能

- **WebSocket 长连**：握手鉴权、指数退避重连（3 → 6 → 12 → 24 → 48 → 60 秒）、15 秒心跳
- **MC → QQ**：聊天（唤醒词开头分流为 `bot_chat` 交给 AI）、进服、退服、死亡、成就
- **QQ → MC**：下行整行文本广播到公屏（支持 § 染色码）；以控制台身份执行指令
  并回传捕获到的输出

### 验证状态

| 功能 | 状态 |
|---|---|
| 指令执行 + 输出捕获 | ✅ 端到端实测（5 条指令与 Paper 端逐一对照一致） |
| 成就上报 | ✅ 端到端实测 |
| 配置生成与生效 | ✅ 实测 |
| 连接 / 重连 / 心跳 | ✅ 实测 |
| 聊天 / 进服 / 退服 / 死亡 | ⚠️ 仅编译验证 |

### 环境要求

- Minecraft **26.3**
- Fabric Loader `>= 0.19.0`
- Fabric API `0.161.0+26.3`
- **Java 25**
- 已装好并运行 AstrBot 上的 NetherLink 插件

### 配置

首次启动自动生成 `config/netherlink.json`，**键名与默认值与 Paper 端的
`config.yml` 逐字对齐**——换端时配置可以直接照搬。

### 已知限制

- `config/netherlink.json` 与 Paper 端的 `config.yml` **格式不同**（语义与键名相同）。
- 聊天 / 进服 / 退服 / 死亡尚未跑真人的端到端测试，上手前建议先在测试服上跑一轮。
- 与 Paper / Purpur / Folia 版**协议完全相同**，接同一个 AstrBot 插件，
  两端可混用（不同服务器各装一个）。
