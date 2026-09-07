package cn.botsentinel.fabric;

import cn.botsentinel.core.LibraryStore;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * BotSentinel v2.2 — Fabric 1.21.4 服务端 mod 入口。
 *
 * 进服前拦截通过 PlayerManagerMixin(checkCanJoin) 实现 —— 在登录阶段拒绝,
 * 不产生任何 join/quit 消息, QQ 桥零感知。
 */
public class BotSentinelMod implements DedicatedServerModInitializer {

    private static volatile MinecraftServer server;
    private static ScheduledExecutorService timer;

    @Override
    public void onInitializeServer() {
        SentinelState.INSTANCE.init();

        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            server = s;
            if (SentinelState.INSTANCE.config.geoEnabled) {
                SentinelState.INSTANCE.geo.initAsync();
            }
            startTimer();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            stopTimer();
            SentinelState.INSTANCE.library.forceSave();
            SentinelState.INSTANCE.stats.forceSave();
            server = null;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ModCommands.register(dispatcher));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayerEntity p = handler.player;
            SentinelState.INSTANCE.onJoin(p.getGameProfile().getName(), ipOf(p));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            ServerPlayerEntity p = handler.player;
            SentinelState.INSTANCE.onQuit(p.getGameProfile().getName(), ipOf(p));
        });

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) ->
                SentinelState.INSTANCE.onChat(
                        sender.getGameProfile().getName(), ipOf(sender), message.getContent().getString()));

        // 每秒检查自动保存(异步落盘在 timer 中做)
        ServerTickEvents.END_SERVER_TICK.register(s -> { /* 预留 */ });
    }

    private static void startTimer() {
        stopTimer();
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BotSentinel-Timer");
            t.setDaemon(true);
            return t;
        });
        SentinelState st = SentinelState.INSTANCE;
        long saveMs = st.config.saveIntervalMinutes * 60000L;
        timer.scheduleAtFixedRate(() -> {
            try {
                st.library.saveIfDirtyAsync();
                st.stats.saveIfDirtyAsync();
                st.ipTracker.cleanup();
            } catch (Exception ignored) {}
        }, saveMs, saveMs, TimeUnit.MILLISECONDS);
        timer.scheduleAtFixedRate(() -> {
            try {
                int removed = st.library.decayAndCleanup();
                if (removed > 0) st.log("[特征库] 衰减清理完成, 移除过期特征 " + removed + " 条");
                st.geo.checkUpdate(false);
            } catch (Exception ignored) {}
        }, 6 * 3600_000L, 6 * 3600_000L, TimeUnit.MILLISECONDS);
    }

    private static void stopTimer() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    // ---------- 工具: 由 SentinelState / ModCommands 调用 ----------

    public static String ipOf(ServerPlayerEntity p) {
        try {
            if (p != null && p.networkHandler != null
                    && ((cn.botsentinel.mixin.ServerCommonNetworkHandlerAccessor) (Object) p.networkHandler)
                            .botsentinel$getConnection() != null) {
                var addr = ((cn.botsentinel.mixin.ServerCommonNetworkHandlerAccessor) (Object) p.networkHandler)
                        .botsentinel$getConnection().getAddress();
                if (addr instanceof InetSocketAddress isa && isa.getAddress() != null) {
                    return isa.getAddress().getHostAddress();
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    public static String ipOfOnline(String name) {
        MinecraftServer s = server;
        if (s == null) return "";
        ServerPlayerEntity p = s.getPlayerManager().getPlayer(name);
        return p == null ? "" : ipOf(p);
    }

    public static void kickOnline(String name, String message) {
        MinecraftServer s = server;
        if (s == null) return;
        ServerPlayerEntity p = s.getPlayerManager().getPlayer(name);
        if (p != null) {
            s.execute(() -> {
                if (p.networkHandler != null) {
                    p.networkHandler.disconnect(Text.literal(message));
                }
            });
        }
    }

    /** 警报推送给在线 OP(权限等级>=2) + 控制台 */
    public static void pushAlert(String msg) {
        MinecraftServer s = server;
        if (s == null) return;
        s.execute(() -> {
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                if (p.hasPermissionLevel(2)) {
                    p.sendMessage(Text.literal("§8[§bBS§8] §7" + msg), false);
                }
            }
        });
    }
}
