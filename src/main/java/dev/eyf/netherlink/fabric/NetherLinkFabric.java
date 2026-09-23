package dev.eyf.netherlink.fabric;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NetherLink MC 端（Fabric 模组）。
 *
 * <p>职责与 Paper 端（{@code netherlink-plugin}）**完全一致**，协议也相同，
 * 两者接同一个 AstrBot 插件、服务端不用改配置：
 * <ol>
 *   <li>维护与 AstrBot 的 WebSocket 连接（见 {@link AstrBotWsClient}）</li>
 *   <li>MC → QQ：聊天 / 进服 / 退服 / 死亡上报；唤醒词开头的聊天作为 bot_chat 上报</li>
 *   <li>QQ → MC：{@code chat} / {@code bot_reply} 下行整行文本广播到公屏；
 *       {@code command} 以控制台身份执行并回传输出</li>
 * </ol>
 *
 * <p>防循环由 AstrBot 侧通过 OneBot self_id 识别机器人自身消息，本端不做文本匹配。
 *
 * <p>⚠️ 与 Paper 端的**结构性差异**：
 * <ul>
 *   <li>Paper 端有 {@code Bukkit.getPluginManager()} 注册事件，Fabric 用
 *       {@code ServerLifecycleEvents}/{@code ServerMessageEvents} 这些静态事件总线；</li>
 *   <li>Paper 端用 Bukkit 调度器，这里用 JDK 的 {@link java.util.concurrent.ScheduledExecutorService}；</li>
 *   <li>Paper 端用 {@code LegacyComponentSerializer} 渲染 § 码，这里自行解析
 *       （原版 {@code Component} API 没有内置的 legacy 反序列化）。</li>
 * </ul>
 */
