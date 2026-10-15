package dev.eyf.netherlink.fabric.mixin;

import java.util.Optional;

import com.mojang.authlib.GameProfile;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;

import dev.eyf.netherlink.fabric.NetherLinkFabric;

/**
 * 拦截原版 26.3 的自定义点击回调（{@code ServerboundCustomClickActionPacket}），
 * 把 {@code netherlink:*} 命名空间的点击转给 {@code ActionFlow.onClick}。
 *
 * <p>这是 Fabric 端确认卡 / 本地菜单的**免计分板点击通道**，等价于 Paper 端
 * Adventure 的 {@code ClickEvent.callback}（Paper 的 ClickCallbackProviderImpl
 * 底层发的也是 ServerboundCustomClickActionPacket）。
 *
 * <p>⚠️ 方法 {@code handleCustomClickAction} 实际定义在**父类**
 * {@code ServerCommonPacketListenerImpl}（{@code ServerGamePacketListenerImpl}
 * 只是继承，mixin 注入子类找不到目标 —— 起服即崩，2026-10-15 探针实测）。
 * 所以 mixin 父类；玩家身份经 {@code playerProfile()}（抽象方法，游戏监听器
 * 实现为 {@code this.player}）反查 {@code getPlayerList}。
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class CustomClickMixin {

    /** 父类的抽象方法：由子类返回玩家档案（游戏连接 = this.player 的档案）。 */
    protected abstract GameProfile playerProfile();

    @Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
    private void netherlink$onCustomClick(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        try {
            Identifier id = packet.id();
            if (!id.getNamespace().equals("netherlink")) {
                return;   // 不是我们的，走原版逻辑
            }
            ci.cancel();   // 认领，不再进 MinecraftServer.handleCustomClickAction
            ServerPlayer player = NetherLinkFabric.INSTANCE
                    .findPlayer(playerProfile().name());
            if (player == null) {
                return;
            }
            NetherLinkFabric.INSTANCE.dispatchCustomClick(player, id, packet.payload());
        } catch (Exception e) {
            // 点击回调绝不能把服务端弄崩
            NetherLinkFabric.LOGGER.warn("处理自定义点击失败（忽略）: {}", e.getMessage());
        }
    }
}
