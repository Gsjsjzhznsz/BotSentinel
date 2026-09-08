package cn.botsentinel;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第二层防线: 进服后行为哨兵。
 *
 * 针对 2026-09-05 实锤的漏网模式:
 *   进服 -> 1秒内 "/e <随机密码> <随机密码>" -> 被限速器踢出
 * 三条实锤路径(任一命中即"确认假人", 自动反哺特征库 + 踢出 + IP临时封禁):
 *   A. 进服后 instant-register-seconds 秒内发注册类指令且参数为随机串(4/4全部命中)
 *   B. 盯防期内注册类指令累计 confirm-auth-attempts 次
 *   C. 被命令限速器踢出(理由含"速度太快"/spam)且名字/IP已可疑
 * 真人保护:
 *   - 已验证真人完全跳过; 自动信任(在线满N分钟)持续扩充真人名单
 *   - 实锤门槛都带名字随机性/IP风险条件, 正常名字的玩家数学上不可能触发
 */
public class JoinSentinel implements Listener {

    private static class Track {
        long joinTs;
        volatile int authAttempts;
        volatile boolean confirmed;
        volatile boolean flagged;
        volatile String lastRoot = "";      // 最近用过的根命令(v2.3 自适应学习用)
        volatile boolean lastRandomArgs;    // 最近命令参数是否随机串
    }

    private final BotSentinelPlugin plugin;
    private final Map<String, Track> tracks = new ConcurrentHashMap<>();

    public JoinSentinel(BotSentinelPlugin plugin) {
        this.plugin = plugin;
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String name = p.getName();
        Track t = new Track();
        t.joinTs = System.currentTimeMillis();
        tracks.put(key(name), t);

        // 客户端指纹采集: 进服后2秒与10秒各采一次(客户端信息包稍晚于进服到达)
        FoliaBridge.runEntityLater(plugin, p, 2000, () -> captureFingerprint(p));
        FoliaBridge.runEntityLater(plugin, p, 10000, () -> captureFingerprint(p));

        // 已验证真人: 无需盯防
        if (plugin.library().isVerifiedPlayer(name) || plugin.isWhitelisted(name)) return;
    }

