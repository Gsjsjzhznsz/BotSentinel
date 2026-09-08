package cn.botsentinel.mixin;

import cn.botsentinel.fabric.BotSentinelMod;
import cn.botsentinel.fabric.SentinelState;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * v2.4 签名命令通道: MC 1.20.5+ 把签名命令拆到 onChatCommandSigned,
 * 不拦会漏掉部分机器人命令。仅 1.20.5+ 构建时打包(1.20.1 无此类),
 * 由 build.gradle 按 minecraft_version 自动裁剪。
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class SignedCommandHandlerMixin {

    @Shadow
    public abstract ServerPlayerEntity getPlayer();

    @Inject(method = "onChatCommandSigned", at = @At("HEAD"), cancellable = true, require = 0)
    private void botsentinel$onSignedCommand(ChatCommandSignedC2SPacket packet, CallbackInfo ci) {
        ServerPlayerEntity player = this.getPlayer();
        if (player == null) return;
        try {
            String name = player.getGameProfile().getName();
            String ip = BotSentinelMod.ipOf(player);
            boolean allow = SentinelState.INSTANCE.onCommand(name, ip, packet.command());
            if (!allow) {
                ci.cancel();
                BotSentinelMod.kickOnline(name, SentinelState.INSTANCE.config.disposeMessage);
            }
        } catch (Throwable ignored) {
            // 风控永不影响正常命令执行
        }
    }
}
