package cn.botsentinel.fabric;

import cn.botsentinel.core.GeoRegionManager;
import cn.botsentinel.core.IpRiskTracker;
import cn.botsentinel.core.IpUtil;
import cn.botsentinel.core.LibraryStore;
import cn.botsentinel.core.RiskScorer;
import cn.botsentinel.core.ScoreEngine;
import cn.botsentinel.core.StatsStore;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fabric 端业务中枢 —— 与 Bukkit 版 PreLoginListener/JoinSentinel/ChatGuard 相同的判定逻辑。
 *
 * 决策顺序(与插件版 v2.2 一致):
 *   1. 真人名字封禁 -> 2. 信任放行 -> 3. 归属地 -> 4. IP临时封禁
 *   -> 5. 真人网段连坐 -> 6. bot网段+逃避 -> 7. 评分(+闪进闪退)
 */
public final class SentinelState {

    public static final SentinelState INSTANCE = new SentinelState();

    private static final Pattern GENERIC_DOMAIN = Pattern.compile(
            "(?:[a-z0-9\\-]+\\.)+(?:cn|com|net|org|xyz|top|cc|vip|shop|club|io|me|tv)");

    public SentinelConfig config;
    public LibraryStore library;
    public StatsStore stats;
    public RiskScorer scorer;
    public ScoreEngine scoreEngine;
    public IpRiskTracker ipTracker;
    public GeoRegionManager geo;

    /** 会话观察分(name -> score); -1=受信任, -100=已拦截 */
    public final Map<String, Integer> sessionScores = new ConcurrentHashMap<>();
    /** 进服时间戳(name -> ts), 用于闪进闪退/自动信任 */
    public final Map<String, long[]> joinStamps = new ConcurrentHashMap<>();

    private SentinelState() {}