    /** 采集客户端品牌/语言/协议/IP 并与真人封禁库比对 */
    private void captureFingerprint(Player p) {
        if (!p.isOnline() || !plugin.config().banEvasionFingerprint) return;
        String name = p.getName();
        String brand = "";
        try { brand = p.getClientBrandName() == null ? "" : p.getClientBrandName(); } catch (Throwable ignored) {}
        String ip = ipOf(p);
        plugin.library().recordFingerprint(name, brand, localeOf(p), viaProtocol(p.getUniqueId()), ip);

        // 已登记真人/白名单: 只记录, 不比对
        if (plugin.library().isVerifiedPlayer(name) || plugin.isWhitelisted(name)) return;

        for (LibraryStore.HumanBan ban : plugin.library().activeHumanBans()) {
            if (ban.name.equalsIgnoreCase(name)) continue; // 同名进服前已拦
            boolean brandMatch = !ban.brand.isEmpty() && !brand.isEmpty()
                    && ban.brand.equalsIgnoreCase(brand);
            if (!brandMatch) continue;
            // 误伤防护: vanilla客户端人人都一样, 必须同/24网段才算数;
            // 非通用客户端(启动器专属品牌)本身就是强特征, 单独即可实锤
            boolean subnet24 = !ban.subnet24.isEmpty()
                    && ban.subnet24.equals(LibraryStore.subnetKey(ip, 24));
            if ("vanilla".equalsIgnoreCase(brand) && !subnet24) continue;

            plugin.library().humanEvasionHit(ban);
            plugin.stats().d.evasionBlocked++;
            plugin.stats().touch();
            final String kickMsg = plugin.config().banMessage;
            FoliaBridge.runEntity(plugin, p, () -> {
                if (p.isOnline()) p.kickPlayer(kickMsg);
            });
            plugin.alert().warn("evade-human", "[真人逃避拦截] " + name + " (IP: " + ip + regionSuffix(ip)
                    + ") 客户端指纹命中已封禁真人 " + ban.name
                    + " | 客户端=" + brand + " 语言=" + localeOf(p) + " 网段=" + LibraryStore.subnetKey(ip, 16)
                    + " | 累计逃避" + ban.evasionHits + "次");
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        Track t = tracks.get(key(p.getName()));
        if (t == null || t.confirmed) return;

        String msg = event.getMessage();
        if (msg.length() < 2) return;
        String[] parts = msg.substring(1).split("\\s+");
        if (parts.length == 0) return;
        String root = parts[0].toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);

        // 已验证真人: 正常注册/登录, 不统计
        if (plugin.library().isVerifiedPlayer(p.getName()) || plugin.isWhitelisted(p.getName())) return;

        // v2.3 泛化: 命令表(可配置) + 本服自适应学习库 + (可选)任意命令检测
        //   —— 不再绑定特定服务器的注册命令, 移植到别的服务器也能自适应
        boolean authListed = plugin.config().authCommands.contains(root)
                || plugin.library().isLearnedAuthCommand(root);
        if (!authListed && !plugin.config().anyCommandDetect) return;

        long sinceJoin = System.currentTimeMillis() - t.joinTs;
        String ip = p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
        int randomness = plugin.scorer().randomness(p.getName());
        int session = plugin.preLogin().sessionScore(p.getName());
        int ipCount = plugin.ipTracker().newNameCount(ip);

        // 可疑门槛: 观察名单 / 名字随机 / 同IP新账号多 —— 正常名字玩家不会进入本分支
        boolean suspicious = session >= 35 || randomness >= 35 || ipCount >= 2 || ipRisk(ip);
        if (!suspicious) return;

        // 参数随机串判定: 两个相同参数(注册确认式) 或 单个无元音长参数
        boolean randomArgs = false;
        if (parts.length >= 3) {
            String a1 = parts[1], a2 = parts[2];
            if (a1.length() >= 6 && a1.equals(a2)) randomArgs = true;
        }
        if (!randomArgs && parts.length >= 2) {
            String a1 = parts[1];
            if (a1.length() >= 8) {
                String low = a1.toLowerCase(Locale.ROOT);
                int vowels = 0;
                for (char c : low.toCharArray()) if ("aeiou".indexOf(c) >= 0) vowels++;
                if (vowels * 4 < a1.length()) randomArgs = true; // 元音占比<25%
            }
        }
        t.lastRoot = root;
        t.lastRandomArgs = randomArgs;

        // A: 秒发命令实锤(命令在注册类表内=强信号; 任意命令+随机参数=弱信号)
        if (randomArgs && sinceJoin <= plugin.config().instantRegisterSeconds * 1000L
                && (randomness >= 50 || session >= 45 || ipCount >= 2)) {
            if (authListed) {
                confirmBot(p, "秒发注册指令(" + (sinceJoin / 1000) + "秒内, 参数随机串)");
                event.setCancelled(true);
                return;
            }
            // 任意命令(非注册类): 降级为计数信号, 连续两次才实锤(防误伤真人在首秒发其他命令)
            t.authAttempts += 1;
            if (t.authAttempts >= plugin.config().confirmAuthAttempts + 1
                    && sinceJoin <= plugin.config().watchSeconds * 1000L) {
                confirmBot(p, "盯防期内连续可疑命令" + t.authAttempts + "次(参数随机串)");
            }
            return;
        }

        if (!authListed) return; // 非注册类命令且未达随机参数条件: 只记录不计数

        // B: 盯防期内累计注册尝试
        t.authAttempts++;
        if (t.authAttempts >= plugin.config().confirmAuthAttempts
                && sinceJoin <= plugin.config().watchSeconds * 1000L) {
            confirmBot(p, "盯防期内注册类指令" + t.authAttempts + "次(名字随机度" + randomness + ")");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        Player p = event.getPlayer();
        Track t = tracks.get(key(p.getName()));
        String reason = event.getReason() == null ? "" : event.getReason();
        boolean limiter = reason.contains("速度太快") || reason.toLowerCase(Locale.ROOT).contains("spam")
                || reason.contains("Too fast");
        if (!limiter || t == null || t.confirmed) return;
        if (plugin.library().isVerifiedPlayer(p.getName())) return;

        int randomness = plugin.scorer().randomness(p.getName());
        int session = plugin.preLogin().sessionScore(p.getName());
        String ip = p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
        int ipCount = plugin.ipTracker().newNameCount(ip);

        // C: 限速器踢出 + 可疑画像 -> 实锤 (当日4只漏网bot全部死于此, 此前特征被白白丢弃)
        if (randomness >= 50 || session >= 45 || ipCount >= 2 || ipRisk(ip)) {
            t.flagged = true;
            confirmBot(p, "被命令限速器踢出且画像可疑(随机度" + randomness + "/观察分" + session + ")");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        String name = p.getName();
        String ip = ipOf(p);
        Track t = tracks.remove(key(name));
        if (t != null && !t.confirmed) {
            int minutes = (int) ((System.currentTimeMillis() - t.joinTs) / 60000L);
            if (!t.flagged && minutes >= plugin.config().autoTrustMinutes) {
                // 自动信任: 真人名单自动成长(特征库自动更新的一部分) + 小模型在线学习
                plugin.library().addKnownPlayer(name, true, "自动信任:在线" + minutes + "分钟无异常");
                plugin.scoreEngine().learnHuman(name);
            }
            if (minutes > 0) plugin.library().addPlayMinutes(name, minutes);
        }

        // v2.2 闪进闪退检测: 停留<20秒即断(日志实锤 Ciloat77422 模式)
        // 只记录未实锤/未信任的玩家, 真人掉线重连一般会停留更久
        if (t != null && !t.confirmed && !t.flagged) {
            long dwellMs = System.currentTimeMillis() - t.joinTs;
            if (dwellMs < 20_000L && !plugin.library().isVerifiedPlayer(name)) {
                int fq = plugin.ipTracker().recordFlashQuit(ip);
                if (fq >= 3) {
                    plugin.stats().d.flashQuitBlocked++;
                    plugin.stats().touch();
                    plugin.alert().warn("flash", "[闪进闪退] " + ip + " 窗口内短停留进出 " + fq
                            + " 次(最近: " + name + " 停留" + (dwellMs / 1000) + "秒)"
                            + (fq >= 5 ? " , 已提升该IP风险信誉" : ", 继续观察"));
                }
            }
        }
    }

    /** 警报里附带的归属地信息(库就绪才有) */
    private String regionSuffix(String ip) {
        if (!plugin.config().geoEnabled) return "";
        String raw = plugin.geo().rawRegion(ip);
        if (raw == null) return "";
        return " 地区[" + GeoRegionManager.classify(raw) + "]";
    }

    private boolean ipRisk(String ip) {
        return plugin.library().ipConfidence(ip) >= 0.3
                || (plugin.config().prefixLearn && plugin.library().ipPrefixConfidence(ip) >= 0.5)
                || plugin.ipTracker().newNameCount(ip) >= 3;
    }

    private static String ipOf(Player p) {
        return p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
    }

    private static String localeOf(Player p) {
        try { return p.getLocale() == null ? "" : p.getLocale(); } catch (Throwable t) { return ""; }
    }

    /** ViaVersion 协议版本(软依赖反射, 未安装返回-1) */
    public static int viaProtocol(java.util.UUID uuid) {
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            Object v = api.getClass().getMethod("getPlayerVersion", java.util.UUID.class).invoke(api, uuid);
            return (v instanceof Number num) ? num.intValue() : -1;
        } catch (Throwable t) { return -1; }
    }

    /** 确认假人: 学习特征库 + 踢出 + 临时IP封禁 (供哨兵/聊天守卫/手动learn调用) */
    public void confirmBot(Player p, String reason) {
        if (p == null || !p.isOnline()) return;
        String name = p.getName();
        Track t = tracks.get(key(name));
        if (t != null && t.confirmed) return;
        if (t != null) t.confirmed = true;

        String ip = p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();

        // 学习(特征库自动更新核心)
        if (plugin.config().autoLearn) {
            plugin.library().recordBotName(name, 0.5, reason);
            plugin.library().recordShape(name, 0.25, reason);
            plugin.library().recordBotIp(ip, 0.35, reason);
            if (plugin.config().prefixLearn) plugin.library().recordIpPrefix(ip, 0.08, reason);
        }

        // v2.3: 本服命令自适应学习 —— 实锤bot用过的注册类命令直接学进库,
        // 移植到其他服务器/AuthMe变体/模组登录插件也能自动掌握
        if (plugin.config().autoLearnAuthCommands && t != null && t.lastRandomArgs
                && t.lastRoot != null && !t.lastRoot.isEmpty()) {
            plugin.library().learnAuthCommand(t.lastRoot, "实锤bot自动学习");
        }

        // v2.3: 在线小模型训练(实锤=正样本)
        plugin.scoreEngine().learnBot(name);

        // 踢出(实体线程)
        FoliaBridge.runEntity(plugin, p, () -> {
            if (p.isOnline()) p.kickPlayer(plugin.config().disposeMessage);
        });

        // 临时IP封禁
        if (plugin.config().ipBanEnabled) {
            plugin.banIp(ip, plugin.config().ipBanMinutes, reason);
        }

        plugin.stats().d.disposedBots++;
        plugin.stats().touch();
        plugin.alert().warn("dispose", "[处置假人] " + name + " (IP: " + ip + regionSuffix(ip) + ") | " + reason
                + " | 已学习其名字/形态/IP进特征库");
    }

    public void shutdownCleanup() { tracks.clear(); }
}
