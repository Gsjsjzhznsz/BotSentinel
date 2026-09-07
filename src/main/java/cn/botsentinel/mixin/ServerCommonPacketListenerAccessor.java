package cn.botsentinel.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 ServerCommonPacketListenerImpl#connection (protected) 用于登录阶段取玩家IP。
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonPacketListenerAccessor {

    @Accessor("connection")
    Connection botsentinel$getConnection();
}