    // ---------- 生命周期 ----------
    public void init() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("BotSentinel");
        config = SentinelConfig.load(dir.resolve("config.json"));
        library = new LibraryStore(dir.resolve("library.json"));
        stats = new StatsStore(dir.resolve("stats.json"));
        library.configure(config.decayDays, config.removeBelow);
        scoreEngine = new ScoreEngine(dir.resolve("model.json"));
        scoreEngine.configure(config.scoringEngine, config.scoringWHeuristic, config.scoringWMarkov,
                config.scoringWEntropy, config.scoringWLogistic, config.scoringOnlineLearn);
        scorer = new RiskScorer();
        scorer.bindEngine(scoreEngine);
        ipTracker = new IpRiskTracker(config.windowMinutes);
        geo = new GeoRegionManager(dir.resolve("geo").resolve("ip2region.xdb"),
                s -> System.out.println("[BotSentinel] " + s),
                s -> System.out.println("[BotSentinel/WARN] " + s));
        geo.configure(config.geoEnabled, config.geoMainlandOnly, config.geoBlockedRegions,
                config.geoAllowedRegions, config.geoAllowIps, config.geoUpdateDays);
        geo.initAsync(); // v2.3: 不管开关都准备本地库
        log("BotSentinel v2.3 (Fabric) 已加载 | 评分: " + scoreEngine.engineName() + " | 模式: " + config.mode
                + " | 特征库: 假人名" + library.botNameCount() + " 形态" + library.shapeCount()
                + " 签名" + library.signatureCount() + " 风险IP" + library.botIpCount()
                + " | 地区拦截: " + (config.geoEnabled ? "开启" : "关闭"));
    }

    public void reload() {
        SentinelConfig fresh = SentinelConfig.load(
                FabricLoader.getInstance().getConfigDir().resolve("BotSentinel").resolve("config.json"));
        this.config = fresh;
        library.configure(fresh.decayDays, fresh.removeBelow);
        geo.configure(fresh.geoEnabled, fresh.geoMainlandOnly, fresh.geoBlockedRegions,
                fresh.geoAllowedRegions, fresh.geoAllowIps, fresh.geoUpdateDays);
        scoreEngine.configure(fresh.scoringEngine, fresh.scoringWHeuristic, fresh.scoringWMarkov,
                fresh.scoringWEntropy, fresh.scoringWLogistic, fresh.scoringOnlineLearn);
        geo.initAsync(); // v2.3: pre-download 语义
    }

    // ---------- 进服前判定(checkCanJoin mixin 调用, 返回 null=放行, 非 null=拒因) ----------
    public String preLoginDecision(String name, String ip) {
        String key = name.toLowerCase(Locale.ROOT);
        stats.d.totalScored++;
        stats.touch();

        // 1) 真人名字封禁(任何IP)
        LibraryStore.HumanBan hb = library.activeHumanBan(name);
        if (hb != null) {
            stats.d.blockedPreLogin++;
            library.humanEvasionHit(hb);
            stats.touch();
            alert("human-name", "[真人封禁] " + name + " (IP: " + ip + ") 使用被封账号重进被拒");
            sessionScores.put(key, -100);
            return config.humanBanMessage;
        }

        // 2) 受信任玩家直接放行(免 IP/网段/地区连坐)
        if (library.isVerifiedPlayer(name) || config.isWhitelisted(name)) {
            sessionScores.put(key, -1);
            return null;
        }

        // 3) 归属地拦截
        if (config.geoEnabled) {
            GeoRegionManager.Verdict g = geo.check(ip);
            if (!g.allow) {
                stats.d.blockedPreLogin++;
                stats.d.geoBlocked++;
                stats.touch();
                alert("geo", "[地区拦截] " + name + " (IP: " + ip + ") 地区: " + g.region + " | " + g.raw);
                sessionScores.put(key, -100);
                return config.geoDenyMessage;
            }
        }

        // 4) IP 临时封禁
        long until = library.getBannedUntil(ip);
        if (until > System.currentTimeMillis()) {
            long minutes = Math.max(1, (until - System.currentTimeMillis()) / 60000);
            stats.d.blockedPreLogin++;
            stats.touch();
            alert("ban", "[拦截进服] IP: " + ip + " | 账号: " + name + " | IP临时封禁中(剩" + minutes + "分钟)");
            sessionScores.put(key, -100);
            return config.banMessage + " §7(剩余约 " + minutes + " 分钟)";
        }

        // 5) 真人网段连坐
        LibraryStore.HumanBan hsb = library.humanBanBySubnet(ip);
        if (hsb != null && config.banEvasionBlockNonverified) {
            long remain = hsb.until <= 0 ? -1 : Math.max(1, (hsb.until - System.currentTimeMillis()) / 60000);
            stats.d.blockedPreLogin++;
            stats.d.evasionBlocked++;
            library.humanEvasionHit(hsb);
            stats.touch();
            alert("evade-human", "[真人逃避拦截] " + name + " (IP: " + ip + ") 来自被封真人 "
                    + hsb.name + " 的网段(" + hsb.subnet16 + ")且未登记, 已拒绝");
            sessionScores.put(key, -100);
            return config.banMessage + (remain > 0 ? " §7(剩余约 " + remain + " 分钟)" : "");
        }

        // 6) bot网段封禁 + 逃避拦截
        LibraryStore.SubnetBan subnet = library.activeSubnetBan(ip);
        if (subnet != null) {
            int randomness = scorer.randomness(name);
            boolean botlike = randomness >= config.minNameRandomness
                    || library.isKnownBotName(name)
                    || library.shapeConfidence(name) >= 0.5;
            if (config.banEvasionBlockBotlike && botlike) {
                long minutes = Math.max(1, (subnet.bannedUntil - System.currentTimeMillis()) / 60000);
                stats.d.blockedPreLogin++;
                stats.d.evasionBlocked++;
                stats.touch();
                int hits = library.evasionHit(ip, config.ipBanMinutes, config.banEvasionMaxMinutes);
                if (config.autoLearn) {
                    library.recordBotIp(ip, 0.15, "网段封禁逃避拦截");
                    library.recordShape(name, 0.10, "网段封禁逃避拦截");
                    library.recordBotName(name, 0.3, "网段封禁逃避拦截");
                }
                alert("evade", "[逃避拦截] " + name + " (IP: " + ip + ") 换IP重进被拒 | 已拦截逃避" + hits + "次");
                sessionScores.put(key, -100);
                return config.banMessage + " §7(剩余约 " + minutes + " 分钟)";
            }
            sessionScores.put(key, Math.max(60, sessionScore(name)));
            alert("evade-watch", "[网段观察] " + name + " (IP: " + ip + ") 来自被封网段但名字正常, 放行并重点盯防");
            return null;
        }

        // 7) 评分决策
        int randomness = scorer.randomness(name);
        int modelBonus = scorer.modelBoost(name); // v2.3: AI小模型加分(不碰保险丝)
        double shapeConf = library.shapeConfidence(name);
        int shapeBonus = shapeConf >= 0.3 ? (int) Math.min(45, 45 * shapeConf) : 0;
        double ipConf = library.ipConfidence(ip);
        double prefixConf = config.prefixLearn ? library.ipPrefixConfidence(ip) : 0;
        ipTracker.recordNewName(ip, name);
        int windowCount = ipTracker.newNameCount(ip);
        int windowBonus = windowCount >= 5 ? 45 : windowCount >= 3 ? 35 : windowCount == 2 ? 20 : windowCount == 1 ? 10 : 0;
        int ipBonus = (int) Math.min(45, ipConf * 40 + prefixConf * 20 + windowBonus);
        int trust = library.isVerifiedPlayer(name) || config.isWhitelisted(name) ? 999
                : library.isSeededPlayer(name) ? 20 : 0;
        int score = Math.max(0, randomness + modelBonus + shapeBonus + ipBonus - trust);

        boolean knownBot = library.isKnownBotName(name);
        boolean block;
        boolean observe;
        String reason;
        if (trust >= 999) { block = false; observe = false; reason = "已登记真人, 直接放行"; }
        else if (knownBot) { block = true; observe = false; reason = "命中假人名特征库"; }
        else {
            // 闪进闪退加分(v2.2)
            int flash = ipTracker.flashQuitCount(ip);
            if (flash >= 3) {
                int bonus = Math.min(30, flash * 10);
                score += bonus;
                if (flash >= 5 && config.autoLearn) {
                    library.recordBotIp(ip, 0.3, "闪进闪退" + flash + "次");
                    stats.d.flashQuitBlocked++;
                }
            }
            block = config.blockMode() && score >= config.blockThreshold && randomness >= config.minNameRandomness;
            observe = !block && score >= config.observeThreshold && randomness >= 35;
            reason = "评分" + score + " (随机" + randomness + "+形态" + shapeBonus + "+IP" + ipBonus + ")"
                    + (flash >= 3 ? " +闪进闪退" + flash + "次" : "");
        }

        if (block) {
            stats.d.blockedPreLogin++;
            stats.touch();
            if (config.autoLearn) {
                library.recordShape(name, 0.10, "进服前拦截反哺");
                library.recordBotIp(ip, 0.18, "拦截反哺");
                if (config.prefixLearn) library.recordIpPrefix(ip, 0.06, "拦截反哺");
                stats.d.learnedFromBlocks++;
            }
            int count = windowCount;
            if (config.ipBanEnabled && count >= config.autoBanCount) {
                banIp(ip, config.ipBanMinutes, "窗口内新账号达" + count + "个");
            } else if (config.ipBanEnabled && library.getBannedUntil(ip) <= 0
                    && ipTracker.recordBlockedHit(ip) >= config.autoBanBlockedCount) {
                banIp(ip, config.ipBanMinutes, "窗口内被拦机器人达" + config.autoBanBlockedCount + "个");
            }
            alert("block", "[进服拦截] 账号: " + name + " | IP: " + ip + " | " + reason + " | 窗口新账号: " + count);
            sessionScores.put(key, -100);
            return config.kickMessage;
        }

        if (observe) {
            alert("observe", "[观察] 新账号 " + name + " (IP: " + ip + ") " + reason + ", 进服后重点盯防");
        }
        sessionScores.put(key, observe ? score : 0);
        if (sessionScores.size() > 2000) sessionScores.clear();
        return null;
    }

    // ---------- 进服/退出 ----------
    public void onJoin(String name, String ip) {
        joinStamps.put(name.toLowerCase(Locale.ROOT), new long[]{System.currentTimeMillis()});
    }

    public void onQuit(String name, String ip) {
        String key = name.toLowerCase(Locale.ROOT);
        long[] ts = joinStamps.remove(key);
        if (ts == null) return;
        long dwellMs = System.currentTimeMillis() - ts[0];
        int minutes = (int) (dwellMs / 60000L);
        if (!sessionScores.containsKey(key) || sessionScores.get(key) > -100) {
            if (minutes >= config.autoTrustMinutes) {
                library.addKnownPlayer(name, true, "自动信任:在线" + minutes + "分钟无异常");
                scoreEngine.learnHuman(name);
            }
            // 闪进闪退检测(v2.2)
            if (dwellMs < 20_000L && !library.isVerifiedPlayer(name)) {
                int fq = ipTracker.recordFlashQuit(ip);
                if (fq >= 3) {
                    stats.d.flashQuitBlocked++;
                    stats.touch();
                    alert("flash", "[闪进闪退] " + ip + " 窗口内短停留进出 " + fq + " 次(最近: " + name + ")");
                }
            }
        }
        if (minutes > 0) library.addPlayMinutes(name, minutes);
        sessionScores.remove(key);
    }

    // ---------- 命令监控(秒注册检测) ----------
    /** 返回 false = 取消该命令执行 */
    public boolean onCommand(String name, String ip, String fullCommand) {
        String key = name.toLowerCase(Locale.ROOT);
        Integer sc = sessionScores.get(key);
        if (sc == null || sc == -100 || sc == -1) return true;
        if (library.isVerifiedPlayer(name) || config.isWhitelisted(name)) return true;

        String[] parts = fullCommand.split("\\s+");
        if (parts.length == 0) return true;
        String root = parts[0].toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);
        boolean authListed = config.authCommands.contains(root) || library.isLearnedAuthCommand(root);
        if (!authListed && !config.anyCommandDetect) return true;

        long sinceJoin = joinStamps.containsKey(key) ? System.currentTimeMillis() - joinStamps.get(key)[0] : Long.MAX_VALUE;
        int randomness = scorer.randomness(name);
        int session = sc;
        int ipCount = ipTracker.newNameCount(ip);
        boolean suspicious = session >= 35 || randomness >= 35 || ipCount >= 2
                || library.ipConfidence(ip) >= 0.3;
        if (!suspicious) return true;

        boolean randomArgs = false;
        if (parts.length >= 3) {
            String a1 = parts[1], a2 = parts[2];
            if (a1.length() >= 6 && a1.equals(a2)) randomArgs = true;
        }
        if (!randomArgs && parts.length >= 2 && parts[1].length() >= 8) {
            String low = parts[1].toLowerCase(Locale.ROOT);
            int vowels = 0;
            for (char c : low.toCharArray()) if ("aeiou".indexOf(c) >= 0) vowels++;
            if (vowels * 4 < parts[1].length()) randomArgs = true;
        }

        long[] stamp = joinStamps.get(key);
        if (randomArgs && sinceJoin <= config.instantRegisterSeconds * 1000L
                && (randomness >= 50 || session >= 45 || ipCount >= 2)) {
            if (authListed) {
                if (config.autoLearnAuthCommands) library.learnAuthCommand(root, "实锤bot自动学习");
                confirmBot(name, ip, "秒发注册指令(" + (sinceJoin / 1000) + "秒内, 参数随机串)");
                return false;
            }
            // 非注册类命令: 降级为计数信号(防误伤)
            int weak = sessionScore(name) + 1;
            sessionScores.put(key, weak);
            if (weak >= config.confirmAuthAttempts + 1 && sinceJoin <= config.watchSeconds * 1000L) {
                if (config.autoLearnAuthCommands) library.learnAuthCommand(root, "实锤bot自动学习");
                confirmBot(name, ip, "盯防期内连续可疑命令" + weak + "次(参数随机串)");
                return false;
            }
            return true;
        }
        if (!authListed) return true;
        // 累计尝试(简单计数: 用 sessionScores 旁路计数)
        int attempts = sessionScore(name) + 1;
        sessionScores.put(key, attempts);
        if (attempts >= config.confirmAuthAttempts + 1 && sinceJoin <= config.watchSeconds * 1000L) {
            if (config.autoLearnAuthCommands && randomArgs) library.learnAuthCommand(root, "实锤bot自动学习");
            confirmBot(name, ip, "盯防期内注册类指令" + attempts + "次(名字随机度" + randomness + ")");
            return false;
        }
        return true;
    }

    // ---------- 聊天守卫 ----------
    /** 返回 false = 拦截该消息 */
    public boolean onChat(String name, String ip, String message) {
        String sig = library.matchAdSignature(message);
        boolean verified = library.isVerifiedPlayer(name) || config.isWhitelisted(name);

        if (sig != null) {
            stats.d.recalledAds++;
            stats.touch();
            if (verified && config.protectKnownPlayers) {
                alert("adknown", "[广告-已登记] " + name + " 消息已撤回 | 命中签名: " + sig);
                return false;
            }
            library.learnAdSignature(message);
            alert("ad", "[广告拦截] " + name + " (IP: " + ip + ") 消息已撤回 | 命中签名: " + sig);
            confirmBot(name, ip, "发送广告消息(命中签名" + sig + ")");
            return false;
        }

        if (verified || !config.genericDomainHeuristic) return true;
        Integer sc = sessionScores.get(name.toLowerCase(Locale.ROOT));
        int session = sc == null ? 0 : sc;
        boolean gated = scorer.randomness(name) >= 50 || session >= 45
                || ipTracker.newNameCount(ip) >= 2 || library.ipConfidence(ip) >= 0.3;
        if (!gated) return true;

        String norm = LibraryStore.normalizeText(message);
        Matcher m = GENERIC_DOMAIN.matcher(norm);
        if (m.find()) {
            stats.d.recalledAds++;
            stats.touch();
            alert("adgen", "[疑似广告-通用域名] " + name + " (IP: " + ip + ") 消息已撤回");
            if (scorer.randomness(name) >= 50) {
                confirmBot(name, ip, "可疑画像玩家发送域名消息(通用启发)");
            }
            return false;
        }
        return true;
    }

    // ---------- 实锤 ----------
    public void confirmBot(String name, String ip, String reason) {
        String key = name.toLowerCase(Locale.ROOT);
        sessionScores.put(key, -100);
        if (config.autoLearn) {
            library.recordBotName(name, 0.5, reason);
            library.recordShape(name, 0.25, reason);
            library.recordBotIp(ip, 0.35, reason);
            if (config.prefixLearn) library.recordIpPrefix(ip, 0.08, reason);
        }
        scoreEngine.learnBot(name); // v2.3: 小模型在线训练
        stats.d.disposedBots++;
        stats.touch();
        if (config.ipBanEnabled) banIp(ip, config.ipBanMinutes, reason);
        alert("dispose", "[处置假人] " + name + " (IP: " + ip + ") | " + reason + " | 已学习进特征库");
    }

    public void banIp(String ip, int minutes, String reason) {
        if (ip == null || ip.isEmpty()) return;
        long until = System.currentTimeMillis() + minutes * 60000L;
        library.setBannedUntil(ip, until, reason);
        stats.d.ipBans++;
        stats.touch();
        log("[IP封禁] " + ip + " 已临时封禁 " + minutes + " 分钟 | 原因: " + reason);
        if (config.banEvasionEnabled && config.banEvasionScope > 0) {
            int eff = library.banSubnet(ip, config.banEvasionScope, minutes, reason,
                    config.banEvasionEscalate, config.banEvasionEscalateAfter,
                    config.banEvasionEscalateMultiplier, config.banEvasionMaxMinutes);
            log("[网段封禁] " + LibraryStore.subnetKey(ip, config.banEvasionScope)
                    + " 同步封禁 " + eff + " 分钟(换IP无法逃避)");
        }
    }

    public void banHuman(String name, String ip, int minutes, String reason) {
        LibraryStore.HumanBan ban = library.banHuman(name, ip, "", "", -1, minutes, reason);
        stats.d.humanBans++;
        stats.touch();
        log("[真人封禁] " + name + " (" + (minutes <= 0 ? "永久" : minutes + "分钟") + ") | " + reason
                + (ban.subnet16.isEmpty() ? "" : " | 网段联动: " + ban.subnet16));
    }

    // ---------- 工具 ----------
    public int sessionScore(String name) {
        Integer v = sessionScores.get(name.toLowerCase(Locale.ROOT));
        return v == null ? 0 : v;
    }

    public void alert(String category, String msg) {
        stats.d.warnings++;
        stats.touch();
        System.out.println("[BotSentinel/WARN] " + msg);
        BotSentinelMod.pushAlert(msg);
    }

    public void log(String msg) {
        System.out.println("[BotSentinel] " + msg);
    }

    public static String ipOf(String name) {
        return BotSentinelMod.ipOfOnline(name);
    }
}
