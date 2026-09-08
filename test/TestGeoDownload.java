import cn.botsentinel.GeoRegionManager;
import java.nio.file.*;

/** v2.4 关键修复验证: geo 目录不存在时能否成功下载+加载 (复现用户服务器故障场景) */
public class TestGeoDownload {
    public static void main(String[] args) throws Exception {
        Path missing = Paths.get("/tmp/bs-geo-test/nested/deep/geo/ip2region.xdb");
        if (Files.exists(Paths.get("/tmp/bs-geo-test"))) {
            try (java.util.stream.Stream<Path> s = Files.walk(Paths.get("/tmp/bs-geo-test"))) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println("目录存在? " + Files.exists(missing.getParent()) + " (应为 false)");

        GeoRegionManager geo = new GeoRegionManager(missing,
                s -> System.out.println("[INFO] " + s),
                s -> System.out.println("[WARN] " + s));
        geo.configure(false, true, java.util.List.of(), java.util.List.of(), java.util.List.of(), 7);
        geo.initAsync();

        // 轮询等下载完成 (最多120秒)
        for (int i = 0; i < 120; i++) {
            Thread.sleep(1000);
            if (geo.state() == GeoRegionManager.State.READY || geo.state() == GeoRegionManager.State.FAILED) break;
        }
        System.out.println("最终状态: " + geo.state());
        System.out.println("库大小: " + (geo.dbSize() / 1024 / 1024) + "MB");
        System.out.println("lookup 1.2.4.8: " + geo.lookup("1.2.4.8"));
        System.out.println("lookup 223.5.5.5: " + geo.lookup("223.5.5.5"));
        System.out.println("lookup 8.8.8.8: " + geo.lookup("8.8.8.8"));
        if (geo.state() != GeoRegionManager.State.READY) { System.out.println("FAILED-GEO-TEST"); System.exit(1); }
        System.out.println("GEO-TEST-OK");
    }
}
