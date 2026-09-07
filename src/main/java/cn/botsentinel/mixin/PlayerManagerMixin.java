package cn.botsentinel.mixin;

import cn.botsentinel.fabric.SentinelState;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * 登录阶段拦截: checkCanJoin 是白名单/Ban 的判定点 —— 在 HEAD 注入,
 * 返回非 null Text 即断开连接, 此时玩家尚未进入世界, 不产生 join 消息。
 */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {

    @Inject(method = "checkCanJoin", at = @At("HEAD"), cancellable = true)
    private void botsentinel$preLogin(SocketAddress address, GameProfile profile,
                                      CallbackInfoReturnable<Text> cir) {
        if (profile == null || profile.getName() == null) return;
        String ip = "";
        try {
            if (address instanceof java.net.InetSocketAddress isa && isa.getAddress() != null) {
                ip = isa.getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {}

        String deny = SentinelState.INSTANCE.preLoginDecision(profile.getName(), ip);
        if (deny != null) {
            cir.setReturnValue(Text.literal(deny));
        }
    }
}
