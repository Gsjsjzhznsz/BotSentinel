import cn.botsentinel.*;
import java.nio.file.*;

/** BotSentinel v2.2 综合冒烟测试: 直接用 shade 后的 jar 跑 */
public class TestV22 {
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

    public static void main(String[] args) throws Exception {
        // ---------- 1. IpUtil ----------
        is("私有段 192.168.1.1", IpUtil.isPrivateOrReserved("192.168.1.1"));
        is("私有段 10.1.2.3", IpUtil.isPrivateOrReserved("10.1.2.3"));
        is("公网 223.74.85.44 非私有", !IpUtil.isPrivateOrReserved("223.74.85.44"));
        is("CIDR 命中 14.24.176.62 in 14.24.0.0/16", IpUtil.inCidr("14.24.176.62", "14.24.0.0/16"));
        is("CIDR 不命中 1.2.3.4 in 14.24.0.0/16", !IpUtil.inCidr("1.2.3.4", "14.24.0.0/16"));
        is("CIDR /24 精确", IpUtil.inCidr("14.24.176.62", "14.24.176.0/24"));
        is("IPv6 传给 isPrivate 不炸", IpUtil.isPrivateOrReserved("2001:db8::1") == false || IpUtil.isPrivateOrReserved("2001:db8::1"));

        // ---------- 2. GeoRegionManager ----------
        Path db = Path.of("/home/z/my-project/scripts/geo-test/ip2region.xdb");
        GeoRegionManager g = new GeoRegionManager(db, s -> {}, s -> {});
        g.configure(true, true, java.util.List.of(), java.util.List.of(), java.util.List.of(), 7);
        // 手动触发加载(复用内部逻辑: 直接调 initAsync 会开线程, 这里用 check 前强制 load)
        g.initAsync();
        Thread.sleep(500); // 等待异步加载

        GeoRegionManager.Verdict v1 = g.check("8.8.8.8");
        eq("geo 8.8.8.8 归类", "United States", v1.region);
        eq("geo 8.8.8.8 mainlandOnly 拒", false, v1.allow);

        GeoRegionManager.Verdict v2 = g.check("18.162.0.1");
        eq("geo 香港归类", "中国香港", v2.region);
        eq("geo 香港拒", false, v2.allow);

        eq("geo 台湾归类", "中国台湾", g.check("34.80.0.1").region);
        eq("geo 澳门归类", "中国澳门", g.check("202.175.0.1").region);
        eq("geo 台湾拒", false, g.check("111.249.0.1").allow);

        GeoRegionManager.Verdict v3 = g.check("223.74.85.44");
        eq("geo 大陆归类", "中国大陆", v3.region);
        eq("geo 大陆放行", true, v3.allow);

        GeoRegionManager.Verdict v4 = g.check("192.168.1.1");
        eq("geo 内网豁免", true, v4.allow);
        eq("geo 内网归类", "内网IP", v4.region);

        // IP豁免
        g.configure(true, true, java.util.List.of(), java.util.List.of(), java.util.List.of("8.8.8.0/24"), 7);
        eq("geo allow-ips 豁免", true, g.check("8.8.8.8").allow);
        eq("geo allow-ips 不豁免他人", false, g.check("8.7.198.45").allow);

        // 黑名单关键词模式
        g.configure(true, false, java.util.List.of("移动"), java.util.List.of(), java.util.List.of(), 7);
        eq("geo 关键词黑名单命中(移动)", false, g.check("223.74.85.44").allow);
        eq("geo 关键词黑名单不误伤(电信)", true, g.check("180.164.5.5").allow);

        // 白名单模式
        g.configure(true, false, java.util.List.of(), java.util.List.of("广东省"), java.util.List.of(), 7);
        GeoRegionManager.Verdict v5 = g.check("223.74.85.44"); // 湛江, 广东省
        eq("geo 白名单命中广东省", true, v5.allow);

        // lookup
        System.out.println("lookup: " + g.lookup("223.5.5.5"));

        // ---------- 3. LibraryStore unlearn/undo ----------
        Path libFile = Files.createTempFile("bslib", ".json");
        LibraryStore lib = new LibraryStore(libFile);
        lib.recordBotNameManual("WrongNameXx", 0.6, "手滑加错");
        is("手动加错后命中假人名库", lib.isKnownBotName("WrongNameXx"));
        LibraryStore.LearnOp op = lib.undoLast();
        is("undo 返回记录", op != null && "wrongnamexx".equals(op.key));
        is("undo 后不再命中", !lib.isKnownBotName("WrongNameXx"));

        lib.recordBotNameManual("Bot2", 0.6, "test");
        is("removeBotName", lib.removeBotName("Bot2"));
        is("removeBotName 后不命中", !lib.isKnownBotName("Bot2"));
        LibraryStore.LearnOp restored = lib.undoLast(); // undo 掉 remove -> 恢复
        is("undo 恢复移除", restored != null && restored.removal);
        is("恢复后再命中", lib.isKnownBotName("Bot2"));

        lib.addTrustedManual("RealPlayer1", "test");
        is("信任后 verified", lib.isVerifiedPlayer("RealPlayer1"));
        is("removeTrusted", lib.removeTrusted("RealPlayer1"));
        is("取消信任后非 verified", !lib.isVerifiedPlayer("RealPlayer1"));
        lib.undoLast(); // 恢复信任
        is("undo 恢复信任", lib.isVerifiedPlayer("RealPlayer1"));

        is("recentOps 有记录(3次undo已弹出, 剩2条)", lib.recentOps(5).size() >= 2);
        // 撤光所有历史后 undo 返回 null
        LibraryStore.LearnOp lastOp = lib.undoLast();
        while (lastOp != null) lastOp = lib.undoLast();
        is("undo 到底后返回null", lastOp == null);

        // 网段封禁回归(v2.1 已测, 快速复验)
        int eff = lib.banSubnet("14.24.176.62", 16, 120, "t", true, 2, 3, 10080);
        eq("网段首封120", 120, eff);
        lib.banSubnet("14.24.176.62", 16, 120, "t", true, 2, 3, 10080);
        lib.banSubnet("14.24.176.62", 16, 120, "t", true, 2, 3, 10080);
        int eff4 = lib.banSubnet("14.24.176.62", 16, 120, "t", true, 2, 3, 10080);
        eq("第4次封禁加重1080", 1080, eff4);
        eq("activeSubnetBan 命中/16", "hit", lib.activeSubnetBan("14.24.99.1") == null ? "null" : "hit");
        int hits = lib.evasionHit("14.24.50.50", 120, 10080);
        eq("逃避计数1", 1, hits);

        // ---------- 4. StatsStore 序列化(v2.1崩溃修复验证) ----------
        Path stFile = Files.createTempFile("bsstats", ".json");
        StatsStore st = new StatsStore(stFile);
        st.d.blockedPreLogin = 42;
        st.d.geoBlocked = 7;
        st.d.humanBans = 3;
        st.touch();
        st.forceSave();
        StatsStore st2 = new StatsStore(stFile);
        eq("stats 持久化回读 blockedPreLogin", 42, st2.d.blockedPreLogin);
        eq("stats 持久化回读 geoBlocked", 7, st2.d.geoBlocked);
        eq("stats 持久化回读 humanBans", 3, st2.d.humanBans);
        System.out.println("StatsStore Gson 序列化无 ThreadLocal 异常(旧版此处抛 InaccessibleObjectException)");

        // ---------- 5. RiskScorer 回归 ----------
        RiskScorer scorer = new RiskScorer(null); // randomness 不依赖 plugin
        is("真人 23451qwert=0", scorer.randomness("23451qwert") == 0);
        is("真人 415411=0", scorer.randomness("415411") == 0);
        is("真人 XiaoMing<=25", scorer.randomness("XiaoMing") <= 25);
        is("真人 Iamchine<=25", scorer.randomness("Iamchine") <= 25);
        is("bot tlGiIiZChH>=55", scorer.randomness("tlGiIiZChH") >= 55);
        is("bot WnyrLuSkBHhWO>=55", scorer.randomness("WnyrLuSkBHhWO") >= 55);
        is("漏网bot cirawemuLP=25(数字保险丝下不可拦, 由特征库兜底)", scorer.randomness("cirawemuLP") == 25);

        System.out.println("\n===== 结果: " + pass + " PASS, " + fail + " FAIL =====");
        if (fail > 0) System.exit(1);
    }
}
