package dev.eyf.netherlink.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;

/**
 * 物品操作基石：解析 {@code id[组件]}、槽位寻址、计数、组件合并。
 *
 * <p>与 Paper 端（{@code netherlink-plugin} 的同名能力）逐条对应：
 * <ul>
 *   <li>{@code buildItem} ↔ Paper 的 {@code Bukkit.getItemFactory().createItemStack(spec)}
 *       —— 26.3 原版的 {@link ItemParser} 就是 {@code /give} 用的同一套解析器</li>
 *   <li>{@code hasNonDefault(type)} ↔ Paper 的 {@code isDataOverridden}（「显式写上去的」）</li>
 *   <li>{@code applyComponents(patch)} ↔ Paper 的 {@code copyDataFrom(temp, temp::isDataOverridden)}
 *       —— 这里合并语义更直接：ItemParser 产出的 patch 本来就只含显式写的组件</li>
 * </ul>
 *
 * <p>槽位名与 Paper 端 {@code actions._SLOTS} 白名单一致：
 * {@code weapon.mainhand} / {@code weapon.offhand} / {@code armor.head|chest|legs|feet} /
 * {@code hotbar.0~8} / {@code inventory.9~35}。
 *
 * <p>⚠️ 26.3 的装备不在 {@code Inventory.items} 里（那是 36 格背包），在
 * {@code Inventory.equipment}（{@link net.minecraft.world.entity.EntityEquipment}）；
 * {@code Inventory.getItem(int)} 会自动路由，但负数下标不认 —— 装备一律走
 * {@link #readSlot} 的显式分支。
 */
public final class ItemTool {

    private final MinecraftServer server;

    public ItemTool(MinecraftServer server) {
        this.server = server;
    }

    // ------------------------------------------------------------------
    // 构造：id[组件] → ItemStack
    // ------------------------------------------------------------------

