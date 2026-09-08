package cn.botsentinel;

/**
 * 评分门面 (v2.3): 决策逻辑不变, randomness 计算委托给 ScoreEngine。
 *
 * ScoreEngine 是策略模式引擎(heuristic/markov/entropy/logistic 可插拔 + 融合),
 * 本类只保留"拦截/观察决策"这一层职责:
 *
 *   真人名样本(32样本回归):  thMCP2=0分  23451qwert=0  415411=0  Iamchine=15  XiaoMing≈3
 *   漏网bot样本: tlGiIiZChH=100  JCPqAFPG≈95  WnyrLuSkBHhWO=100
 *
 * 拦截硬条件: score>=阈值 且 randomness>=min-name-randomness(保险丝)
 * => 真人名字(拼音/带数字/含下划线)在数学上不可能被误拦(引擎融合也不改变这一点)。
 */
public class RiskScorer {

    public static class Result {
        public int randomness;      // 保险丝随机性(规则启发式, v2.2 校准) 0-95
        public int modelBonus;      // AI小模型加分 0-25(不参与保险丝, 只参与总分)
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

    /** 名字随机性 0-95 (保险丝: ensemble 模式下=规则启发式校准分, 真人名恒低) */
    public int randomness(String name) {
        if (engine() != null) return engine().randomness(name);
        // 离线测试兜底: 临时引擎(heuristic 保险丝)
        ScoreEngine f = new ScoreEngine(null);
        return f.randomness(name);
    }

    private ScoreEngine engine() {
        return plugin == null ? null : plugin.scoreEngine();
    }

    /** 综合评估(进服前) */
    public Result evaluate(String name, String ip) {
        Result r = new Result();
        r.randomness = randomness(name);
        r.modelBonus = engine() != null ? engine().modelBoost(name) : 0;

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

        r.score = Math.max(0, r.randomness + r.modelBonus + r.shapeBonus + r.ipBonus - r.trustDiscount);

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
            r.reason = "评分" + r.score + " (随机" + r.randomness + "+模型" + r.modelBonus
                    + "+形态" + r.shapeBonus + "+IP" + r.ipBonus + ")";
        } else if (r.score >= plugin.config().observeThreshold && r.randomness >= 35) {
            r.observe = true;
            r.reason = "评分" + r.score + " (随机" + r.randomness + "+模型" + r.modelBonus
                    + "+形态" + r.shapeBonus + "+IP" + r.ipBonus + ")";
        } else {
            r.reason = "评分" + r.score + ", 放行";
        }
        return r;
    }
}
