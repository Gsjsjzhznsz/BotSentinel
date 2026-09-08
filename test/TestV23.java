import cn.botsentinel.*;
import java.nio.file.*;

/** BotSentinel v2.3 综合冒烟测试: 用 shade 后的 jar + gson 跑(纯Java部分) */
public class TestV23 {
    static int pass = 0, fail = 0;

    static void eq(String name, Object expect, Object actual) {
        boolean ok = String.valueOf(expect).equals(String.valueOf(actual));
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  expect=" + expect + "  actual=" + actual);
        if (ok) pass++; else fail++;
    }

    static void is(String name, boolean cond) {
        System.out.println((cond ? "PASS " : "FAIL ") + name);
        if (cond) pass++; else fail++;
    }

    static int fuse(ScoreEngine e, String n) { return e.randomness(n); }
    static int total(ScoreEngine e, String n) { return e.scoreWith(e.features(n)); }

    static double avgTotal(ScoreEngine eng, String[] names) {
        double sum = 0;
        for (String n : names) sum += eng.scoreWith(eng.features(n));
        return Math.round(sum / names.length * 10) / 10.0;
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("bs23");

        // ==================== 1. ScoreEngine 评分回归 ====================
        // 架构: 保险丝=规则启发式(v2.2校准), 模型分只加分不碰保险丝
        // 断言: 真人名 保险丝<50 且 总分<45(永不误拦/不误观察); 纯字母bot 总分>=55
        ScoreEngine eng = new ScoreEngine(tmp.resolve("model.json"));

        String[] humans = {
                "thMCP2", "23451qwert", "415411", "Iamchine", "XiaoMing", "Steve",
                "Gamer2020", "Lin_Wang", "DragonSlayer998", "xiaohong2003",
                "Wangxh2002", "Zhang_san", "hello_kitty", "MinecraftDBS",
                "xiaoming123", "superman", "Night_Wolf", "chenhaoran",
                "LoveMoon99", "Star_Light", "PlayerOne", "Builder_Bob"
        };
        String[] bots = {
                "tlGiIiZChH", "JCPqAFPG", "ghJswmFs", "HtjcdWAGxGC", "WnyrLuSkBHhWO",
                "NRjvwXZCp", "kHONbMoWLaMNe", "vryplXOy", "RUkIuUNDNwcEW", "SHqFtGbAh",
                "eZkaScHC", "odqvwAleWtBaD", "lZvXVuRR", "oACtZdUlKwoMi", "bDXBxbGD",
                "GAjQKsJYcKD", "tauAJpWROGnh", "jEArrQqriop", "cUTcqLBIJCABU"
        };

        System.out.println("---- 真人名(保险丝<50 且 总分<45) ----");
        int humanMaxFuse = 0, humanMaxTotal = 0;
        String hWorst = "";
        for (String h : humans) {
            int fv = fuse(eng, h), tt = total(eng, h);
            if (tt > humanMaxTotal) { humanMaxTotal = tt; hWorst = h; }
            humanMaxFuse = Math.max(humanMaxFuse, fv);
            System.out.println("  " + h + " 保险丝=" + fv + " 总分=" + tt);
        }
        is("真人名保险丝最大 < 50 (22样本): max=" + humanMaxFuse, humanMaxFuse < 50);
        // 观察触发条件 = 总分>=45 且 保险丝>=35 —— 两者必须同时成立才可能误观察
        boolean anyObserve = false;
        for (String h : humans) if (total(eng, h) >= 45 && fuse(eng, h) >= 35) anyObserve = true;
        is("真人名无一人进入观察区(总分/保险丝双门未同时过)", !anyObserve);

        System.out.println("---- bot名(纯字母 总分>=55; 带数字 靠特征库/行为兜底) ----");
        int botMinTotal = 100;
        String botMinName = "";
        for (String b : bots) {
            int tt = total(eng, b);
            if (tt < botMinTotal) { botMinTotal = tt; botMinName = b; }
            System.out.println("  " + b + " 保险丝=" + fuse(eng, b) + " 总分=" + tt);
        }
        is("bot名总分最小 >= 55: min=" + botMinTotal + "(" + botMinName + ")", botMinTotal >= 55);
        is("当日实波bot NRjvwXZCp 总分>=85 (进服拦截成立)", total(eng, "NRjvwXZCp") >= 85);
        is("当日实波bot kHONbMoWLaMNe 总分>=70", total(eng, "kHONbMoWLaMNe") >= 70);
        is("漏网bot jEArrQqriop 进入观察区(总分>=45)", total(eng, "jEArrQqriop") >= 45);
        is("带数字bot Ciloat77422 保险丝=0(设计如此, 由形态库兜底)", fuse(eng, "Ciloat77422") == 0);

        ScoreEngine.NameFeatures f = eng.features("tlGiIiZChH");
        int[] bd = eng.breakdown("tlGiIiZChH", f);
        is("breakdown 返回7项(4策略+加分+保险丝+总分)", bd.length == 7);
        is("保险丝=规则分(ensemble)", bd[5] == bd[0]);

        // ==================== 2. 在线学习: logistic 分离度提升 ====================
        double beforeBot = avgTotal(eng, bots), beforeHuman = avgTotal(eng, humans);
        for (int i = 0; i < 20; i++) {
            for (String b : bots) eng.learnBot(b);
            for (String h : humans) eng.learnHuman(h);
        }
        double afterBot = avgTotal(eng, bots), afterHuman = avgTotal(eng, humans);
        System.out.println("bot均值 " + beforeBot + " -> " + afterBot + " | 真人均值 " + beforeHuman + " -> " + afterHuman);
        is("在线学习后 bot 总分不降", afterBot >= beforeBot - 1);
        is("在线学习后 真人总分不升(分离度提升)", afterHuman <= beforeHuman + 1);
        is("训练样本计数增长", eng.logistic().trainedSamples >= 800);
        is("训练后真人保险丝仍 < 50 (不随训练漂移)", fuse(eng, "XiaoMing") < 50 && fuse(eng, "Iamchine") < 50);

        // ==================== 3. Markov 语料在线学习 ====================
        int corpus0 = eng.markov().corpusCount();
        eng.learnHuman("chenweilin");
        eng.flush();
        is("markov 语料增长(已验证真人名进语料)", eng.markov().corpusCount() >= corpus0 + 1);
        is("markov 学习后真人拼音名仍低分", fuse(eng, "zhangweili") < 50);

        // ==================== 4. model.json 持久化 ====================
        eng.saveIfDirty();
        is("model.json 已写出", Files.exists(tmp.resolve("model.json")));
        ScoreEngine eng2 = new ScoreEngine(tmp.resolve("model.json"));
        is("重启后权重恢复(logistic samples)", eng2.logistic().trainedSamples == eng.logistic().trainedSamples);
        is("重启后语料恢复", eng2.markov().corpusCount() >= eng.markov().corpusCount());

        // ==================== 5. LibraryStore 自适应命令库 + 持久化 ====================
        Path libFile = tmp.resolve("library.json");
        LibraryStore lib = new LibraryStore(libFile);
        lib.learnAuthCommand("e", "实锤bot自动学习");
        lib.learnAuthCommand("zhuces", "测试命令");
        is("学习后的命令被视为注册类", lib.isLearnedAuthCommand("zhuces") && lib.isLearnedAuthCommand("e"));
        is("未学习的命令不视为注册类", !lib.isLearnedAuthCommand("help"));
        is("移除学习命令", lib.removeLearnedAuthCommand("zhuces"));
        is("移除后失效", !lib.isLearnedAuthCommand("zhuces"));
        lib.learnAuthCommand("mvreg", "模组登录命令");
        lib.forceSave();
        LibraryStore lib2 = new LibraryStore(libFile);
        is("命令库重启保留", lib2.isLearnedAuthCommand("mvreg") && lib2.isLearnedAuthCommand("e"));

        lib2.recordBotNameManual("WrongNameXx", 0.6, "手滑");
        is("手动加错命中", lib2.isKnownBotName("WrongNameXx"));
        LibraryStore.LearnOp op = lib2.undoLast();
        is("undo 生效", op != null && !lib2.isKnownBotName("WrongNameXx"));

        // ==================== 6. GeoRegionManager 状态机 + 判定 ====================
        Path db = Path.of("/home/z/my-project/scripts/geo-test/ip2region.xdb");
        if (Files.exists(db)) {
            GeoRegionManager g = new GeoRegionManager(db, s -> {}, s -> {});
            g.configure(false, true, java.util.List.of(), java.util.List.of(), java.util.List.of(), 7);
            is("v2.3修复: geo关闭也执行 initAsync 下载/加载", g.state() == GeoRegionManager.State.NOT_DOWNLOADED);
            g.initAsync(); // v2.2 会因 enabled=false 直接 return(用户反馈的"库没下载"bug)
            Thread.sleep(600);
            eq("geo 关闭也能就绪(v2.3核心修复)", "READY", g.state().name());
            // 开启后逐项验证判定(check 在 disabled 时短路, 归类断言需在开启后)
            g.configure(true, true, java.util.List.of(), java.util.List.of(), java.util.List.of(), 7);
            eq("归属 8.8.8.8 = 美国", "United States", g.check("8.8.8.8").region);
            eq("台湾归类", "中国台湾", g.check("34.80.0.1").region);
            eq("香港归类", "中国香港", g.check("18.162.0.1").region);
            eq("美国拒", false, g.check("8.8.8.8").allow);
            eq("台湾拒", false, g.check("111.249.0.1").allow);
            eq("大陆放行", true, g.check("223.74.85.44").allow);
            eq("内网豁免", true, g.check("192.168.1.1").allow);
            // 关闭开关不影响库
            g.configure(false, true, java.util.List.of(), java.util.List.of(), java.util.List.of(), 7);
            eq("关闭后 lookup 仍可用(库在)", true, g.lookup("223.5.5.5").contains("223.5.5.5"));
            eq("关闭后库仍就绪", "READY", g.state().name());
        } else {
            System.out.println("SKIP geo 测试(无 xdb 文件)");
        }

        System.out.println("\n===== 结果: " + pass + " PASS, " + fail + " FAIL =====");
        if (fail > 0) System.exit(1);
    }
}
