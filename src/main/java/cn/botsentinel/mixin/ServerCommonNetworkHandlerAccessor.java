package cn.botsentinel.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 ServerCommonNetworkHandler#connection (protected) 用于取玩家IP。
 */
@Mixin(ServerCommonNetworkHandler.class)
public interface ServerCommonNetworkHandlerAccessor {

    @Accessor("connection")
    ClientConnection botsentinel$getConnection();
}
