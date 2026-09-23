package dev.eyf.netherlink.fabric.mixin;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.Identifier;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.eyf.netherlink.fabric.NetherLinkFabric;

/**
 * 在「玩家获得成就」时上报给 AstrBot。
 *
 * <p><b>为什么必须用 mixin</b>：Fabric API **没有**提供这个事件。
 * {@code net.fabricmc.fabric.api.advancement.v1.AdvancementEvents} 只有
 * REPLACE / MODIFY / ALL_LOADED 三个事件，那是用来**构建与加载**成就定义的，
 * 不是「玩家达成了某个成就」。原版也没有对应的事件回调，所以只能注入。
 *
 * <p>注入点选 {@link PlayerAdvancements#award} 而不是「成就播报的聊天消息」：
 * <ul>
 *   <li>{@code award} 是**成就被授予**这个事实本身，最直接；</li>
 *   <li>同一件事会走 {@code ServerMessageEvents.GAME_MESSAGE}（成就的播报），
 *       但那条路上「是不是成就、是哪个成就」要靠**解析本地化文本**反推，
 *       多语言环境下必然出错；这里能直接拿到 {@link AdvancementHolder}。</li>
 * </ul>
 *
 * <p>⚠️ 与 Paper 端的等价性：Paper 侧用的是 {@code PlayerAdvancementDoneEvent}，
 * 那里同样需要过滤「配方解锁」与「根成就」。本 mixin 的过滤条件与之逐条对齐，
 * 见 {@link #netherlink$onAward}。
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

    /** 玩家引用。{@code award} 里拿不到「是谁」，只能这样取。 */
    @Shadow
    private ServerPlayer player;

    @Inject(method = "award", at = @At("RETURN"))
    private void netherlink$onAward(AdvancementHolder holder, String criterion,
                                    CallbackInfoReturnable<Boolean> cir) {
        try {
            // award 返回 false = 这次调用并没有真正新授予（已经拿过了 / 条件没满足），
            // 不该重复上报——否则同一个成就推进多次会反复通知 AI。
            if (!cir.getReturnValue()) {
                return;
            }
            if (player == null) {
                return;
            }

            Identifier id = holder.id();
            String key = id.toString(); // 形如 "minecraft:story/mine_diamond"

            // 过滤条件与 Paper 端逐条对齐：
            // ① 配方解锁不算成就。原版把配方解锁也做成成就（recipes/ 前缀），
            //    不过滤的话合成一次东西就通知 AI 一次。
            // ⚠️ 注意：原版 id 形如 "minecraft:recipes/building_blocks/xxx"，
            // 前缀要连**命名空间**一起判断，不能只看 path。
            if (key.contains(":recipes/") || key.startsWith("recipes/")) {
                return;
            }
            // ② 根成就是分类的「标题」（如 "story/root"），display 为 absent。
            //    它不是玩家感知的成就，报上去只会让 AI 无意义地加好感。
            if (holder.value().display().isEmpty()) {
                return;
            }

            // ③ 进度确实完成了才算（与 Paper 端 isDone 的语义一致）。
            //    award 返回 true 时通常已经完成，这里再确认一次，防住边缘情况。
            AdvancementProgress progress = ((PlayerAdvancements) (Object) this)
                    .getOrStartProgress(holder);
            if (progress == null || !progress.isDone()) {
                return;
            }

            // 成就的显示名（本地化后的文本）
            String title = Advancement.name(holder)
                    .getString();

            NetherLinkFabric.INSTANCE.reportAdvancement(player.getScoreboardName(), title, key);
        } catch (Exception e) {
            // ⚠️ mixin 里抛异常会连累整个 award 调用（玩家拿不到成就）。
            // 上报失败绝不能影响游戏本身。
            NetherLinkFabric.LOGGER.warn("成就上报失败（已忽略）: {}", e.getMessage());
        }
    }
}
