import cn.botsentinel.LibraryStore;
import cn.botsentinel.RiskScorer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 离线冒烟测试: 用 2026-09-05 全天日志的真实名字校验评分边界。
 * RiskScorer.randomness() 不依赖 Bukkit 实例, 可离线运行。
 */
public class TestScorer {
    public static void main(String[] args) throws Exception {
        RiskScorer scorer = new RiskScorer(null);

        // ---- 当天日志中的真实玩家(AuthMe登录成功/正常游玩) —— 必须全部 <50 (不可拦) ----
        String[] humans = {
                "thMCP2", "Iamchine", "23451qwert", "415411", "238318",
                "XiaoMing", "wangwei", "jim_zty", "chuyingweiqi", "Wangxh2002",
                "caw2206", "Echo", "ero", "cc666", "Steve_2008", "MinecraftDBS"
        };
        int fail = 0;
        System.out.println("===== 真人名字(要求全部<50, 永不拦截) =====");
        for (String n : humans) {
            int r = scorer.randomness(n);
            String mark = r < 50 ? "OK " : "!!!误拦风险";
            if (r >= 50) fail++;
            System.out.printf("  %-14s 随机性=%3d  %s%n", n, r, mark);
        }

        // ---- 当天日志中的bot名(v1观察放过/拦截的) —— 期望 >=50 (可拦) ----
        String[] bots = {
                "tlGiIiZChH", "zynTuESOg", "JCPqAFPG", "VpCKoXIUVpN",
                "WnyrLuSkBHhWO", "rGBCjNyjFeRT", "WxluIgMlb", "ghJswmFs",
                "cUTcqLBIJCABU", "HtjcdWAGxGC", "nhRKyMxeeDHK", "QiRoJMCDrd",
                "BsMSrtyeDtsM", "rWSIaAPrKQh", "xraHOOIxySAyL", "kajYZlqB"
        };
        System.out.println("===== 机器人名字(要求全部>=50, 可拦截) =====");
        for (String n : bots) {
            int r = scorer.randomness(n);
            String mark = r >= 50 ? "OK " : "!!!漏网风险";
            if (r < 50) fail++;
            System.out.printf("  %-16s 随机性=%3d  %s%n", n, r, mark);
        }

        // ---- 形态签名 / 网段 / 归一化 / 广告学习 ----
        System.out.println("===== 特征库基础功能 =====");
        LibraryStore lib = new LibraryStore(Files.createTempDirectory("bs").resolve("library.json"));
        System.out.println("  shapeOf(WnyrLuSkBHhWO) = " + LibraryStore.shapeOf("WnyrLuSkBHhWO"));
        System.out.println("  prefix16(14.150.8.168) = " + LibraryStore.prefix16("14.150.8.168"));

        String ad1 = "欢迎来到服务器玩法群 839560171 官网 mc点xemc点cn 注册送VIP";
        var learned1 = lib.learnAdSignature(ad1);
        System.out.println("  广告1自动学习签名: " + learned1);
        String ad2 = " xemcchat  免费皮肤领取 ";
        var learned2 = lib.learnAdSignature(ad2);
        System.out.println("  广告2自动学习签名: " + learned2);
        String hit1 = lib.matchAdSignature("快来 mc.xemc.cn 领取福利!");
        String hit2 = lib.matchAdSignature("今天天气不错, 一起下矿吗");
        System.out.println("  消息1(含域名)命中: " + hit1 + "  | 消息2(正常)命中: " + hit2);

        // ---- 置信度/封禁读写 ----
        lib.recordBotIp("1.2.3.4", 0.35, "测试");
        lib.setBannedUntil("1.2.3.4", System.currentTimeMillis() + 60000, "测试封禁");
        System.out.println("  封禁读取(应>0): " + lib.getBannedUntil("1.2.3.4"));
        lib.clearBan("1.2.3.4");
        System.out.println("  解封后(应=0): " + lib.getBannedUntil("1.2.3.4"));
        lib.addKnownPlayer("Steve_2008", true, "测试");
        System.out.println("  真人验证(应true): " + lib.isVerifiedPlayer("steve_2008"));

        System.out.println();
        System.out.println(fail == 0 ? ">>> 全部校验通过 <<<" : ">>> 有 " + fail + " 个校验失败 <<<");
        if (fail > 0) System.exit(1);
    }
}
