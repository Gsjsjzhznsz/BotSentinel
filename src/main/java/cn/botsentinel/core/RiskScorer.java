package cn.botsentinel.core;

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

    /** core 版无插件依赖(仅名字随机性评分); mod 端自行组合其他信号 */
    public RiskScorer() {
    }

    private ScoreEngine engine = null;

    /** 绑定策略引擎(SentinelState 初始化时调用) */
    public void bindEngine(ScoreEngine engine) { this.engine = engine; }

    /**
     * 保险丝随机性 0-95 (v2.3): 绑定引擎后 = 规则启发式校准分(ensemble),
     * 未绑定 = 内置临时引擎, 行为一致。
     */
    public int randomness(String name) {
        return engine().randomness(name);
    }

    /** v2.3: AI 小模型加分 0-25(只参与总分, 不碰保险丝) */
    public int modelBoost(String name) {
        return engine().modelBoost(name);
    }

    private ScoreEngine engine() {
        if (engine == null) engine = new ScoreEngine(null);
        return engine;
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
}
