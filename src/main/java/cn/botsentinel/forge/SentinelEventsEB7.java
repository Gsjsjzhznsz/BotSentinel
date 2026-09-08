package cn.botsentinel.forge;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;

/**
 * 事件处理(非 public 类, 构建时按 MC 版本二选一打包):
 * EventBus 6 = 1.20.1~1.21.5 (net.minecraftforge.eventbus.api)
 * EventBus 7 = 1.21.6+      (net.minecraftforge.eventbus7.api, 包名重命名)
 * 由 build.gradle 按 minecraft_version 自动裁剪。
 */
class SentinelEvents {

    // ---------- 生命周期 ----------
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent e) {
        BotSentinelMod.setServer(e.getServer());
        if (SentinelState.INSTANCE.config.geoEnabled) {
            SentinelState.INSTANCE.geo.initAsync();
        }
        BotSentinelMod.startTimer();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent e) {
        BotSentinelMod.stopTimer();
        SentinelState.INSTANCE.library.forceSave();
        SentinelState.INSTANCE.stats.forceSave();
        BotSentinelMod.setServer(null);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent e) {
        ModCommands.register(e.getDispatcher());
    }

    // ---------- 进出服 ----------
    @SubscribeEvent
    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        SentinelState.INSTANCE.onJoin(cn.botsentinel.forge.NameOf.of(p.getGameProfile()), cn.botsentinel.forge.ConnectionIps.remoteIp(p.connection));
    }

    @SubscribeEvent
    public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        SentinelState.INSTANCE.onQuit(cn.botsentinel.forge.NameOf.of(p.getGameProfile()), cn.botsentinel.forge.ConnectionIps.remoteIp(p.connection));
    }

    // ---------- 聊天守卫 ----------
    // v2.4: EventBus 7 的取消机制 = 方法返回 boolean(true=取消), 不再有 setCanceled
    @SubscribeEvent
    public boolean onChat(ServerChatEvent e) {
        ServerPlayer p = e.getPlayer();
        boolean allow = SentinelState.INSTANCE.onChat(
                cn.botsentinel.forge.NameOf.of(p.getGameProfile()), cn.botsentinel.forge.ConnectionIps.remoteIp(p.connection), e.getRawText());
        if (!allow) {
            if (SentinelState.INSTANCE.sessionScore(cn.botsentinel.forge.NameOf.of(p.getGameProfile())) == -100) {
                p.connection.disconnect(Component.literal(SentinelState.INSTANCE.config.disposeMessage));
            }
            return true;
        }
        return false;
    }

}
