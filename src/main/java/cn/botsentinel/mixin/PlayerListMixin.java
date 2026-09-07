package cn.botsentinel.mixin;

import cn.botsentinel.forge.SentinelState;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * 登录阶段拦截: canPlayerLogin 是白名单/Ban 的判定点(mojmap) —— 在 HEAD 注入,
 * 返回非 null Component 即断开连接, 此时玩家尚未进入世界, 不产生 join 消息。
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void botsentinel$preLogin(ServerLoginPacketListenerImpl handler, GameProfile profile,
                                      CallbackInfoReturnable<Component> cir) {
        if (profile == null || profile.getName() == null) return;
        String ip = "";
        try {
            SocketAddress addr = ((ServerCommonPacketListenerAccessor) handler).botsentinel$getConnection().getRemoteAddress();
            if (addr instanceof InetSocketAddress isa && isa.getAddress() != null) {
                ip = isa.getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {
        }

        String deny = SentinelState.INSTANCE.preLoginDecision(profile.getName(), ip);
        if (deny != null) {
            cir.setReturnValue(Component.literal(deny));
        }
    }
}
