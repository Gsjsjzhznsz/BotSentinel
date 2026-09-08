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

/**
 * 登录阶段拦截 — MC 1.20.2+ 专用(mojmap):
 * canPlayerLogin(ServerLoginPacketListenerImpl, GameProfile), 1.20.1 及更早是
 * (SocketAddress, GameProfile) → 见 PlayerListLegacyMixin。
 * 由 build.gradle 按 minecraft_version 自动二选一打包。
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    // require=0: 未来版本(26.x+)签名漂移时软降级(仅失去进服前拦截, 不崩服)
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true, require = 0)
    private void botsentinel$preLogin(ServerLoginPacketListenerImpl handler, GameProfile profile,
                                      CallbackInfoReturnable<Component> cir) {
        if (profile == null || cn.botsentinel.forge.NameOf.of(profile) == null) return;
        // v2.4: 反射按类型取连接(跨版本无映射依赖, 兼容 1.20.1~26.x)
        String ip = cn.botsentinel.forge.ConnectionIps.remoteIp(handler);

        String deny = SentinelState.INSTANCE.preLoginDecision(cn.botsentinel.forge.NameOf.of(profile), ip);
        if (deny != null) {
            cir.setReturnValue(Component.literal(deny));
        }
    }
}