    /**
     * 把 {@code id} 或 {@code id[组件]} 造成 {@link ItemStack}。
     * 造不出来返回 {@link ItemStack#EMPTY}（不抛 —— 与 Paper 端 buildItem 返回 null 同口径）。
     */
    public ItemStack buildItem(String id, int n, String components) {
        String spec = components == null || components.isEmpty()
                ? id : id + "[" + components + "]";
        try {
            var parser = new net.minecraft.commands.arguments.item.ItemParser(
                    server.registryAccess());
            var input = parser.parse(new StringReader(spec));
            ItemStack one = input.createItemStack(Math.max(1, n));
            return one;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /** 这件物品的 {@code minecraft:xxx} ID（不带组件）。 */
    public static String itemId(ItemStack it) {
        return it.getItem().builtInRegistryHolder().getRegisteredName();
    }

    /** {@code minecraft:xxx} → 取命名空间段比较用。 */
    static boolean isOurNamespace(net.minecraft.resources.Identifier id) {
        return id.getNamespace().equals("netherlink");
    }

    // ------------------------------------------------------------------
    // 槽位寻址
    // ------------------------------------------------------------------

    /** {@code weapon.mainhand} 等装备槽位名 → {@link EquipmentSlot}；不是装备槽返回 null。 */
    static EquipmentSlot toEquipSlot(String slot) {
        switch (slot) {
            case "weapon.mainhand":
            case "weapon":
                return EquipmentSlot.MAINHAND;
            case "weapon.offhand":
                return EquipmentSlot.OFFHAND;
            case "armor.head":
                return EquipmentSlot.HEAD;
            case "armor.chest":
                return EquipmentSlot.CHEST;
            case "armor.legs":
                return EquipmentSlot.LEGS;
            case "armor.feet":
                return EquipmentSlot.FEET;
            default:
                return null;
        }
    }

    /** {@code hotbar.N} / {@code inventory.N} → 背包下标（0~35）；不是这两种返回 null。 */
    static Integer toInventoryIndex(String slot) {
        try {
            if (slot.startsWith("hotbar.")) {
                int i = Integer.parseInt(slot.substring("hotbar.".length()));
                return i >= 0 && i <= 8 ? i : null;
            }
            if (slot.startsWith("inventory.")) {
                int i = Integer.parseInt(slot.substring("inventory.".length()));
                return i >= 9 && i <= 35 ? i : null;
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    /** 读玩家某个槽位上的物品；空格 / 槽位名不认识返回 {@link ItemStack#EMPTY}。 */
    public static ItemStack readSlot(ServerPlayer player, String slot) {
        EquipmentSlot es = toEquipSlot(slot);
        if (es != null) {
            return player.getItemBySlot(es);
        }
        Integer idx = toInventoryIndex(slot);
        if (idx != null) {
            return player.getInventory().getItem(idx);
        }
        return ItemStack.EMPTY;
    }

    /** 写回某个槽位（takeSlot 减数量后、applyModify 合并后都用它落格）。 */
    public static void writeSlot(ServerPlayer player, String slot, ItemStack it) {
        EquipmentSlot es = toEquipSlot(slot);
        if (es != null) {
            player.setItemSlot(es, it);
            return;
        }
        Integer idx = toInventoryIndex(slot);
        if (idx != null) {
            player.getInventory().setItem(idx, it);
        }
    }

    /** 身上 + 背包的全部槽位名（手上 → 装备 → 快捷栏 → 背包）。校验用（完整清单）。 */
    public static List<String> allSlots() {
        List<String> out = new ArrayList<>();
        out.add("weapon.mainhand");
        out.add("weapon.offhand");
        out.add("armor.head");
        out.add("armor.chest");
        out.add("armor.legs");
        out.add("armor.feet");
        for (int i = 0; i < 9; i++) {
            out.add("hotbar." + i);
        }
        for (int i = 9; i < 36; i++) {
            out.add("inventory." + i);
        }
        return out;
    }

    /**
     * **枚举**用：手持那一格只以 {@code weapon.mainhand} 出现。
     *
     * 🔴 与 Paper 端同一课（2026-10-14）：{@code weapon.mainhand} 与
     * {@code hotbar.<手持格>} 是**同一格**，全列会把手上那件报两次。
     */
    public static List<String> listingSlots(ServerPlayer player) {
        int held = player.getInventory().getSelectedSlot();   // 0..8
        List<String> out = new ArrayList<>();
        for (String slot : allSlots()) {
            if (!slot.equals("hotbar." + held)) {
                out.add(slot);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 计数 / 扣除
    // ------------------------------------------------------------------

    /**
     * 数玩家身上有多少个该物品（**只比类型，不看组件**）。
     * 与 Paper 端 countItem 同判据；装备位也数（slotsHolding 对齐）。
     */
    public int countItem(ServerPlayer player, String id) {
        ItemStack proto = buildItem(id, 1, "");
        if (proto.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = player.getInventory().getItem(i);
            if (it.getItem() == proto.getItem()) {
                total += it.getCount();
            }
        }
        for (EquipmentSlot es : new EquipmentSlot[]{EquipmentSlot.MAINHAND,
                EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack it = player.getItemBySlot(es);
            if (it.getItem() == proto.getItem()) {
                total += it.getCount();
            }
        }
        return total;
    }

    /** 数某个槽位上有几个（空格 / 槽位名不认识都算 0）。 */
    public static int countSlot(ServerPlayer player, String slot) {
        ItemStack it = readSlot(player, slot);
        return it.isEmpty() ? 0 : it.getCount();
    }

    /**
     * 背包里这一种类型**散落在哪些槽位**（歧义报错要列出具体格位）。
     */
    public List<String> slotsHolding(ServerPlayer player, String id) {
        ItemStack proto = buildItem(id, 1, "");
        List<String> out = new ArrayList<>();
        if (proto.isEmpty()) {
            return out;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack it = player.getInventory().getItem(i);
            if (it.getItem() == proto.getItem()) {
                out.add(i <= 8 ? "hotbar." + i : "inventory." + i);
            }
        }
        for (EquipmentSlot es : new EquipmentSlot[]{EquipmentSlot.MAINHAND,
                EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack it = player.getItemBySlot(es);
            if (it.getItem() == proto.getItem()) {
                out.add(es == EquipmentSlot.MAINHAND ? "weapon.mainhand"
                        : es == EquipmentSlot.OFFHAND ? "weapon.offhand"
                        : "armor." + es.getSerializedName());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 组件合并（modify 语义）
    // ------------------------------------------------------------------

    /**
     * 以 {@code cur} 为基底、把 {@code spec}（{@code 组件串}）里**显式写的**组件套上去。
     *
     * Paper 端是 {@code copyDataFrom(temp, temp::isDataOverridden)}；这里更直接：
     * {@link ItemParser} 产出的 {@link DataComponentPatch} 本来就只含显式写的
     * 组件（{@code !} 前缀的移除项是 {@code set(type, null)}），
     * {@code applyComponents} 原地打补丁，其余保持原样 —— **合并不是替换**。
     */
    public ItemStack buildMergedItem(ItemStack cur, String spec) {
        try {
            var parser = new net.minecraft.commands.arguments.item.ItemParser(
                    server.registryAccess());
            var input = parser.parse(new StringReader(
                    itemId(cur) + "[" + spec + "]"));
            ItemStack merged = cur.copy();
            merged.applyComponents(input.components());
            return merged;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    // ------------------------------------------------------------------
    // 序列化（inspect 用：给 AI 读的文本）
    // ------------------------------------------------------------------

    /** 名字或物品 ID 里含 {@code match}（不区分大小写）就算命中。 */
    public static boolean matchesName(ItemStack it, String match) {
        String m = match.toLowerCase(Locale.ROOT);
        if (itemId(it).toLowerCase(Locale.ROOT).contains(m)) {
            return true;
        }
        return plain(it.getHoverName()).toLowerCase(Locale.ROOT).contains(m);
    }

    /**
     * 一件物品 → **给 AI 读的一段**（首行带物品 ID 与槽位，AI 拿它填回别的动作参数）。
     *
     * 🔴 只报「显式写上去的」组件（{@link ItemStack#hasNonDefault}）——
     * 把物品类型的默认值（裸钻石剑的 attack_speed）报出去，AI 会照抄进
     * components、把默认值写死（Paper 端真机踩过，坑同源）。
     */
    public static String formatItemForAi(ItemStack it, String slot) {
        StringBuilder sb = new StringBuilder();
        sb.append("⌜").append(plain(it.getHoverName())).append("⌝ (")
                .append(itemId(it)).append(") ×").append(it.getCount())
                .append("   [").append(slot).append(']');
        if (it.hasNonDefault(DataComponents.ENCHANTMENTS)) {
            ItemEnchantments en = it.get(DataComponents.ENCHANTMENTS);
            List<String> es = new ArrayList<>();
            for (var e : en.entrySet()) {
                // 与 Paper 端一致：报 path（不带命名空间）
                es.add(e.getKey().unwrapKey()
                        .map(k -> k.identifier().getPath()).orElse("?")
                        + " " + e.getIntValue());
            }
            if (!es.isEmpty()) {
                sb.append("\n   附魔: ").append(String.join(", ", es));
            }
        }
        if (it.hasNonDefault(DataComponents.ATTRIBUTE_MODIFIERS)) {
            ItemAttributeModifiers am = it.get(DataComponents.ATTRIBUTE_MODIFIERS);
            List<String> as = new ArrayList<>();
            for (ItemAttributeModifiers.Entry e : am.modifiers()) {
                // 26.3 原版 operation 的 getSerializedName 本来就是 add_value 这些
                //（Paper 端要手工映射，这里直接用原版名）
                as.add(fmtAmount(e.modifier().amount()) + " "
                        + e.attribute().unwrapKey()
                                .map(k -> k.identifier().getPath()).orElse("?")
                        + " (" + e.modifier().operation().getSerializedName()
                        + ", slot=" + slotGroupName(e.slot()) + ")");
            }
            if (!as.isEmpty()) {
                sb.append("\n   属性: ").append(String.join(", ", as));
            }
        }
        if (it.hasNonDefault(DataComponents.LORE)) {
            ItemLore lore = it.get(DataComponents.LORE);
            List<String> ls = new ArrayList<>();
            for (net.minecraft.network.chat.Component c : lore.lines()) {
                ls.add(plain(c));
            }
            if (!ls.isEmpty()) {
                sb.append("\n   说明: ").append(String.join(" / ", ls));
            }
        }
        if (it.hasNonDefault(DataComponents.UNBREAKABLE)) {
            sb.append("\n   不可破坏");
        }
        if (it.hasNonDefault(DataComponents.RARITY)) {
            Object r = it.get(DataComponents.RARITY);
            sb.append("\n   稀有度: ").append(r);
        }
        return sb.toString();
    }

    /** {@link EquipmentSlotGroup} 的原版序列名（{@code any} / {@code mainhand} …）。 */
    private static String slotGroupName(EquipmentSlotGroup g) {
        return g.getSerializedName();
    }

    /** 数字去掉浮点尾巴（{@code -2.4000000953674316} → {@code -2.4}），带符号。 */
    static String fmtAmount(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return (v >= 0 ? "+" : "") + s;
    }

    /** Component → 纯文本（丢样式）。 */
    public static String plain(net.minecraft.network.chat.Component c) {
        return c == null ? "" : c.getString();
    }
}