public class NetherLinkFabric implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("NetherLink");
    /** 与 AstrBot 握手时上报的服务器标识（显示名不在本端控制，由 AstrBot 的
     *  {@code server_display_names} 决定）。 */
    public static final String SERVER_NAME = "mc";

    /** 当前实例。Fabric 的主类由框架构造，事件回调里拿不到 this，故留一个静态引用。 */
    public static NetherLinkFabric INSTANCE;

    /** 服务端主线程执行器：操作世界/玩家必须在主线程上。 */
    private final MainThreadExecutor mainThread = new MainThreadExecutor();

    private final Gson gson = new Gson();
    private AstrBotWsClient wsClient;
    private MinecraftServer server;

    /** 游戏内机器人唤醒词，从配置读（与 AstrBot 侧 mc_wake_prefixes 一致）。
     *  ⚠️ 此前是硬编码的 ["ai","助手"]——那意味着用户在配置里改唤醒词对 Fabric 端
     *  完全无效，而 Paper 端是从配置读的。本次配置统一时一并修掉。 */
    private java.util.List<String> wakePrefixes = java.util.List.of();

    /** 待回执的指令：id -> 输出收集器。 */
    private final java.util.Map<String, CommandCapture> pendingCommands = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        INSTANCE = this;

        // 连接要在服务端起来之后建立（需要知道 MC 服务端实例）
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            this.server = srv;
            mainThread.bind(srv);

            // 配置从文件读（config/netherlink.json），与 Paper 端的 config.yml
            // 键名与默认值逐字对齐——见 NetherLinkConfig 的类注释。
            NetherLinkConfig cfg = NetherLinkConfig.load(
                    net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());

            this.wakePrefixes = parsePrefixes(cfg.wakePrefixesRaw);
            wsClient = new AstrBotWsClient(LOGGER, mainThread, cfg.host, cfg.port, cfg.token);
            wsClient.connect();
            LOGGER.info("NetherLink(Fabric) 已启用，目标 AstrBot: {}:{}（唤醒词: {}，服务器标识: {}）",
                    cfg.host, cfg.port, wakePrefixes, cfg.serverName);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            if (wsClient != null) {
                wsClient.shutdown();
            }
            LOGGER.info("NetherLink(Fabric) 已卸载");
        });

        registerPlayerEvents();
        registerChatEvents();
        registerDeathEvents();
    }

    /**
     * 解析逗号分隔的唤醒词。
     *
     * <p>⚠️ 与 AstrBot 插件侧一样要处理**中文全角分隔符**：中文输入法下打出的
     * 常是全角逗号「，」或顿号「、」，直接 split(",") 会把整串当成一个词。
     * AstrBot 侧统一走 `_SEPARATORS` 归一化，这里做等价的处理。
     */
    static java.util.List<String> parsePrefixes(String raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String normalized = raw
                .replace('，', ',').replace('、', ',')
                .replace('；', ',').replace(';', ',')
                .replace('　', ',')   // 全角空格
                .replace('：', ':');
        for (String p : normalized.split(",")) {
            String s = p.strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 进服 / 退服。 */
    private void registerPlayerEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayer p = handler.player;
            report("join", p.getScoreboardName());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            // ⚠️ 用 handler.player 而不是「遍历找离线的玩家」：断开时玩家可能
            // 已经从玩家列表里移除，只有 handler 上还挂着引用。
            ServerPlayer p = handler.player;
            report("leave", p.getScoreboardName());
        });
    }

    /** 聊天：唤醒词开头 → bot_chat，否则普通 chat。 */
    private void registerChatEvents() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, bound) -> {
            String name = sender.getScoreboardName();
            // decoratedContent 是**渲染后**的文本（含队伍前缀等）；signedContent 是玩家输入的原文。
            // 这里取原文：与 Paper 端行为一致（那边取的是 AsyncChatEvent 的 message()）。
            String text = message.signedContent();
            for (String prefix : wakePrefixes) {
                if (text.startsWith(prefix)) {
                    JsonObject o = new JsonObject();
                    o.addProperty("type", "bot_chat");
                    o.addProperty("player", name);
                    o.addProperty("text", text);
                    sendAsync(o);
                    return;
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("type", "chat");
            o.addProperty("player", name);
            o.addProperty("text", text);
            sendAsync(o);
        });
    }

    /** 死亡。 */
    private void registerDeathEvents() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof ServerPlayer player)) {
                return; // 只报玩家，怪物死亡不上报
            }
            Component deathMessage = damageSource.getLocalizedDeathMessage(entity);
            JsonObject o = new JsonObject();
            o.addProperty("type", "death");
            o.addProperty("player", player.getScoreboardName());
            o.addProperty("message", deathMessage.getString());
            sendAsync(o);
        });
    }

    // ------------------------------------------------------------------
    // 上行发送
    // ------------------------------------------------------------------
    private void report(String type, String player) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("player", player);
        sendAsync(o);
    }

    /**
     * 玩家获得成就时的上报（由 {@code PlayerAdvancementsMixin} 调用）。
     *
     * <p>Fabric 没有现成的「玩家获得成就」事件，只能靠 mixin 注入——见那个类的注释。
     * 过滤条件（配方解锁 / 根成就）在 mixin 里做，这里只负责发出去。
     */
    public void reportAdvancement(String player, String advancement, String key) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "advancement");
        o.addProperty("player", player);
        o.addProperty("advancement", advancement);
        // key 形如 "minecraft:story/mine_diamond"，与 Paper 端上报的字段一致
        // （那边是 advancement.getKey().getKey()，不带命名空间；AstrBot 侧只用它做去重参考）
        o.addProperty("advancement_key", key);
        sendAsync(o);
    }

    /** 网络发送切到 IO 线程，别占着服务端主线程。 */
    private void sendAsync(JsonObject payload) {
        final String json = payload.toString();
        mainThread.executeAsync(() -> {
            AstrBotWsClient c = wsClient;
            if (c == null) {
                LOGGER.warn("WS 客户端未就绪，丢弃消息: {}", json.substring(0, Math.min(60, json.length())));
                return;
            }
            c.send(json);
        });
    }

    // ------------------------------------------------------------------
    // 下行处理（已在主线程）
    // ------------------------------------------------------------------
    public void onWsMessage(String raw) {
        try {
            JsonObject data = gson.fromJson(raw, JsonObject.class);
            String type = data.has("type") ? data.get("type").getAsString() : "";
            switch (type) {
                case "chat", "bot_reply" -> broadcastLine(data);
                case "command" -> runCommand(data);
                default -> {
                    // hello 应答 / 未知类型，忽略
                }
            }
        } catch (Exception e) {
            LOGGER.warn("处理 WS 消息失败: {}", e.getMessage());
        }
    }

    /** 下行整行文本：AstrBot 已渲染好（含 § 染色码），这里解析后广播。 */
    private void broadcastLine(JsonObject data) {
        String line = data.has("line") ? data.get("line").getAsString() : "";
        if (line.isEmpty() || server == null) {
            return;
        }
        Component component = LegacyText.parse(line);
        server.getPlayerList().broadcastSystemMessage(component, false);
    }

    /**
     * 以控制台身份执行一条指令并回传输出。
     *
     * <p>捕获方式是 {@link CommandSourceStack#withSource}：原版指令的反馈最终都会走
     * {@code CommandSource.sendSystemMessage(Component)}，所以把 Source 换成一个
     * 收集器就能拿到输出。权限给 {@code ALL_PERMISSIONS} 以等同于控制台。
     *
     * <p>⚠️ {@code performPrefixedCommand} 返回 **void**（不像 Bukkit 的
     * {@code dispatchCommand} 返回 boolean）。所以「是否执行成功」只能靠
     * **有没有捕获到失败反馈**来判断——{@link CommandCapture} 里记了这个。
     */
    private void runCommand(JsonObject data) {
        String id = data.has("id") ? data.get("id").getAsString() : java.util.UUID.randomUUID().toString();
        String rawCmd = data.has("cmd") ? data.get("cmd").getAsString() : "";
        String cmd = rawCmd.startsWith("/") ? rawCmd.substring(1) : rawCmd;

        if (server == null || cmd.isBlank()) {
            sendCommandResult(id, false, "服务器未就绪或指令为空");
            return;
        }

        CommandCapture capture = new CommandCapture();
        try {
            var source = server.createCommandSourceStack()
                    .withSource(capture)    // ① 收指令输出文本
                    .withCallback(capture)  // ② 收成功/失败信号（文本那路不区分成败）
                    .withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
            server.getCommands().performPrefixedCommand(source, cmd);
            // 「执行了但无输出」是正常成功（tp / kill / say 都不给执行者反馈），
            // 不能把 output 为空当成失败——成败只看 capture 收到的信号。
            sendCommandResult(id, capture.ok(), capture.text());
        } catch (Exception e) {
            // 抛异常 = 明确失败。必须如实上报，否则 AstrBot 侧会当成功照扣好感
            // （Paper 端踩过：那边曾无条件报 ok=true）。
            sendCommandResult(id, false, "执行异常: " + e.getMessage());
        }
    }

    private void sendCommandResult(String id, boolean ok, String output) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "command_result");
        o.addProperty("id", id);
        // ok 是 AstrBot 侧判断「是否退费」的**唯一**依据：false = 这次执行明确失败，必须退费。
        o.addProperty("ok", ok);
        o.addProperty("output", output);
        sendAsync(o);
    }
}
