package cn.botsentinel;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第一层防线: 进服前拦截。
 *
 * AsyncPlayerPreLoginEvent 在 join 广播之前触发 —— 在这里 deny,
 * 服务端不会产生任何 join/quit 消息, easyBOT 桥自然不会往QQ群转发,
 * 机器人对QQ群完全隐形。
 *
 * v2.2 决策顺序(重要改动: 已登记真人放行提到 IP 封禁之前):
 *   0. 统计
 *   1. 真人名字封禁(被 /atb ban 封过的名字, 任何IP都拒)   <- 即使是信任名单也不放
 *   2. 已登记真人/白名单 -> 直接放行 (修复: 移动CGNAT出口IP被bot连坐误伤真实玩家)
 *   3. 归属地拦截 (geo, v2.2)
 *   4. IP 临时封禁
 *   5. 真人封禁网段连坐
 *   6. 机器人网段封禁 + 逃避拦截
 *   7. 评分决策 (+ v2.2 闪进闪退加分)
 */
public class PreLoginListener implements Listener {

    private final BotSentinelPlugin plugin;

    /** 观察期评分表(name -> score), 供进服后哨兵使用; -1 = 受信任 */
    private final ConcurrentHashMap<String, Integer> sessionScores = new ConcurrentHashMap<>();

    public PreLoginListener(BotSentinelPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;

        String name = event.getName();
        String ip = event.getAddress() == null ? "" : event.getAddress().getHostAddress();
        String key = name.toLowerCase(Locale.ROOT);

        // 0) 统计
        plugin.stats().d.totalScored++;
        plugin.stats().touch();

        // 1) 【真人封禁】账号名命中: /atb ban 封过的真人, 换任何IP都进不来
        LibraryStore.HumanBan hb = plugin.library().activeHumanBan(name);
        if (hb != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.config().humanBanMessage);
            plugin.stats().d.blockedPreLogin++;
            plugin.library().humanEvasionHit(hb);
            plugin.stats().touch();
            plugin.alert().warn("human-name", "[真人封禁] " + name + " (IP: " + ip + regionSuffix(ip)
                    + ") 使用被封账号重进被拒 | 封禁原因: "
                    + (hb.reason.isEmpty() ? "管理员封禁" : hb.reason));
            sessionScores.put(key, -100);
            return;
        }

        // 2) 受信任玩家(已验证/白名单): 直接放行 —— 即使所在IP/网段被封也放行
        //    (v2.2: 移动CGNAT出口IP上bot与真人共享同一个公网IP, IP封禁不再连坐已登记真人)
        if (plugin.library().isVerifiedPlayer(name) || plugin.isWhitelisted(name)) {
            sessionScores.put(key, -1);
            return;
        }

