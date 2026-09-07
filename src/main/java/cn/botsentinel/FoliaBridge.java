package cn.botsentinel;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Folia / Lophine / Luminol / Spigot 调度兼容桥。
 * Lophine 是 Folia 分支: 禁止主线程API, 必须走 Region/Entity/Async 调度。
 */
public final class FoliaBridge {

    private static final boolean FOLIA = detectFolia();

    private FoliaBridge() {}

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean isFolia() { return FOLIA; }

    public static String kernelName() { return FOLIA ? "Folia/Region" : "Bukkit/Main"; }

    /** 全局异步执行(库/文件/计算) */
    public static void runAsync(Plugin plugin, Runnable task) {
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, (t) -> task.run());
        } catch (Throwable foliaOnly) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /** 全局异步定时(毫秒) */
    public static void runAsyncTimer(Plugin plugin, long delayMs, long periodMs, Runnable task) {
        try {
            Bukkit.getAsyncScheduler().runAtFixedRate(plugin, (t) -> task.run(),
                    delayMs, periodMs, TimeUnit.MILLISECONDS);
        } catch (Throwable foliaOnly) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task,
                    Math.max(1, delayMs / 50), Math.max(1, periodMs / 50));
        }
    }

    /** 实体所属线程执行(踢人/发消息等实体操作); 非Folia回退主线程 */
    public static void runEntity(Plugin plugin, Entity entity, Runnable task) {
        if (entity == null || !entity.isValid()) return;
        try {
            entity.getScheduler().run(plugin, (t) -> task.run(), null);
        } catch (Throwable foliaOnly) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (entity.isValid()) task.run();
            });
        }
    }

    /** 实体延迟执行(毫秒; EntityScheduler按tick计) */
    public static void runEntityLater(Plugin plugin, Entity entity, long delayMs, Runnable task) {
        if (entity == null || !entity.isValid()) return;
        try {
            entity.getScheduler().runDelayed(plugin, (t) -> task.run(), null, Math.max(1, delayMs / 50));
        } catch (Throwable foliaOnly) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (entity.isValid()) task.run();
            }, Math.max(1, delayMs / 50));
        }
    }
}
