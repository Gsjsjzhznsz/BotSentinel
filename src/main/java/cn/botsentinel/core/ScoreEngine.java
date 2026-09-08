package cn.botsentinel.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BotSentinel v2.3 评分引擎 —— 策略模式 + 可插拔 AI 小模型。
 *
 * 四个策略(Strategy), 各自输出 0-95 的"名字随机性/机器度"分:
 *   heuristic  规则启发式 —— v2.0-2.2 用全天日志校准的规则(32样本回归通过), 稳定兜底
 *   markov     字符 bigram 语言模型(小模型#1) —— 从真人名单在线学习"人类命名习惯",
 *              越用越准; 对随机串天然敏感(转移概率极低)
 *   entropy    统计特征 —— 香农熵/元音占比/辅音连串/重复度
 *   logistic   在线逻辑回归小模型(#2) —— 8维特征向量, 权重从"实锤bot/验证真人"事件
 *              自动 SGD 学习并持久化(model.json), 服务器画像自进化
 *
 * 融合(Ensemble): final = clamp(Σ wi * si, 0, 95), 权重可在 config.yml 调整。
 * 也可 single 模式只启用某个策略(scoring.engine)。
 *
 * 【真人零误伤数学不变】最终分仍只作为 randomness 参与拦截判定,
 * 拦截硬条件 score>=70 且 randomness>=50 且非信任 —— 真人名(拼音/数字/下划线)
 * 在所有策略下都是低分, 数学上不可能被误拦(回归测试保证)。
 *
 * 纯 Java, 零 Bukkit 依赖 —— Fabric/Forge 分支直接复用。
 * 线程安全: pre-login 异步线程并发调用。
 */
public class ScoreEngine {

    // ==================== 特征提取(所有策略共享, 一次计算) ====================

    /** 名字统计特征向量 */
    public static final class NameFeatures {
        public String low = "";
        public int len;
        public int upper, lower, digit, other;
        public boolean lettersOnly;
        public double vowelRatio;        // 元音占比 0-1
        public int maxConsonantRun;      // 最长辅音连
        public int openingRun;           // 开头辅音连
        public int upperIslands;         // 大写孤岛数
        public int longestUpperRun;      // 最长连续大写
        public double entropy;           // 归一化香农熵 bits/char (0-4.7)
        public double repeatRatio;       // 最高频字符占比 0-1
        public double markovLogP;        // bigram 平均对数概率(负值, 越大越像人)
        public boolean hasCommonWord;    // 含常见词/拼音
    }

    // ==================== 策略接口 ====================

    public interface Strategy {
        String id();
        String desc();
        /** 返回 0-95 随机性分 */
        int score(String name, NameFeatures f);
    }

    // ==================== 策略1: 规则启发式(v2.x 校准版) ====================

    public static class HeuristicStrategy implements Strategy {
        @Override public String id() { return "heuristic"; }
        @Override public String desc() { return "规则启发式(v2.0全天日志校准)"; }

        @Override public int score(String name, NameFeatures f) {
            if (name == null || name.isEmpty()) return 0;
            if (!f.lettersOnly) return 0;   // 带数字/下划线/符号 -> 人类习惯, 0分

            int base;
            if (f.len >= 8 && f.len <= 14 && f.upper >= 2 && f.lower >= 4) base = 25;
            else if (f.len >= 6 && f.upper >= 1 && f.lower >= 1) base = 15;
            else return 0;

            int score = base;
            if (f.vowelRatio < 0.25) score += 15;
            if (f.maxConsonantRun >= 4) score += 25;
            else if (f.maxConsonantRun == 3) score += 10;
            if (f.openingRun >= 3) score += 15;
            if (f.upperIslands >= 4) score += 20;
            if (f.longestUpperRun >= 4) score += 20;
            if (f.hasCommonWord) score -= 25;
            return Math.max(0, Math.min(95, score));
        }
    }

    // ==================== 策略2: Markov bigram 语言模型(小模型#1) ====================

    /**
     * 字符 bigram 模型: 统计真人名中 c_{i-1}->c_i 的转移概率。
     * 内置语料 = 常见英文词/拼音词组合; 运行时把每个"已验证真人名"喂进语料(在线学习)。
     * 随机串的转移在人类语料中几乎不出现 -> 平均 logP 极低 -> 高分。
     */
    public static class MarkovStrategy implements Strategy {
        @Override public String id() { return "markov"; }
        @Override public String desc() { return "bigram语言模型(真人名单在线学习)"; }

        // 真人命名语料基座(拼音词+英文词, 引擎初始化时两两组合扩充)
        private static final String[] SEED_WORDS = {
                "xiao", "ming", "chen", "wei", "hao", "lei", "jie", "jun", "hao", "xin",
                "yang", "fan", "kai", "dong", "xu", "kun", "qi", "lin", "hao", "peng",
                "admin", "gamer", "player", "steve", "craft", "miner", "builder", "dream",
                "tiger", "dragon", "panda", "wolf", "eagle", "storm", "frost", "shadow",
                "light", "night", "star", "moon", "cloud", "fire", "snow", "rain",
                "king", "lord", "hero", "ghost", "sword", "hunter", "master", "legend"
        };

        private final double[][] trans = new double[27][27]; // 0-25=a-z, 26=boundary
        private final int[][] counts = new int[27][27];
        private final AtomicBoolean trained = new AtomicBoolean(false);
        private final AtomicInteger corpusSize = new AtomicInteger(0);
        private final java.util.Deque<String> corpusNames = new ConcurrentLinkedDeque<>();
        private final AtomicBoolean transDirty = new AtomicBoolean(true);

        public synchronized void seedBuiltin() {
            if (trained.getAndSet(true)) return;
            for (String w : SEED_WORDS) feedInternal(w, false);
            // 组合出更像真实玩家的双词名: xiaoming / tigerking / darkstorm ...
            java.util.Random r = new java.util.Random(20260908L);
            for (int i = 0; i < 220; i++) {
                String a = SEED_WORDS[r.nextInt(SEED_WORDS.length)];
                String b = SEED_WORDS[r.nextInt(SEED_WORDS.length)];
                if (!a.equals(b)) feedInternal(a + b, false);
            }
        }

        /** 在线学习: 已验证真人名进语料(封顶, 防撑爆) */
        public synchronized void learnHuman(String name) {
            if (!trained.get()) seedBuiltin();
            if (corpusSize.get() >= 3000) return;
            String low = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (low.length() < 3 || low.length() > 16) return;
            for (char c : low.toCharArray()) if (c < 'a' || c > 'z') return; // 只要纯字母
            feedInternal(low, true);
            corpusNames.addLast(low);
            transDirty.set(true);
        }

        /** 语料快照(持久化用) */
        public List<String> corpusSnapshot() {
            return new ArrayList<>(corpusNames);
        }

        /** 批量恢复语料(启动加载用) */
        public void restoreCorpus(List<String> names) {
            if (names == null) return;
            for (String s : names) {
                if (s == null || s.length() < 3 || s.length() > 16) continue;
                String low = s.toLowerCase(Locale.ROOT);
                boolean ok = true;
                for (char c : low.toCharArray()) if (c < 'a' || c > 'z') { ok = false; break; }
                if (!ok) continue;
                feedInternal(low, true);
                corpusNames.addLast(low);
            }
            transDirty.set(true);
        }

        private void feedInternal(String low, boolean count) {
            if (count && corpusSize.incrementAndGet() > 3000) { corpusSize.decrementAndGet(); return; }
            int prev = 26;
            for (char c : low.toCharArray()) {
                if (c < 'a' || c > 'z') continue;
                int cur = c - 'a';
                counts[prev][cur]++;
                prev = cur;
            }
            counts[prev][26]++;
        }

        /** 训练转移概率表(惰性, 语料变化后重算) */
        private void ensureTrained() {
            if (!trained.get()) seedBuiltin();
            if (!transDirty.compareAndSet(true, false)) return;
            synchronized (this) {
                for (int i = 0; i < 27; i++) {
                    int total = 0;
                    for (int j = 0; j < 27; j++) total += counts[i][j];
                    if (total == 0) continue;
                    for (int j = 0; j < 27; j++) {
                        trans[i][j] = Math.log((counts[i][j] + 0.15) / (total + 0.15 * 27)); // Laplace 平滑
                    }
                }
            }
        }

        @Override public int score(String name, NameFeatures f) {
            if (!f.lettersOnly) return 0; // 带数字/符号的名字由 heuristic 定调(人类习惯)
            ensureTrained();
            String low = f.low;
            if (low.length() < 4) return 0;
            int prev = 26;
            double sum = 0; int n = 0;
            for (char c : low.toCharArray()) {
                if (c < 'a' || c > 'z') continue;
                int cur = c - 'a';
                sum += trans[prev][cur];
                prev = cur; n++;
            }
            sum += trans[prev][26];
            f.markovLogP = n > 0 ? sum / n : 0;
            // 校准(2026-09 样本): 真人纯字母名 -2.4 ~ -3.8, 随机串 -4.4 ~ -5.8
            double avg = f.markovLogP;
            if (avg >= -3.9) return 0;
            if (avg <= -4.5) return 40;
            return (int) Math.round((-3.9 - avg) / 0.6 * 40);
        }

        public int corpusCount() { return corpusSize.get(); }
    }

    // ==================== 策略3: 统计特征/信息熵 ====================

    public static class EntropyStrategy implements Strategy {
        @Override public String id() { return "entropy"; }
        @Override public String desc() { return "信息熵/重复度统计"; }

        @Override public int score(String name, NameFeatures f) {
            if (!f.lettersOnly || f.len < 6) return 0;
            int score = 0;
            // 香农熵: 均匀随机串 ~4.3-4.7, 真人名 ~2.4-3.4
            if (f.entropy >= 4.25) score += 25;
            else if (f.entropy >= 3.95) score += 15;
            else if (f.entropy >= 3.7) score += 8;
            // 字符几乎不重复(真人名常重复/双写, 随机串撞字符概率低)
            if (f.len >= 8 && f.repeatRatio <= 0.15) score += 12;
            else if (f.len >= 10 && f.repeatRatio <= 0.2) score += 6;
            // 元音极低已由 heuristic 加分, 这里补极高频模式: 完全无元音
            if (f.vowelRatio < 0.12) score += 8;
            return Math.min(40, score);
        }
    }

    // ==================== 策略4: 在线逻辑回归(小模型#2) ====================

    /**
     * 8维特征 -> sigmoid -> 机器度 0-1 -> 分数。
     * 权重来源: 初始人工先验 + 运行期 SGD 学习(实锤bot=y1, 验证真人=y0),
     * 持久化到 model.json, 重启不丢, 越跑越懂这个服务器。
     */
    public static class LogisticStrategy implements Strategy {
        public static final int DIM = 9; // x0=bias
        public double[] w = { -1.2, 1.2, -1.5, 0.8, 1.5, -1.8, 0.3, -0.5, 0.8 };
        public long trainedSamples = 0;
        private static final double LR = 0.06;

        @Override public String id() { return "logistic"; }
        @Override public String desc() { return "在线逻辑回归(实锤事件自学习)"; }

        public double[] featurize(NameFeatures f) {
            double[] x = new double[DIM];
            x[0] = 1;
            x[1] = f.entropy / 4.7;
            x[2] = f.vowelRatio;
            x[3] = (double) f.upperIslands / Math.max(1, f.len);
            x[4] = (double) f.maxConsonantRun / Math.max(1, f.len);
            x[5] = Math.max(0, Math.min(1, (f.markovLogP + 4.0) / 3.0)); // 人类度 0-1
            x[6] = f.lettersOnly ? 1 : 0;
            x[7] = Math.min(1, f.repeatRatio * 2);
            x[8] = f.hasCommonWord ? 0 : 1;
            return x;
        }

        public double prob(NameFeatures f) {
            double[] x = featurize(f);
            double z = 0;
            for (int i = 0; i < DIM; i++) z += w[i] * x[i];
            return 1.0 / (1.0 + Math.exp(-z));
        }

        public synchronized void learn(NameFeatures f, boolean bot) {
            double[] x = featurize(f);
            double y = bot ? 1 : 0;
            double p = prob(f);
            for (int i = 0; i < DIM; i++) {
                w[i] += LR * (y - p) * x[i];
                if (w[i] > 3) w[i] = 3;
                if (w[i] < -3) w[i] = -3;
            }
            trainedSamples++;
        }

        @Override public int score(String name, NameFeatures f) {
            return (int) Math.round(prob(f) * 55);
        }
    }

    // ==================== 引擎主体 ====================

    private final HeuristicStrategy heuristic = new HeuristicStrategy();
    private final MarkovStrategy markov = new MarkovStrategy();
    private final EntropyStrategy entropy = new EntropyStrategy();
    private final LogisticStrategy logistic = new LogisticStrategy();

    private volatile String engineId = "ensemble";
    private volatile double wHeur = 1.0, wMarkov = 0.5, wEntropy = 0.35, wLogistic = 0.6;
    private volatile boolean onlineLearning = true;

    private final Path modelFile;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final ConcurrentLinkedDeque<String> pendingCorpus = new ConcurrentLinkedDeque<>();

    private static class Model {
        int version = 1;
        List<String> corpus = new ArrayList<>();
        double[] w;
        long samples = 0;
    }

    public ScoreEngine(Path modelFile) {
        this.modelFile = modelFile;
        markov.seedBuiltin();
        loadModel();
    }

    // ---------- 配置 ----------
    public void configure(String engine, Double wH, Double wM, Double wE, Double wL, Boolean learn) {
        if (engine != null && !engine.isEmpty()) {
            engineId = engine.toLowerCase(Locale.ROOT);
            if (!engineId.equals("ensemble") && strategy(engineId) == null) engineId = "ensemble";
        }
        if (wH != null) wHeur = clampW(wH);
        if (wM != null) wMarkov = clampW(wM);
        if (wE != null) wEntropy = clampW(wE);
        if (wL != null) wLogistic = clampW(wL);
        if (learn != null) onlineLearning = learn;
    }

    private static double clampW(double v) { return Math.max(0, Math.min(3, v)); }

    public Strategy strategy(String id) {
        switch (id) {
            case "heuristic": return heuristic;
            case "markov": return markov;
            case "entropy": return entropy;
            case "logistic": return logistic;
            default: return null;
        }
    }
    public List<Strategy> strategies() {
        List<Strategy> l = new ArrayList<>();
        l.add(heuristic); l.add(markov); l.add(entropy); l.add(logistic);
        return l;
    }

    // ---------- 特征 ----------
    public NameFeatures features(String name) {
        NameFeatures f = new NameFeatures();
        if (name == null || name.isEmpty()) return f;
        f.low = name.toLowerCase(Locale.ROOT);
        f.len = name.length();
        int vowels = 0;
        int[] freq = new int[26];
        int letters = 0;
        int maxFreq = 0;
        int run = 0;
        boolean openingDone = false;
        int maxUpperRun = 0, curUpperRun = 0;
        boolean inUpperIsland = false;

        // 大写特征基于原始名字(大写孤岛/连续大写)
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) { f.upper++; curUpperRun++; maxUpperRun = Math.max(maxUpperRun, curUpperRun); if (!inUpperIsland) { f.upperIslands++; inUpperIsland = true; } }
            else { curUpperRun = 0; inUpperIsland = false;
                if (Character.isLowerCase(c)) f.lower++;
                else if (Character.isDigit(c)) f.digit++;
                else f.other++;
            }
        }
        f.lettersOnly = (f.upper + f.lower) == f.len;
        f.longestUpperRun = maxUpperRun;

        // 字母级特征统一在小写串上算(v2.2 校准语义)
        for (int i = 0; i < f.low.length(); i++) {
            char c = f.low.charAt(i);
            if (c < 'a' || c > 'z') { run = 0; openingDone = true; continue; }
            freq[c - 'a']++;
            letters++;
            if (isVowel(c)) vowels++;
            if (!isVowel(c)) {
                run++;
                if (!openingDone) f.openingRun++;
            } else {
                f.maxConsonantRun = Math.max(f.maxConsonantRun, run);
                run = 0;
                openingDone = true;
            }
        }
        f.maxConsonantRun = Math.max(f.maxConsonantRun, run);

        // 香农熵 + 最高频占比
        if (letters > 0) {
            double h = 0;
            for (int i = 0; i < 26; i++) {
                if (freq[i] == 0) continue;
                maxFreq = Math.max(maxFreq, freq[i]);
                double p = (double) freq[i] / letters;
                h -= p * (Math.log(p) / Math.log(2));
            }
            f.entropy = h;
            f.repeatRatio = (double) maxFreq / letters;
        }
        // v2.2 校准语义: 元音占比 = 小写元音数 / 全名长度
        f.vowelRatio = f.len == 0 ? 0 : (double) vowels / f.len;
        f.hasCommonWord = containsCommonWord(f.low);
        return f;
    }

    static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }

    private static final List<String> COMMON_WORDS = List.of(
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

    static boolean containsCommonWord(String low) {
        if (low == null) return false;
        for (String word : COMMON_WORDS) if (low.contains(word)) return true;
        return false;
    }

    // ---------- 评分 ----------
    /**
     * 保险丝随机性分 0-95。ensemble 模式下 = 规则启发式分(v2.2 校准, 真人名恒低),
     * 保证拦截硬条件 score>=70 且 randomness>=50 的真人零误伤数学不变。
     * 单策略模式下 = 该策略的分(自行承担校准风险)。
     */
    public int randomness(String name) {
        if (name == null || name.isEmpty()) return 0;
        NameFeatures f = features(name);
        return fuseScore(f);
    }

    private int fuseScore(NameFeatures f) {
        int s;
        if ("heuristic".equals(engineId)) s = heuristic.score(f.low, f);
        else if ("markov".equals(engineId)) s = markov.score(f.low, f);
        else if ("entropy".equals(engineId)) s = entropy.score(f.low, f);
        else if ("logistic".equals(engineId)) s = logistic.score(f.low, f);
        else s = heuristic.score(f.low, f); // ensemble: 保险丝只用校准过的规则分
        return Math.max(0, Math.min(95, s));
    }

    /**
     * 模型加分 0-25: markov+entropy+logistic 的加权融合, 只加分不改保险丝。
     * 作用: 让"规则分中等"的边缘 bot(如 jEArrQqriop=45)在无IP证据时也能进入
     * 观察区(总分>=45), 让 h>=50 的名字更快过拦截线; 真人名(规则分<=25+模型识别为人类)
     * 加分极低。
     */
    public int modelBoost(String name) {
        if (name == null || name.isEmpty()) return 0;
        NameFeatures f = features(name);
        return modelBoost(f);
    }

    public int modelBoost(NameFeatures f) {
        int m = markov.score(f.low, f);
        int e = entropy.score(f.low, f);
        int l = logistic.score(f.low, f);
        double boost = m * wMarkov + e * wEntropy + l * wLogistic;
        return (int) Math.max(0, Math.min(25, Math.round(boost)));
    }

    /** 已算好特征的评分(避免重复提取) */
    public int scoreWith(NameFeatures f) {
        return Math.max(0, Math.min(95, fuseScore(f) + modelBoost(f)));
    }

    /** 各策略分数明细(/atb engine 用): [规则, 语言模型, 熵, 回归, 模型加分, 保险丝, 总分] */
    public int[] breakdown(String name, NameFeatures f) {
        return new int[] {
                heuristic.score(f.low, f), markov.score(f.low, f),
                entropy.score(f.low, f), logistic.score(f.low, f),
                modelBoost(f), fuseScore(f), scoreWith(f)
        };
    }

    // ---------- 在线学习 ----------
    /** 实锤 bot -> y=1 */
    public void learnBot(String name) {
        if (!onlineLearning || name == null || name.isEmpty()) return;
        NameFeatures f = features(name);
        markov.score(f.low, f); // 确保 markovLogP 已算
        logistic.learn(f, true);
        dirty.set(true);
    }

    /** 验证真人 -> y=0 + 进 markov 语料 */
    public void learnHuman(String name) {
        if (!onlineLearning || name == null || name.isEmpty()) return;
        NameFeatures f = features(name);
        markov.score(f.low, f);
        logistic.learn(f, false);
        if (f.low.length() >= 3 && f.low.length() <= 16) pendingCorpus.add(f.low);
        dirty.set(true);
    }

    /** 定时调用: 把待学语料喂给 markov + 落盘 */
    public void flush() {
        String n;
        while ((n = pendingCorpus.poll()) != null) markov.learnHuman(n);
        saveIfDirty();
    }

    // ---------- 持久化 ----------
    private void loadModel() {
        try {
            if (modelFile == null || !Files.exists(modelFile)) return;
            Model m = new Gson().fromJson(Files.readString(modelFile, StandardCharsets.UTF_8), Model.class);
            if (m == null) return;
            if (m.w != null && m.w.length == LogisticStrategy.DIM) {
                logistic.w = m.w;
                logistic.trainedSamples = m.samples;
            }
            if (m.corpus != null) markov.restoreCorpus(m.corpus);
        } catch (Exception e) {
            System.out.println("[ScoreEngine] model.json 加载失败(使用初始权重): " + e.getMessage());
        }
    }

    public void saveIfDirty() {
        if (!dirty.compareAndSet(true, false)) return;
        try {
            Model m = new Model();
            synchronized (logistic) { m.w = logistic.w.clone(); m.samples = logistic.trainedSamples; }
            m.corpus = markov.corpusSnapshot();
            if (modelFile != null && modelFile.getParent() != null) {
                Files.createDirectories(modelFile.getParent());
            }
            Files.writeString(modelFile, new GsonBuilder().setPrettyPrinting().create().toJson(m), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[ScoreEngine] model.json 保存失败: " + e.getMessage());
            dirty.set(true);
        }
    }

    // ---------- 展示 ----------
    public String engineName() {
        return "ensemble".equals(engineId) ? "ensemble(融合)" : engineId;
    }

    public Map<String, Object> statsMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("engine", engineId);
        m.put("markovCorpus", markov.corpusCount());
        m.put("logisticSamples", logistic.trainedSamples);
        m.put("weights", String.format(Locale.ROOT, "H%.2f M%.2f E%.2f L%.2f", wHeur, wMarkov, wEntropy, wLogistic));
        return m;
    }

    public MarkovStrategy markov() { return markov; }
    public LogisticStrategy logistic() { return logistic; }
}
