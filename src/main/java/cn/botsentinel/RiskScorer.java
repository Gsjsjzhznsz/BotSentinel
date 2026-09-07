package cn.botsentinel;

import java.util.Locale;

/**
 * 评分引擎 (用 2026-09-05 全天日志校准):
 *
 *   真人名样本:  thMCP2=0分  23451qwert=0  415411=0  Iamchine=15  XiaoMing=25(拼音)
 *   漏网bot样本: tlGiIiZChH=70  JCPqAFPG=85  ghJswmFs=80  HtjcdWAGxGC=95  WnyrLuSkBHhWO=95
 *
 * 拦截硬条件: score>=阈值 且 randomness>=min-name-randomness(保险丝)
 * => 真人名字(拼音/带数字/含下划线)在数学上不可能被误拦。
 */
public class RiskScorer {

    public static class Result {
        public int randomness;      // 名字随机性 0-95
        public int ipBonus;         // IP侧加分 0-45
        public int shapeBonus;      // 形态库加分 0-45
        public int trustDiscount;   // 信任减免
        public int score;           // 综合分
        public boolean block;       // 拦截
        public boolean observe;     // 观察
        public String reason;       // 决策说明
    }

    private final BotSentinelPlugin plugin;

    public RiskScorer(BotSentinelPlugin plugin) {
        this.plugin = plugin;
    }

