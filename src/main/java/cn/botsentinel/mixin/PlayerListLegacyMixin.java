package cn.botsentinel.mixin;

import cn.botsentinel.forge.SentinelState;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * 登录阶段拦截 — MC 1.20.1 及更早版本专用:
 * 1.20.1 的 canPlayerLogin 签名是 (SocketAddress, GameProfile),
 * 1.20.2+ 改为 (ServerLoginPacketListenerImpl, GameProfile) → 见 PlayerListMixin。
 * 由 build.gradle 按 minecraft_version 自动二选一打包。
 */
@Mixin(PlayerList.class)
public abstract class PlayerListLegacyMixin {

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true, require = 0)
    private void botsentinel$preLogin(SocketAddress address, GameProfile profile,
                                      CallbackInfoReturnable<Component> cir) {
        if (profile == null || profile.getName() == null) return;
        String ip = "";
        try {
            if (address instanceof InetSocketAddress isa && isa.getAddress() != null) {
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
