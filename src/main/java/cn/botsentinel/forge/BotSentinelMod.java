package cn.botsentinel.forge;

import cn.botsentinel.core.LibraryStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * BotSentinel v2.2 — Forge 1.21.4 服务端 mod 入口。
 *
 * 进服前拦截通过 PlayerListMixin(canPlayerLogin) 实现 —— 在登录阶段拒绝,
 * 不产生任何 join/quit 消息, QQ 桥零感知。
 */
@Mod("botsentinel")
public class BotSentinelMod {

    private static volatile MinecraftServer server;
    static void setServer(MinecraftServer s) { server = s; }
    private static ScheduledExecutorService timer;

    public BotSentinelMod() {
        // v2.4: 事件处理拆分到 SentinelEvents(EB6/EB7 两版, 构建按 MC 版本二选一)
        MinecraftForge.EVENT_BUS.register(new SentinelEvents());
        SentinelState.INSTANCE.init();
    }

    // ---------- 工具 ----------
    public static Path dataDir() {
        return FMLPaths.CONFIGDIR.get().resolve("BotSentinel");
    }

    public static String ipOfOnline(String name) {
        MinecraftServer s = server;
        if (s == null) return "";
        ServerPlayer p = s.getPlayerList().getPlayerByName(name);
        return p == null ? "" : cn.botsentinel.forge.ConnectionIps.remoteIp(p.connection);
    }

    public static void kickOnline(String name, String message) {
        MinecraftServer s = server;
        if (s == null) return;
        ServerPlayer p = s.getPlayerList().getPlayerByName(name);
        if (p != null) {
            s.execute(() -> p.connection.disconnect(Component.literal(message)));
        }
    }

    /** 警报推送给在线 OP(权限等级>=2) + 控制台 */
    public static void pushAlert(String msg) {
        MinecraftServer s = server;
        if (s == null) return;
        s.execute(() -> {
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                if (p.hasPermissions(2)) {
                    p.sendSystemMessage(Component.literal("§8[§bBS§8] §7" + msg));
                }
            }
        });
    }

    static void startTimer() {
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
                st.scoreEngine.flush();
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

    static void stopTimer() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    // 让 PlayerList 事件不 import 未用
    @SuppressWarnings("unused")
    private static final Class<PlayerList> KEEP_REF = PlayerList.class;
}
