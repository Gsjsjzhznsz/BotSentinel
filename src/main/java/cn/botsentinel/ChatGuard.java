package cn.botsentinel;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 广告聊天守卫:
 *  - 特征库签名命中(自动学习的签名持续增长): 撤回消息;
 *    未验证玩家 -> 直接实锤为假人(踢出+封IP+学习新签名)
 *  - 通用域名启发: 仅对未验证且画像可疑的玩家生效, 已登记真人聊天不受影响
 *  - 已验证真人命中签名: 只撤回 + 警报, 不踢人不封IP(protect-known-players)
 */
public class ChatGuard implements Listener {

    private static final Pattern GENERIC_DOMAIN = Pattern.compile(
            "(?:[a-z0-9\\-]+\\.)+(?:cn|com|net|org|xyz|top|cc|vip|shop|club|io|me|tv)");

    private final BotSentinelPlugin plugin;

    public ChatGuard(BotSentinelPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        String name = p.getName();

        // 已验证真人: 只查特征库硬签名
        boolean verified = plugin.library().isVerifiedPlayer(name) || plugin.isWhitelisted(name);
        String message = event.getMessage();
        String sig = plugin.library().matchAdSignature(message);

        if (sig != null) {
            event.setCancelled(true);
            plugin.stats().d.recalledAds++;
            plugin.stats().touch();
            if (verified && plugin.config().protectKnownPlayers) {
                plugin.alert().warn("adknown", "[广告-已登记] " + name + " 消息已撤回 | 命中签名: " + sig
                        + " | 原文: " + abbreviate(message));
                return;
            }
            // 学习新签名(特征库自动更新)
            var learned = plugin.library().learnAdSignature(message);
            plugin.alert().warn("ad", "[广告拦截] " + name + " (IP: " + ip(p)
                    + ") 消息已撤回 | 命中签名: " + sig
                    + (learned.isEmpty() ? "" : " | 新学习签名: " + String.join(",", learned)));
            plugin.joinSentinel().confirmBot(p, "发送广告消息(命中签名" + sig + ")");
            return;
        }

        if (verified || !plugin.config().genericDomainHeuristic) return;

        // 通用域名启发: 只针对画像可疑的未验证玩家
        int randomness = plugin.scorer().randomness(name);
        int session = plugin.preLogin().sessionScore(name);
        String ip = ip(p);
        boolean gated = randomness >= 50 || session >= 45 || plugin.ipTracker().newNameCount(ip) >= 2
                || plugin.library().ipConfidence(ip) >= 0.3;
        if (!gated) return;

        String norm = LibraryStore.normalizeText(message);
        Matcher m = GENERIC_DOMAIN.matcher(norm);
        if (m.find()) {
            event.setCancelled(true);
            plugin.stats().d.recalledAds++;
            plugin.stats().touch();
            plugin.alert().warn("adgen", "[疑似广告-通用域名] " + name + " (IP: " + ip + ") 消息已撤回 | 原文: "
                    + abbreviate(message));
            // 随机名玩家发域名 -> 实锤; 其他只撤回观察
            if (randomness >= 50) {
                plugin.joinSentinel().confirmBot(p, "可疑画像玩家发送域名消息(通用启发)");
            }
        }
    }

    private static String ip(Player p) {
        return p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
    }

    private static String abbreviate(String s) {
        String c = s.replaceAll("§.", "");
        return c.length() > 50 ? c.substring(0, 50) + "..." : c;
    }
}
