package dev.eyf.netherlink.fabric;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * 动作层：{@code action_offer} 确认卡渲染 + steps 执行 + 点击回调分发。
 *
 * <p>与 Paper 端（{@code netherlink-plugin} 的 handleActionOffer / sendConfirmCard /
 * runSteps / applyStep / countItem / takeItems …）逐条对应，协议相同：
 * <ul>
 *   <li>入帧 {@code action_offer{token, player, card, steps}} → 存 steps → 私聊卡片</li>
 *   <li>玩家点「确认/取消」→ {@link #onClick}（由 MinecraftServerMixin 转来）→
 *       runSteps → 出帧 {@code action_result{token, player, ok, detail}}</li>
 * </ul>
 *
 * <p>🔴 点击回调走原版 26.3 的 {@link ClickEvent.Custom}（客户端发
 * {@code ServerboundCustomClickActionPacket} 回服务端，{@code MinecraftServer.handleCustomClickAction}
 * 非 final，mixin 拦截）—— **不用计分板**，与 Paper 的 {@code ClickEvent.callback} 同体验：
 * 无 /trigger 回显、无轮询、无需解锁。
 *
 * <p>🔴 steps 语义与 Paper 端一致：
 * <ol>
 *   <li>所有 check 先干跑，任一不过整体中止（一个都不动）</li>
 *   <li>按类型扣之前查歧义（同种物品散落多格 → 列出格位拒绝）</li>
 *   <li>modify 是**合并**（{@link ItemTool#buildMergedItem}），没点名的组件保留</li>
 * </ol>
 */
public final class ActionFlow {

    /** 点击回调的 id 前缀（token 编进 payload）。 */
    static final Identifier CLICK_CONFIRM = Identifier.fromNamespaceAndPath("netherlink", "confirm");
    static final Identifier CLICK_CANCEL = Identifier.fromNamespaceAndPath("netherlink", "cancel");
    static final Identifier CLICK_MENU = Identifier.fromNamespaceAndPath("netherlink", "menu");

    /**
     * 一次待确认的卡片：token → steps（JSON 字符串）。
     * 🔴 必须并发安全：WS 线程写、主线程（点击回调）读删 —— Paper 端这里
     * 曾用普通 HashMap 丢过条目（症状：点了确认没反应）。
     */
    private final Map<Integer, String> pendingActions = new ConcurrentHashMap<>();

    /** menu 点击的 payload → 动作（本地菜单用，见 MenuFlow）。 */
    private final Map<String, Runnable> menuActions = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    private final ItemTool items;

    ActionFlow(MinecraftServer server, ItemTool items) {
        this.server = server;
        this.items = items;
    }

    // ------------------------------------------------------------------
    // 点击回调入口（MinecraftServerMixin 转来，已在主线程）
    // ------------------------------------------------------------------

    /**
     * 玩家点了带 {@link ClickEvent.Custom} 的聊天消息。
     *
     * @return true = 我们认这个 id（mixin 就不再走原版逻辑）
     */
    public boolean onClick(ServerPlayer player, Identifier id,
                           java.util.Optional<net.minecraft.nbt.Tag> payload) {
        net.minecraft.nbt.CompoundTag tag = payload.orElse(null)
                instanceof net.minecraft.nbt.CompoundTag ct ? ct : null;
        if (CLICK_CONFIRM.equals(id)) {
            int token = tag != null && tag.contains("t") ? tag.getIntOr("t", 0) : 0;
            if (token != 0) {
                runSteps(player, token);
            }
            return true;
        }
        if (CLICK_CANCEL.equals(id)) {
            int token = tag != null && tag.contains("t") ? tag.getIntOr("t", 0) : 0;
            String raw = token == 0 ? null : pendingActions.remove(token);
            if (raw != null) {
                player.sendSystemMessage(Component.literal("已取消。")
                        .withStyle(ChatFormatting.GRAY));
                reportActionResult(token, player.getScoreboardName(), false, "玩家取消");
            }
            return true;
        }
        if (CLICK_MENU.equals(id)) {
            String key = tag != null && tag.contains("k") ? tag.getStringOr("k", "") : "";
            Runnable act = menuActions.remove(key);
            if (act != null) {
                act.run();
            }
            return true;
        }
        return false;
    }

    /** 菜单注册一次性点击动作（MenuFlow 用）。 */
    String registerMenuAction(Runnable action) {
        String key = UUID.randomUUID().toString();
        menuActions.put(key, action);
        return key;
    }

    // ------------------------------------------------------------------
    // action_offer → 确认卡
    // ------------------------------------------------------------------

    void handleActionOffer(JsonObject data) {
        final int token = data.has("token") ? data.get("token").getAsInt() : 0;
        final String playerName = data.has("player") ? data.get("player").getAsString() : "";
        final JsonObject card = data.has("card") ? data.getAsJsonObject("card") : new JsonObject();
        final JsonArray steps = data.has("steps") ? data.getAsJsonArray("steps") : new JsonArray();
        if (token == 0 || playerName.isEmpty()) {
            NetherLinkFabric.LOGGER.warn("action_offer 缺 token 或 player，忽略");
            return;
        }
        pendingActions.put(token, steps.toString());
        NetherLinkFabric.INSTANCE.runOnMainThread(() -> {
            ServerPlayer p = server.getPlayerList().getPlayerByName(playerName);
            if (p == null) {
                reportActionResult(token, playerName, false, "玩家不在线");
                return;
            }
            sendConfirmCard(p, token, card);
        });
    }

    // ------------------------------------------------------------------
    // 卡片渲染
    // ------------------------------------------------------------------

    /** 分隔线（与 Paper 端同款 16 个 ━）。 */
    private static Component cardRule() {
        return Component.literal(" ━━━━━━━━━━━━━━━".repeat(1) + "━━━━━━━━━━━━━━")
                .withStyle(ChatFormatting.DARK_GRAY);
    }

    /** 一个带 hover 的可点击按钮（自定义点击回调）。 */
    private static Component actionButton(String label, ChatFormatting color,
                                          String hover, ClickEvent event) {
        return Component.literal(label)
                .withStyle(style -> style
                        .withColor(color)
                        .withBold(true)
                        .withClickEvent(event)
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static Component styled(String text, ChatFormatting color, boolean bold) {
        MutableComponent c = Component.literal(text).withStyle(color);
        return bold ? c.withStyle(ChatFormatting.BOLD) : c;
    }

    void sendConfirmCard(ServerPlayer player, int token, JsonObject card) {
        try {
            String title = card.has("title") ? card.get("title").getAsString() : "确认请求";
            player.sendSystemMessage(cardRule());
            player.sendSystemMessage(Component.literal("")
                    .append(styled("      ✦ ", ChatFormatting.GRAY, false))
                    .append(styled(title, ChatFormatting.GOLD, true))
                    .append(styled(" ✦", ChatFormatting.GRAY, false)));
            player.sendSystemMessage(Component.empty());
            JsonArray lines = card.has("lines") ? card.getAsJsonArray("lines") : new JsonArray();
            for (JsonElement le : lines) {
                JsonObject line = le.getAsJsonObject();
                MutableComponent row = Component.literal("  ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(styled(line.get("label").getAsString() + ": ",
                                ChatFormatting.GRAY, false));
                if (line.has("items")) {
                    Map<String, ItemStack> slotCache = new HashMap<>();
                    for (JsonElement ie : line.getAsJsonArray("items")) {
                        JsonObject it = ie.getAsJsonObject();
                        String id = it.has("id") ? it.get("id").getAsString() : "";
                        int n = it.has("count") ? it.get("count").getAsInt() : 1;
                        String disp = it.has("name") && !it.get("name").getAsString().isEmpty()
                                ? it.get("name").getAsString() : id;
                        ItemStack show = cardItemStack(player, line, it, id, n, slotCache);
                        // 🔴 show 为 EMPTY 时不能造 ItemStackTemplate(对 AIR 抛
                        //    IllegalStateException)—— 整张卡会渲染失败(真机踩过)。
                        //    空物品退回纯文本, 卡片宁可少个悬停也不能发不出去。
                        MutableComponent chunk = Component.literal("[" + disp + "]")
                                .withStyle(ChatFormatting.AQUA);
                        if (!show.isEmpty()) {
                            chunk = chunk.withStyle(style -> style
                                    .withColor(ChatFormatting.AQUA)
                                    .withHoverEvent(itemHover(show)));
                        }
                        row.append(chunk).append(Component.literal(" ×" + n + "  ")
                                .withStyle(ChatFormatting.WHITE));
                    }
                }
                if (line.has("text")) {
                    row.append(styled(line.get("text").getAsString(),
                            ChatFormatting.WHITE, false));
                }
                player.sendSystemMessage(row);
            }
            if (card.has("cost_value") && card.get("cost_value").getAsInt() > 0) {
                player.sendSystemMessage(Component.literal("  花费 ")
                        .withStyle(ChatFormatting.WHITE)
                        .append(styled(String.valueOf(card.get("cost_value").getAsInt()),
                                ChatFormatting.YELLOW, false))
                        .append(Component.literal(" 好感").withStyle(ChatFormatting.WHITE)));
            }
            player.sendSystemMessage(Component.empty());
            String ch = card.has("confirm_hover") ? card.get("confirm_hover").getAsString() : "同意";
            String xh = card.has("cancel_hover") ? card.get("cancel_hover").getAsString() : "否决，无消耗";
            sendButtons(player, token, ch, xh);
            player.sendSystemMessage(cardRule());
        } catch (Exception e) {
            NetherLinkFabric.LOGGER.warn("渲染确认卡失败（{}）",
                    player.getScoreboardName(), e);
        }
    }

    private void sendButtons(ServerPlayer player, int token, String ch, String xh) {
        // 载荷用 NBT（ClickEvent.Custom 的 payload 是 Optional<Tag>）：
        // {"t": token} → CompoundTag
        net.minecraft.nbt.CompoundTag pt = new net.minecraft.nbt.CompoundTag();
        pt.putInt("t", token);
        player.sendSystemMessage(Component.literal("  ")
                .append(actionButton("[ 确认 ]", ChatFormatting.GREEN, ch,
                        new ClickEvent.Custom(CLICK_CONFIRM, java.util.Optional.of(pt))))
                .append(Component.literal("    "))
                .append(actionButton("[ 取消 ]", ChatFormatting.RED, xh,
                        new ClickEvent.Custom(CLICK_CANCEL, java.util.Optional.of(pt)))));
    }

    /**
     * 物品 → 带完整组件的悬停（{@link ItemStackTemplate} 带组件，与 Paper 的 asHoverEvent 同口径）。
     * 🔴 空物品必须返回 null（ItemStackTemplate 构造器对 AIR 抛 IllegalStateException，
     * 一张卡里任何一个物品空着都会让整张卡渲染失败 —— 2026-10-15 探针真机踩过）。
     */
    private static HoverEvent itemHover(ItemStack show) {
        if (show.isEmpty()) {
            return null;
        }
        return new HoverEvent.ShowItem(new ItemStackTemplate(
                show.getItem().builtInRegistryHolder(),
                Math.max(1, Math.min(show.getCount(), 99)),
                show.getComponentsPatch()));
    }

    /**
     * 卡片上要显示的那件物品（三条路：项带 slot → 读真实那件 / 组件合并预览；项带 components → 造；
     * 裸 id → 造）。与 Paper 端 cardItemStack 逐条对应。
     */
    private ItemStack cardItemStack(ServerPlayer player, JsonObject line, JsonObject it,
                                    String id, int n, Map<String, ItemStack> slotCache) {
        String comps = it.has("components") && !it.get("components").isJsonNull()
                ? it.get("components").getAsString() : "";
        if (it.has("slot")) {
            String slot = it.get("slot").getAsString();
            ItemStack real = slotCache.computeIfAbsent(slot, s -> ItemTool.readSlot(player, s));
            if (!real.isEmpty()) {
                ItemStack shown = comps.isEmpty() ? real : items.buildMergedItem(real, comps);
                if (!shown.isEmpty()) {
                    return shown;
                }
                NetherLinkFabric.LOGGER.warn("卡片的合并预览造不出来，退回原物品 | slot={} components={}",
                        slot, comps);
                return real;
            }
            return ItemStack.EMPTY;
        }
        if (comps.isEmpty() && line.has("slots")) {
            for (JsonElement se : line.getAsJsonArray("slots")) {
                String slot = se.getAsString();
                ItemStack real = slotCache.computeIfAbsent(slot, s -> ItemTool.readSlot(player, s));
                if (!real.isEmpty()) {
                    return real;
                }
            }
        }
        String full = id.contains(":") ? id : "minecraft:" + id;
        ItemStack one = comps.isEmpty() ? ItemStack.EMPTY : items.buildItem(full, 1, comps);
        if (one.isEmpty()) {
            if (!comps.isEmpty()) {
                NetherLinkFabric.LOGGER.warn("卡片物品的组件造不出来，悬停退回裸物品 | id={} components={}",
                        id, comps);
            }
            one = items.buildItem(full, Math.max(1, Math.min(n, 99)), "");
        }
        return one;
    }

    // ------------------------------------------------------------------
    // steps 执行
    // ------------------------------------------------------------------

    void runSteps(ServerPlayer player, int token) {
        String rawSteps = pendingActions.remove(token);
        if (rawSteps == null) {
            return;   // 已处理过 / 已取消
        }
        try {
            JsonArray steps = JsonParser.parseString(rawSteps).getAsJsonArray();
            // ① 预校验：所有会失败的先干跑，任一不过整体中止
            for (JsonElement se : steps) {
                JsonObject st = se.getAsJsonObject();
                String op = st.get("op").getAsString();
                String problem = null;
                if ("check".equals(op)) {
                    if (st.has("slot")) {
                        String slot = st.get("slot").getAsString();
                        int need = st.get("count").getAsInt();
                        int have = ItemTool.countSlot(player, slot);
                        if (have < need) {
                            problem = have == 0
                                    ? "那一格是空的：" + slot
                                    : "槽位 " + slot + " 上只有 " + have + " 个，不够 " + need;
                        }
                    } else {
                        String id = st.get("item").getAsString();
                        int need = st.get("count").getAsInt();
                        int have = items.countItem(player, id);
                        if (have < need) {
                            problem = "材料不足：⌜" + id + "⌝ 需要 " + need + "，你有 " + have;
                        }
                    }
                } else if ("give".equals(op)) {
                    String id = st.get("item").getAsString();
                    String comps = stepComponents(st);
                    if (items.buildItem(id, 1, comps).isEmpty()) {
                        problem = "物品写法有问题，服务器造不出来：" + id
                                + (comps.isEmpty() ? "" : "[" + comps + "]");
                    }
                } else if ("modify".equals(op)) {
                    problem = validateModify(player, st);
                }
                if (problem != null) {
                    player.sendSystemMessage(Component.literal(problem).withStyle(ChatFormatting.RED));
                    reportActionResult(token, player.getScoreboardName(), false, problem);
                    return;
                }
            }
            // ①b 歧义检查：按类型扣时同种物品散落多格 → 拒绝并列出格位
            for (JsonElement se : steps) {
                JsonObject st = se.getAsJsonObject();
                if (!"clear".equals(st.get("op").getAsString()) || st.has("slot")) {
                    continue;
                }
                String id = st.get("item").getAsString();
                java.util.List<String> where = items.slotsHolding(player, id);
                if (where.size() >= 2) {
                    String problem = "⌜" + id + "⌝ 在你背包里有 " + where.size()
                            + " 件，分散在 " + String.join("、", where)
                            + " —— 要交哪一件？请说清（或用槽位写法）。本次没有扣任何东西。";
                    player.sendSystemMessage(Component.literal(problem).withStyle(ChatFormatting.RED));
                    reportActionResult(token, player.getScoreboardName(), false, problem);
                    return;
                }
            }
            // ② 全通过才动手
            for (JsonElement se : steps) {
                JsonObject st = se.getAsJsonObject();
                if ("check".equals(st.get("op").getAsString())) {
                    continue;
                }
                String err = applyStep(player, st);
                if (err != null) {
                    player.sendSystemMessage(Component.literal(err).withStyle(ChatFormatting.RED));
                    reportActionResult(token, player.getScoreboardName(), false, err);
                    return;
                }
            }
            reportActionResult(token, player.getScoreboardName(), true, "");
        } catch (Exception e) {
            NetherLinkFabric.LOGGER.warn("执行 steps 失败（{}）: {}",
                    player.getScoreboardName(), e.getMessage());
            reportActionResult(token, player.getScoreboardName(), false, "执行异常：" + e.getMessage());
        }
    }

    private static String stepComponents(JsonObject st) {
        return st.has("components") && !st.get("components").isJsonNull()
                ? st.get("components").getAsString() : "";
    }

    private String validateModify(ServerPlayer player, JsonObject st) {
        String slot = st.get("slot").getAsString();
        ItemStack cur = ItemTool.readSlot(player, slot);
        if (cur.isEmpty()) {
            return "那个槽位是空的：" + slot;
        }
        String spec = st.get("raw").getAsString();
        if (items.buildMergedItem(cur, spec).isEmpty()) {
            return "组件写法有问题，服务器造不出来：" + spec;
        }
        return null;
    }

    private String applyStep(ServerPlayer player, JsonObject st) {
        String op = st.get("op").getAsString();
        // command 步必须最先看（没有 item/slot 键）
        if ("command".equals(op)) {
            String cmd = st.has("cmd") ? st.get("cmd").getAsString() : "";
            if (cmd.isEmpty()) {
                return "命令是空的（动作模板没渲染出东西）";
            }
            return runConsoleCommand(cmd);
        }
        if ("modify".equals(op)) {
            return applyModify(player, st);
        }
        if (st.has("slot")) {
            String slot = st.get("slot").getAsString();
            int n = st.get("count").getAsInt();
            if ("clear".equals(op)) {
                return takeSlot(player, slot, n);
            }
            return "槽位寻址只支持 check / clear，实际：" + op;
        }
        String id = st.get("item").getAsString();
        int n = st.get("count").getAsInt();
        String comps = stepComponents(st);
        if ("clear".equals(op)) {
            return takeItems(player, id, n);
        }
        if ("give".equals(op)) {
            return giveItems(player, id, n, comps);
        }
        return "未知的步骤类型：" + op;
    }

    /** 以控制台身份跑一条命令（与 runCommand 同一捕获方式）。 */
    private String runConsoleCommand(String cmd) {
        try {
            CommandCapture capture = new CommandCapture();
            var source = server.createCommandSourceStack()
                    .withSource(capture)
                    .withCallback(capture)
                    .withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS);
            server.getCommands().performPrefixedCommand(source, cmd);
            return capture.ok() ? null
                    : "命令执行失败：" + cmd + "（" + capture.text() + "）";
        } catch (Exception e) {
            return "命令执行出错：" + cmd + "（" + e.getMessage() + "）";
        }
    }

    /** 改玩家已有物品的组件（合并、原地替换同一格）。 */
    private String applyModify(ServerPlayer player, JsonObject st) {
        String slot = st.get("slot").getAsString();
        ItemStack cur = ItemTool.readSlot(player, slot);
        if (cur.isEmpty()) {
            return "那个槽位是空的：" + slot;
        }
        String spec = st.get("raw").getAsString();
        ItemStack built = items.buildMergedItem(cur, spec);
        if (built.isEmpty()) {
            return "组件写法有问题，服务器造不出来：" + spec;
        }
        ItemTool.writeSlot(player, slot, built);
        return null;
    }

    /** 发物品；超一组按组补；背包满了掉地上（与 /give 行为一致，不算失败）。 */
    private String giveItems(ServerPlayer player, String id, int n, String components) {
        ItemStack proto = items.buildItem(id, 1, components);
        if (proto.isEmpty()) {
            return "物品写法有问题，服务器造不出来：" + id
                    + (components.isEmpty() ? "" : "[" + components + "]");
        }
        int max = Math.max(1, proto.getMaxStackSize());
        int left = n;
        while (left > 0) {
            int chunk = Math.min(left, max);
            ItemStack part = proto.copy();
            part.setCount(chunk);
            if (!player.getInventory().add(part)) {
                var leftover = part.copy();
                var drop = player.createItemStackToDrop(leftover, true, false);
                if (drop != null) {
                    player.level().addFreshEntity(drop);
                }
            }
            left -= chunk;
        }
        return null;
    }

    /** 从背包按类型扣 n 个（判据与 countItem 完全一致）。 */
    private String takeItems(ServerPlayer player, String id, int n) {
        ItemStack proto = items.buildItem(id, 1, "");
        if (proto.isEmpty()) {
            return "物品写法有问题：" + id;
        }
        int left = n;
        for (int i = 0; i < 36 && left > 0; i++) {
            ItemStack it = player.getInventory().getItem(i);
            if (it.getItem() != proto.getItem()) {
                continue;
            }
            int take = Math.min(left, it.getCount());
            it.shrink(take);
            left -= take;
        }
        for (EquipmentSlot es : new EquipmentSlot[]{EquipmentSlot.MAINHAND,
                EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (left <= 0) {
                break;
            }
            ItemStack it = player.getItemBySlot(es);
            if (it.getItem() != proto.getItem()) {
                continue;
            }
            int take = Math.min(left, it.getCount());
            it.shrink(take);
            player.setItemSlot(es, it.getCount() <= 0 ? ItemStack.EMPTY : it);
            left -= take;
        }
        return left > 0 ? "扣材料时发现不够（应有 " + n + "，差 " + left + "）" : null;
    }

    /** 从指定槽位扣 n 个。 */
    private String takeSlot(ServerPlayer player, String slot, int n) {
        ItemStack it = ItemTool.readSlot(player, slot);
        if (it.isEmpty()) {
            return "那个槽位是空的：" + slot;
        }
        if (it.getCount() < n) {
            return "槽位 " + slot + " 上只有 " + it.getCount() + " 个，不够 " + n + " 个";
        }
        it.shrink(n);
        ItemTool.writeSlot(player, slot, it.getCount() <= 0 ? ItemStack.EMPTY : it);
        return null;
    }

    /** 回报 AstrBot：执行结果（它据此决定扣不扣好感）。 */
    void reportActionResult(int token, String player, boolean ok, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "action_result");
        o.addProperty("token", token);
        o.addProperty("player", player);
        o.addProperty("ok", ok);
        o.addProperty("detail", detail);
        NetherLinkFabric.INSTANCE.sendAsync(o);
    }
}
