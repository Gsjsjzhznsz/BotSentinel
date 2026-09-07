package cn.botsentinel.mixin;

import cn.botsentinel.forge.SentinelState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundCommandExecutionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 命令监控: 盯防期内秒发注册类指令(/e /l /login /register...)且参数随机 -> 实锤假人。
 * 与 Bukkit 版 JoinSentinel 的实锤路径 A/B 一致。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleCommand", at = @At("HEAD"), cancellable = true)
    private void botsentinel$onCommand(ServerboundCommandExecutionPacket packet, CallbackInfo ci) {
        if (player == null) return;
        try {
            String name = player.getGameProfile().getName();
            String ip = player.getIpAddress();
            boolean allow = SentinelState.INSTANCE.onCommand(name, ip, packet.command());
            if (!allow) {
                ci.cancel();
                player.connection.disconnect(Component.literal(SentinelState.INSTANCE.config.disposeMessage));
            }
        } catch (Throwable ignored) {
            // 风控永不影响正常命令执行
        }
    }
}
