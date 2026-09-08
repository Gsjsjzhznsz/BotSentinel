package cn.botsentinel.mixin;

import cn.botsentinel.forge.SentinelState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 命令监控(普通命令通道): 盯防期内秒发注册类指令(/e /l /login /register...)且参数随机 -> 实锤假人。
 * 与 Bukkit 版 JoinSentinel 的实锤路径 A/B 一致。
 * v2.4: 签名命令通道(1.20.5+)拆分到 SignedGamePacketListenerMixin, 由构建按版本裁剪。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true, require = 0)
    private void botsentinel$onChatCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        if (botsentinel$check(packet.command())) ci.cancel();
    }

    private boolean botsentinel$check(String command) {
        if (player == null) return false;
        try {
            String name = cn.botsentinel.forge.NameOf.of(player.getGameProfile());
            String ip = cn.botsentinel.forge.ConnectionIps.remoteIp(player.connection);
            boolean allow = SentinelState.INSTANCE.onCommand(name, ip, command);
            if (!allow) {
                player.connection.disconnect(Component.literal(SentinelState.INSTANCE.config.disposeMessage));
                return true;
            }
        } catch (Throwable ignored) {
            // 风控永不影响正常命令执行
        }
        return false;
    }
}
