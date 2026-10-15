package dev.eyf.netherlink.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 本地菜单（全在 MC 端完成，不经 AstrBot、不消耗好感、离线可用）。
 *
 * <p>与 Paper 端本地菜单一节逐条对应：触发词**整条相等**（排在唤醒词之前）、
 * 触发后就地消费不上报；按钮走 {@link ClickEvent.Custom}（见 CustomClickMixin）。
 * 三项：传送到玩家 / 传送到死亡点 / 展示手上的物品。
 *
 * <p>🔴 传送与展示**共用冷却闸门**（{@code menu-teleport-cooldown} 同值）——
 * 展示是全服广播，连点会刷屏（Paper 端 2026-10-15 修的同一个 bug，这边
 * 从第一天就带上闸门）。
 */
public final class MenuFlow {

    /** 菜单配置（NetherLinkConfig 读进来的一组）。 */
    static final class MenuConfig {
        boolean enable = true;
        List<String> triggers = List.of("菜单", "menu", "メニュー");
        String title = "✆ 菜单 ✆";
        String itemTeleportPlayer = "传送到玩家";
        String itemTeleportDeath = "传送到死亡点";
        String itemShowItem = "展示手上的物品";
        long cooldownSec = 3L;
    }

    /** 玩家 UUID → 上次动作毫秒时间戳。只在主线程访问，无需并发容器。 */
    private final Map<UUID, Long> lastAction = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    private final ActionFlow actions;
    private final ItemTool items;

    MenuFlow(MinecraftServer server, ActionFlow actions, ItemTool items) {
        this.server = server;
        this.actions = actions;
        this.items = items;
    }

    /** 这条聊天是不是菜单触发词（整条相等）。 */
    boolean isTrigger(String plain) {
        return cfg.enable && cfg.triggers.contains(plain.strip());
    }

    private final MenuConfig cfg = new MenuConfig();

    MenuConfig config() {
        return cfg;
    }

    /** 触发菜单：私聊主菜单（主线程）。 */
    void openMainMenu(ServerPlayer player) {
        try {
            player.sendSystemMessage(styled(cfg.title, TextColor.fromRgb(0xFFAA00), true));
            player.sendSystemMessage(menuRule());
            player.sendSystemMessage(Component.literal(" ")
                    .append(menuButton(cfg.itemTeleportPlayer, 0x55FFFF,
                            Component.literal("列出在线玩家，点击传送"),
                            () -> sendPlayerMenu(player)))
                    .append(Component.literal(" "))
                    .append(menuButton(cfg.itemTeleportDeath, 0xFF55FF,
                            Component.literal("传送到上次死亡位置"),
                            () -> teleportToDeath(player))));
            // 第三项单独一行 —— 三个按钮挤一行会超宽
            player.sendSystemMessage(Component.literal(" ")
                    .append(menuButton(cfg.itemShowItem, 0xFFFF55,
                            Component.literal("把手上的物品展示给全服"),
                            () -> showHeldItem(player))));
        } catch (Exception e) {
            NetherLinkFabric.LOGGER.warn("发送菜单失败（{}）: {}",
                    player.getScoreboardName(), e.getMessage());
        }
    }

    private static Component menuRule() {
        return Component.literal(" ✦✦✦✦✦✦✦✦✦✦✦✦✦✦✦✦✦")
                .withStyle(ChatFormatting.DARK_GRAY);
    }

    private static MutableComponent styled(String text, TextColor color, boolean bold) {
        MutableComponent c = Component.literal(text).withStyle(style -> style.withColor(color));
        return bold ? c.withStyle(ChatFormatting.BOLD) : c;
    }

    /** 带一次性点击回调的按钮。 */
    private Component menuButton(String label, int rgb, Component hover, Runnable action) {
        CompoundTag payload = new CompoundTag();
        payload.putString("k", actions.registerMenuAction(action));
        return Component.literal(label)
                .withStyle(style -> style
                        .withColor(TextColor.fromRgb(rgb))
                        .withBold(true)
                        .withClickEvent(new ClickEvent.Custom(
                                ActionFlow.CLICK_MENU, java.util.Optional.of(payload)))
                        .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    /** 第二层：列出在线玩家（每项一个按钮）+ 返回。 */
    private void sendPlayerMenu(ServerPlayer player) {
        try {
            List<ServerPlayer> targets = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!p.getUUID().equals(player.getUUID())) {
                    targets.add(p);
                }
            }
            targets.sort((a, b) -> a.getScoreboardName().compareTo(b.getScoreboardName()));
            player.sendSystemMessage(styled(cfg.itemTeleportPlayer, TextColor.fromRgb(0x55FFFF), true));
            player.sendSystemMessage(menuRule());
            if (targets.isEmpty()) {
                player.sendSystemMessage(Component.literal("  当前没有其他在线玩家。")
                        .withStyle(ChatFormatting.GRAY));
            }
            for (ServerPlayer target : targets) {
                final String name = target.getScoreboardName();
                player.sendSystemMessage(Component.literal(" ")
                        .append(menuButton(name, 0x55FF55,
                                Component.literal("传送到 " + name + " 身边"),
                                () -> teleportToPlayerByName(player, name))));
            }
            player.sendSystemMessage(Component.literal(" ")
                    .append(menuButton("返回", 0xAAAAAA,
                            Component.literal("回到主菜单"),
                            () -> openMainMenu(player))));
        } catch (Exception e) {
            NetherLinkFabric.LOGGER.warn("发送玩家列表失败（{}）: {}",
                    player.getScoreboardName(), e.getMessage());
        }
    }

