package cn.botsentinel;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 警报总线: 控制台 + 在线OP(权限 botsentinel.alert)。
 * 按类别限速, 避免攻击波刷屏; 被限速的条数汇总到下一条警报。
 */
public class AlertBus {

    private final BotSentinelPlugin plugin;
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private final Map<String, Integer> suppressed = new ConcurrentHashMap<>();

    private static final long THROTTLE_MS = 10_000;

    public AlertBus(BotSentinelPlugin plugin) {
        this.plugin = plugin;
    }

    /** 一般信息: 只进控制台日志 */
    public void info(String msg) {
        plugin.getLogger().info(msg);
    }

    /** 警报: 控制台 + OP; category 相同时 10 秒内合并 */
    public void warn(String category, String msg) {
        plugin.stats().d.warnings++;
        plugin.stats().touch();
        long now = System.currentTimeMillis();
        Long last = lastSent.get(category);
        if (last != null && now - last < THROTTLE_MS) {
            suppressed.merge(category, 1, Integer::sum);
            return;
        }
        lastSent.put(category, now);
        Integer sup = suppressed.remove(category);
        String full = "[BotSentinel] " + msg + (sup != null ? " §8(合并了" + sup + "条同类警报)" : "");
        plugin.getLogger().warning(plain(full));

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("botsentinel.alert")) {
                FoliaBridge.runEntity(plugin, p, () -> p.sendMessage(full));
            }
        }
    }

    private static String plain(String s) {
        return s.replace("§0", "").replace("§1", "").replace("§2", "").replace("§3", "")
                .replace("§4", "").replace("§5", "").replace("§6", "").replace("§7", "")
                .replace("§8", "").replace("§9", "").replace("§a", "").replace("§b", "")
                .replace("§c", "").replace("§d", "").replace("§e", "").replace("§f", "")
                .replace("§k", "").replace("§l", "").replace("§m", "").replace("§n", "").replace("§o", "").replace("§r", "");
    }
}
