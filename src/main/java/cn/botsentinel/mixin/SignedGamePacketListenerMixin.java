package cn.botsentinel.mixin;

import cn.botsentinel.forge.SentinelState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v2.4 签名命令通道: MC 1.20.5+ 把签名命令拆到 handleSignedChatCommand,
 * 不拦会漏掉部分机器人命令。仅 1.20.5+ 构建时打包(1.20.1 无此类),
 * 由 build.gradle 按 minecraft_version 自动裁剪。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SignedGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true, require = 0)
    private void botsentinel$onSignedChatCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
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