        // 3) 【归属地拦截】国外/港澳台等 (v2.2, 可在 /atb geo 开关)
        if (plugin.config().geoEnabled) {
            GeoRegionManager.Verdict g = plugin.geo().check(ip);
            if (!g.allow) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.config().geoDenyMessage);
                plugin.stats().d.blockedPreLogin++;
                plugin.stats().d.geoBlocked++;
                plugin.stats().touch();
                plugin.alert().warn("geo", "[地区拦截] " + name + " (IP: " + ip + ") 地区: "
                        + g.region + " | " + g.raw + " | 规则: " + g.rule);
                sessionScores.put(key, -100);
                return;
            }
        }

        // 4) 临时封禁中的 IP: 直接拒
        long until = plugin.library().getBannedUntil(ip);
        if (until > System.currentTimeMillis()) {
            long minutes = Math.max(1, (until - System.currentTimeMillis()) / 60000);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.config().banMessage + " §7(剩余约 " + minutes + " 分钟)");
            plugin.stats().d.blockedPreLogin++;
            plugin.stats().touch();
            plugin.alert().warn("ban",
                    "[拦截进服] IP: " + ip + " | 账号: " + name + " | 原因: IP临时封禁中(剩" + minutes + "分钟)");
            sessionScores.put(key, -100);
            return;
        }

        // 5) 【真人逃避拦截】被封真人重启路由器换的新IP仍落在同网段
        //      -> 同网段未登记玩家一律拒(已登记真人/白名单已在上面放行)
        LibraryStore.HumanBan hsb = plugin.library().humanBanBySubnet(ip);
        if (hsb != null && plugin.config().banEvasionBlockNonverified) {
            long remain = hsb.until <= 0 ? -1 : Math.max(1, (hsb.until - System.currentTimeMillis()) / 60000);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.config().banMessage + (remain > 0 ? " §7(剩余约 " + remain + " 分钟)" : ""));
            plugin.stats().d.blockedPreLogin++;
            plugin.stats().d.evasionBlocked++;
            plugin.library().humanEvasionHit(hsb);
            plugin.stats().touch();
            plugin.alert().warn("evade-human", "[真人逃避拦截] " + name + " (IP: " + ip + regionSuffix(ip)
                    + ") 来自被封真人 " + hsb.name + " 的网段(" + hsb.subnet16
                    + ")且未登记, 已拒绝 | " + hsb.name + " 累计逃避" + hsb.evasionHits + "次");
            sessionScores.put(key, -100);
            return;
        }

        // 6) 【封禁规避防御】网段封禁检查: 重启路由器换IP+换用户名也逃不掉
        //    - 名字像机器人(随机名/已知假人名/命中同生成器形态) -> 进服前直接拒
        //    - 名字像真人 -> 放行但列入重点盯防(交给进服后哨兵行为实锤)
        LibraryStore.SubnetBan subnet = plugin.library().activeSubnetBan(ip);
        if (subnet != null) {
            int randomness = plugin.scorer().randomness(name);
            boolean botlike = randomness >= plugin.config().minNameRandomness
                    || plugin.library().isKnownBotName(name)
                    || plugin.library().shapeConfidence(name) >= 0.5;

            if (plugin.config().banEvasionBlockBotlike && botlike) {
                long until2 = subnet.bannedUntil;
                long minutes = Math.max(1, (until2 - System.currentTimeMillis()) / 60000);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        plugin.config().banMessage + " §7(剩余约 " + minutes + " 分钟)");
                plugin.stats().d.blockedPreLogin++;
                plugin.stats().d.evasionBlocked++;
                plugin.stats().touch();

                // 逃避被拦: 计数 + 延长网段封禁(逐犯加重), 同时学习这个新IP和新名字
                int hits = plugin.library().evasionHit(ip, plugin.config().ipBanMinutes,
                        plugin.config().banEvasionMaxMinutes);
                if (plugin.config().autoLearn) {
                    plugin.library().recordBotIp(ip, 0.15, "网段封禁逃避拦截");
                    plugin.library().recordShape(name, 0.10, "网段封禁逃避拦截");
                    plugin.library().recordBotName(name, 0.3, "网段封禁逃避拦截");
                }
                plugin.alert().warn("evade", "[逃避拦截] " + name + " (IP: " + ip + regionSuffix(ip)
                        + ") 换IP重进被拒 | 网段封禁中, 随机度" + randomness
                        + " | 已拦截逃避" + hits + "次, 封禁时长已顺延");
                sessionScores.put(key, -100);
                return;
            }

            // 名字不像机器人: 放行, 但拉满盯防等级(进服后哨兵重点关照)
            sessionScores.put(key, Math.max(60, sessionScore(name)));
            plugin.alert().warn("evade-watch", "[网段观察] " + name + " (IP: " + ip
                    + ") 来自被封网段但名字正常, 放行并重点盯防");
            return;
        }

        // 7) 评分决策
        RiskScorer.Result r = plugin.scorer().evaluate(name, ip);
        plugin.ipTracker().recordNewName(ip, name);

        // v2.2 闪进闪退加分: 日志实锤的 Ciloat77422 模式(进服3秒即断, 反复进出)
        int flash = plugin.ipTracker().flashQuitCount(ip);
        if (flash >= 3) {
            int bonus = Math.min(30, flash * 10);
            r.score += bonus;
            r.reason += " +闪进闪退" + flash + "次(+" + bonus + ")";
            if (r.score >= plugin.config().blockThreshold && r.randomness >= plugin.config().minNameRandomness) {
                r.block = true;
            } else if (r.score >= plugin.config().observeThreshold) {
                r.observe = true;
            }
            if (flash >= 5 && plugin.config().autoLearn) {
                plugin.library().recordBotIp(ip, 0.3, "闪进闪退" + flash + "次");
                plugin.stats().d.flashQuitBlocked++;
            }
        }

        if (r.block) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.config().kickMessage);
            plugin.stats().d.blockedPreLogin++;
            plugin.stats().touch();

            // 拦截即学习: 每次拦截都让特征库更强(自动更新闭环)
            if (plugin.config().autoLearn) {
                plugin.library().recordShape(name, 0.10, "进服前拦截反哺");
                plugin.library().recordBotIp(ip, 0.18, "拦截反哺");
                if (plugin.config().prefixLearn) plugin.library().recordIpPrefix(ip, 0.06, "拦截反哺");
                plugin.stats().d.learnedFromBlocks++;
            }

            // 窗口内新账号过多 -> 自动临时封禁整个IP
            int count = plugin.ipTracker().newNameCount(ip);
            if (plugin.config().ipBanEnabled && count >= plugin.config().autoBanCount) {
                plugin.banIp(ip, plugin.config().ipBanMinutes, "窗口内新账号达" + count + "个");
            }

            plugin.alert().warn("block",
                    "[进服拦截] 账号: " + name + " | IP: " + ip + regionSuffix(ip) + " | " + r.reason
                            + " | 窗口新账号: " + count);
            sessionScores.put(key, -100);
            return;
        }

        if (r.observe) {
            plugin.alert().warn("observe",
                    "[观察] 新账号 " + name + " (IP: " + ip + ") " + r.reason + ", 进服后重点盯防");
        }

        sessionScores.put(key, r.observe ? r.score : 0);

        // 防内存膨胀
        if (sessionScores.size() > 2000) sessionScores.clear();
    }

    /** 警报里附带的归属地信息(库就绪才有) */
    private String regionSuffix(String ip) {
        if (!plugin.config().geoEnabled) return "";
        String raw = plugin.geo().rawRegion(ip);
        if (raw == null) return "";
        return " 地区[" + GeoRegionManager.classify(raw) + "]";
    }

    public int sessionScore(String name) {
        Integer v = sessionScores.get(name.toLowerCase(Locale.ROOT));
        return v == null ? 0 : v;
    }

    public void forget(String name) {
        sessionScores.remove(name.toLowerCase(Locale.ROOT));
    }
}
