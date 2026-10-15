package dev.eyf.netherlink.fabric;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * {@code inspect}：查玩家背包，把结果序列化成**给 AI 读的文本**回传。
 *
 * <p>与 Paper 端 handleInspect 逐条对应，入/出帧相同：
 * <ul>
 *   <li>入帧 {@code {type:"inspect", id, player, slot?, match?, limit?}}</li>
 *   <li>出帧 {@code {type:"inspect_result", id, text}}</li>
 * </ul>
 *
 * <p>🔴 只读 —— 不动任何东西、不扣好感、不发确认卡；结果只给 AI 看。
 * 🔴 枚举走 {@link ItemTool#listingSlots}（手持格不列两次，2026-10-14 的教训），
 * 校验用 {@link ItemTool#allSlots}（AI 明确写 hotbar.N 照样读得到）。
 */
public final class InspectFlow {

    private final MinecraftServer server;

    InspectFlow(MinecraftServer server) {
        this.server = server;
    }

    void handleInspect(JsonObject data) {
        String id = data.has("id") ? data.get("id").getAsString() : "";
        String playerName = data.has("player") ? data.get("player").getAsString() : "";
        String wantSlot = data.has("slot") ? data.get("slot").getAsString().strip() : "";
        String match = data.has("match") ? data.get("match").getAsString().strip() : "";
        int limit = data.has("limit") ? data.get("limit").getAsInt() : 10;
        if (limit <= 0) {
            limit = 10;
        }
        final String rid = id;
        final int flimit = limit;
        final String fslot = wantSlot;
        final String fmatch = match;
        NetherLinkFabric.INSTANCE.runOnMainThread(() -> {
            ServerPlayer p = server.getPlayerList().getPlayerByName(playerName);
            if (p == null) {
                reportInspect(rid, "玩家 " + playerName + " 不在线，查不到。");
                return;
            }
            List<String> slots = new ArrayList<>(ItemTool.listingSlots(p));
            if (!fslot.isEmpty()) {
                if (!ItemTool.allSlots().contains(fslot)) {
                    reportInspect(rid, "槽位 " + fslot + " 不认识。可用："
                            + String.join(" / ", ItemTool.allSlots()));
                    return;
                }
                slots.clear();
                slots.add(fslot);
            }
            List<String> lines = new ArrayList<>();
            for (String slot : slots) {
                ItemStack it = ItemTool.readSlot(p, slot);
                if (it.isEmpty()) {
                    continue;
                }
                if (!fmatch.isEmpty() && !ItemTool.matchesName(it, fmatch)) {
                    continue;
                }
                lines.add(ItemTool.formatItemForAi(it, slot));
                if (lines.size() >= flimit) {
                    break;
                }
            }
            if (lines.isEmpty()) {
                String q = "槽位=" + (fslot.isEmpty() ? "全部" : fslot);
                if (!fmatch.isEmpty()) {
                    q += "，名字含「" + fmatch + "」";
                }
                reportInspect(rid, playerName + " 那里没查到符合的物品（" + q + "）。");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(playerName).append(" 查到 ").append(lines.size()).append(" 件：\n");
            for (int i = 0; i < lines.size(); i++) {
                sb.append(i + 1).append(". ").append(lines.get(i)).append('\n');
            }
            if (lines.size() >= flimit) {
                sb.append("（只列了前 ").append(flimit)
                        .append(" 件 —— 要更多就加 slot 或 match 把范围收窄）");
            }
            reportInspect(rid, sb.toString().strip());
        });
    }

    private static void reportInspect(String id, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "inspect_result");
        o.addProperty("id", id);
        o.addProperty("text", text);
        NetherLinkFabric.INSTANCE.sendAsync(o);
    }
}
