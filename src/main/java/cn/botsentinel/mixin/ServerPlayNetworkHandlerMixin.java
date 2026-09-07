package cn.botsentinel.mixin;

import cn.botsentinel.fabric.BotSentinelMod;
import cn.botsentinel.fabric.SentinelState;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 命令监控: 盯防期内秒发注册类指令(/e /l /login /register...)且参数随机 -> 实锤假人。
 * 与 Bukkit 版 JoinSentinel 的实锤路径 A/B 一致。
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Shadow
    public abstract ServerPlayerEntity getPlayer();

    @Inject(method = "onCommandExecution(Lnet/minecraft/network/packet/c2s/play/CommandExecutionC2SPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void botsentinel$onCommand(CommandExecutionC2SPacket packet, CallbackInfo ci) {
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