    /** 冷却检查。返回 true = 这次点击可以执行。 */
    private boolean cooldownOk(ServerPlayer player) {
        if (cfg.cooldownSec <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = lastAction.get(player.getUUID());
        if (last != null && now - last < cfg.cooldownSec * 1000L) {
            return false;
        }
        lastAction.put(player.getUUID(), now);
        return true;
    }

    /** 展示手上的物品给全服（带悬停详情）。🔴 过冷却闸门（全服广播，连点=刷屏）。 */
    private void showHeldItem(ServerPlayer player) {
        if (!cooldownOk(player)) {
            player.sendSystemMessage(Component.literal("展示太频繁，请稍候。")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        ItemStack held = player.getInventory().getSelectedItem();
        if (held.isEmpty()) {
            player.sendSystemMessage(Component.literal("你手上没有东西。")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        String name = held.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)
                ? ItemTool.plain(held.getHoverName())
                : ItemTool.itemId(held);
        int n = Math.max(1, Math.min(held.getCount(), 99));
        Component body = Component.literal("⌜" + name + "⌝ ×" + n)
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withHoverEvent(new HoverEvent.ShowItem(
                                new net.minecraft.world.item.ItemStackTemplate(
                                        held.getItem().builtInRegistryHolder(), n,
                                        held.getComponentsPatch()))));
        Component head = Component.literal("⌜" + player.getScoreboardName() + "⌟ ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("展示了一件物品  ")
                        .withStyle(ChatFormatting.WHITE));
        server.getPlayerList().broadcastSystemMessage(head.copy().append(body), false);
    }

    /** 传送到某玩家（按名字重查；查不到 = 已下线）。 */
    private void teleportToPlayerByName(ServerPlayer player, String targetName) {
        ServerPlayer target = server.getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            player.sendSystemMessage(Component.literal(targetName + " 已不在线。")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (!cooldownOk(player)) {
            player.sendSystemMessage(Component.literal("传送太频繁，请稍候。")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        var ok = player.teleportTo(target.level(), target.getX(), target.getY(),
                target.getZ(), java.util.Set.of(), target.getYRot(), target.getXRot(), true);
        if (ok) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("")
                    .append(styled("⌜" + player.getScoreboardName() + "⌟ ", TextColor.fromRgb(0x55FFFF), false))
                    .append(styled(" 已传送到 ", TextColor.fromRgb(0xFFFF55), false))
                    .append(styled(targetName, TextColor.fromRgb(0x55FFFF), false)), false);
        } else {
            lastAction.remove(player.getUUID());   // 没传成不罚冷却
            player.sendSystemMessage(Component.literal("传送没有成功。")
                    .withStyle(ChatFormatting.RED));
        }
    }

    /** 传送到上次死亡点。 */
    private void teleportToDeath(ServerPlayer player) {
        var locOpt = player.getLastDeathLocation();
        if (locOpt.isEmpty()) {
            player.sendSystemMessage(Component.literal("没有死亡点记录。")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        GlobalPos pos = locOpt.get();
        var level = server.getLevel(pos.dimension());
        if (level == null) {
            player.sendSystemMessage(Component.literal("死亡点所在世界已不可用。")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (!cooldownOk(player)) {
            player.sendSystemMessage(Component.literal("传送太频繁，请稍候。")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        var bp = pos.pos();
        var ok = player.teleportTo(level, bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5,
                java.util.Set.of(), player.getYRot(), player.getXRot(), true);
        if (ok) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("")
                    .append(styled("⌜" + player.getScoreboardName() + "⌟ ", TextColor.fromRgb(0x55FFFF), false))
                    .append(styled(" 已传送到死亡点", TextColor.fromRgb(0xFFFF55), false)), false);
        } else {
            lastAction.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("传送没有成功。")
                    .withStyle(ChatFormatting.RED));
        }
    }

    /** 玩家退出时清冷却记录（Paper 端 onQuit 同款）。 */
    void onQuit(ServerPlayer player) {
        lastAction.remove(player.getUUID());
    }
}