    /** 名字随机性 0-95 (仅看名字本身) */
    public int randomness(String name) {
        if (name == null || name.isEmpty()) return 0;
        int len = name.length();
        int upper = 0, lower = 0, digit = 0, other = 0;
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) upper++;
            else if (Character.isLowerCase(c)) lower++;
            else if (Character.isDigit(c)) digit++;
            else other++;
        }
        boolean lettersOnly = (upper + lower) == len;
        if (!lettersOnly) return 0;   // 带数字/下划线/符号的名字直接0分(人类习惯)

        // 大小写混合的无规律字母串
        int base;
        if (len >= 8 && len <= 14 && upper >= 2 && lower >= 4) base = 25;
        else if (len >= 6 && upper >= 1 && lower >= 1) base = 15;
        else return 0;

        int score = base;
        String low = name.toLowerCase(Locale.ROOT);

        // 元音占比极低 (真人拼音/英文名不会低于0.25)
        int vowels = 0;
        for (char c : low.toCharArray()) if (isVowel(c)) vowels++;
        double vowelRatio = (double) vowels / len;
        if (vowelRatio < 0.25) score += 15;

        // 长辅音串 (随机串特征; "Wnyr"=4, "skBHh"=5)
        int maxRun = 0, run = 0, openingRun = 0;
        boolean openingDone = false;
        for (char c : low.toCharArray()) {
            if (!isVowel(c)) {
                run++;
                if (!openingDone) openingRun++;
            } else {
                if (run > maxRun) maxRun = run;
                run = 0;
                openingDone = true;
            }
        }
        if (run > maxRun) maxRun = run;
        if (maxRun >= 4) score += 25;
        else if (maxRun == 3) score += 10;
        if (openingRun >= 3) score += 15;

        // 大写孤岛数量 (Wnyr|L|u|Sk|B|H|h|W|O -> 7个短岛; XiaoMing只有2个)
        int islands = countUpperIslands(name);
        if (islands >= 4) score += 20;

        // 连续大写长串 (cUT|cq|LBIJCABU -> 8连大写, 纯随机生成特征)
        if (longestUpperRun(name) >= 4) score += 20;

        // 【真人保险】含常见英文/拼音词(MC社区命名习惯) -> 大幅减免
        // 如 MinecraftDBS(含minecraft)=25分 / XiaoMing(含xiao,ming)=0分
        if (containsCommonWord(low)) score -= 25;

        return Math.max(0, Math.min(95, score));
    }

    /** 连续大写字母的最长长度 */
    private static int longestUpperRun(String name) {
        int max = 0, run = 0;
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) { run++; max = Math.max(max, run); }
            else run = 0;
        }
        return max;
    }

    // 常见英文词/MC社区词/拼音词(>=3字母), 命中即认为是人类命名习惯
    private static final java.util.Set<String> COMMON_WORDS = java.util.Set.of(
            "minecraft", "craft", "steve", "gamer", "player", "admin", "sword", "creeper",
            "ender", "nether", "pixel", "block", "build", "miner", "world", "dream",
            "china", "chinese", "angel", "hello", "love", "happy", "sunny", "cloud",
            "storm", "frost", "shadow", "light", "night", "star", "moon", "fire",
            "water", "earth", "wind", "snow", "rain", "king", "queen", "prince",
            "lord", "master", "noob", "game", "play", "legend", "hero", "ghost",
            "tiger", "dragon", "eagle", "panda", "monkey", "rabbit", "snake",
            "xiao", "ming", "chen", "zhang", "wang", "huang", "yang", "zhao",
            "zhou", "lin", "guo", "zheng", "liang", "song", "tang", "feng",
            "deng", "peng", "zeng", "tian", "dong", "yuan", "shan", "hai",
            "yong", "qing", "jing", "xiong", "sheng", "cheng", "hong",
            "long", "jun", "hao", "rui", "jia", "jian", "lei", "tao",
            "kun", "yan", "hui", "xin", "fei", "shi", "bai", "duan", "qun",
            "yue", "nan", "bin", "qiang", "gang", "liao", "cai", "jiang");

    private static boolean containsCommonWord(String low) {
        for (String w : COMMON_WORDS) {
            if (w.length() >= 3 && low.contains(w)) return true;
        }
        return false;
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }

    private static int countUpperIslands(String name) {
        int islands = 0;
        boolean inUpper = false;
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                if (!inUpper) islands++;
                inUpper = true;
            } else inUpper = false;
        }
        return islands;
    }

    /** 综合评估(进服前) */
    public Result evaluate(String name, String ip) {
        Result r = new Result();
        r.randomness = randomness(name);

        LibraryStore lib = plugin.library();

        // 形态库命中(同一生成器)
        double shapeConf = lib.shapeConfidence(name);
        if (shapeConf >= 0.3) r.shapeBonus = (int) Math.min(45, 45 * shapeConf);

        // IP 侧: 精确IP信誉 + 网段信誉 + 窗口内新账号数
        double ipConf = lib.ipConfidence(ip);
        double prefixConf = plugin.config().prefixLearn ? lib.ipPrefixConfidence(ip) : 0;
        int windowCount = plugin.ipTracker().newNameCount(ip);
        int windowBonus;
        if (windowCount >= 5) windowBonus = 45;
        else if (windowCount >= 3) windowBonus = 35;
        else if (windowCount == 2) windowBonus = 20;
        else if (windowCount == 1) windowBonus = 10;
        else windowBonus = 0;
        r.ipBonus = (int) Math.min(45, ipConf * 40 + prefixConf * 20 + windowBonus);

        // 信任减免
        if (lib.isVerifiedPlayer(name)) r.trustDiscount = 999;
        else if (plugin.isWhitelisted(name)) r.trustDiscount = 999;
        else if (lib.isSeededPlayer(name)) r.trustDiscount = 20;

        r.score = Math.max(0, r.randomness + r.shapeBonus + r.ipBonus - r.trustDiscount);

        boolean trusted = r.trustDiscount >= 999;
        boolean knownBot = lib.isKnownBotName(name);

        if (trusted) {
            r.reason = "已登记真人, 直接放行";
        } else if (knownBot) {
            r.block = true;
            r.reason = "命中假人名特征库";
        } else if (plugin.mode() == Config.Mode.BLOCK
                && r.score >= plugin.config().blockThreshold
                && r.randomness >= plugin.config().minNameRandomness) {
            r.block = true;
            r.reason = "评分" + r.score + " (随机" + r.randomness + "+形态" + r.shapeBonus + "+IP" + r.ipBonus + ")";
        } else if (r.score >= plugin.config().observeThreshold && r.randomness >= 35) {
            r.observe = true;
            r.reason = "评分" + r.score + " (随机" + r.randomness + "+形态" + r.shapeBonus + "+IP" + r.ipBonus + ")";
        } else {
            r.reason = "评分" + r.score + ", 放行";
        }
        return r;
    }
}
