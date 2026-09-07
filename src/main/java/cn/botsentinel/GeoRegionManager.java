package cn.botsentinel;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.lionsoul.ip2region.xdb.Searcher;

/**
 * IP 归属地拦截 (v2.2): 基于 ip2region 离线库 (xdb 全内存检索, 无任何API调用, 不怕限流)。
 *
 * - 首次启动自动从多个镜像下载 xdb (GitHub / jsDelivr / gitee / ghproxy)
 * - 定时自动更新本地库 (默认7天, 后台原子替换, 下载失败不影响在用旧库)
 * - 判定规则:
 *     mainland-only=true  -> 仅放行 中国大陆 + 内网/保留IP
 *     blocked-regions     -> 关键词黑名单 (如 香港/澳门/台湾/United States)
 *     allowed-regions     -> 非空时变成白名单模式 (仅放行命中地区)
 *     allow-ips           -> CIDR 豁免名单 (自己的海外IP等)
 *
 * 本类为纯 Java 实现, 不依赖 Bukkit, 可直接移植到 Fabric/Forge。
 */
public class GeoRegionManager {

    public static final String[] DEFAULT_MIRRORS = {
            "https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data/ip2region_v4.xdb",
            "https://cdn.jsdelivr.net/gh/lionsoul2014/ip2region@master/data/ip2region_v4.xdb",
            "https://gitee.com/lionsoul/ip2region/raw/master/data/ip2region_v4.xdb",
            "https://ghproxy.net/https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data/ip2region_v4.xdb"
    };

    /** 判定结果 */
    public static class Verdict {
        public boolean allow;
        public String raw = "";      // 库原始串: 国家|省|城市|ISP|码
        public String region = "";   // 归类: 中国大陆/中国香港/中国澳门/中国台湾/国家名/内网
        public String rule = "";     // 命中的规则说明
    }

    private final Path dbFile;
    private final Consumer<String> infoLog;
    private final Consumer<String> warnLog;
    private final Object searchLock = new Object();

    private volatile Searcher searcher;
    private volatile boolean enabled = false;
    private volatile boolean mainlandOnly = true;
    private volatile List<String> blockedRegions = new ArrayList<>();
    private volatile List<String> allowedRegions = new ArrayList<>();
    private volatile List<String> allowIps = new ArrayList<>();
    private volatile int updateDays = 7;
    private volatile long lastUpdateCheck = 0;
    private volatile long lastDownloadAttempt = 0;

    public GeoRegionManager(Path dbFile, Consumer<String> infoLog, Consumer<String> warnLog) {
        this.dbFile = dbFile;
        this.infoLog = infoLog == null ? s -> {} : infoLog;
        this.warnLog = warnLog == null ? s -> {} : warnLog;
    }

    // ---------- 配置 ----------
    public void configure(boolean enabled, boolean mainlandOnly, List<String> blocked,
                          List<String> allowed, List<String> allowIps, int updateDays) {
        this.enabled = enabled;
        this.mainlandOnly = mainlandOnly;
        this.blockedRegions = lower(blocked);
        this.allowedRegions = lower(allowed);
        this.allowIps = allowIps == null ? new ArrayList<>() : allowIps;
        this.updateDays = Math.max(1, updateDays);
    }

    public boolean isEnabled() { return enabled; }
    public boolean isReady() { return searcher != null; }
    public long dbSize() { try { return Files.size(dbFile); } catch (Exception e) { return 0; } }
    public long dbAgeMs() {
        try { return System.currentTimeMillis() - Files.getLastModifiedTime(dbFile).toMillis(); }
        catch (Exception e) { return Long.MAX_VALUE; }
    }
    public long dbAgeDays() { long a = dbAgeMs(); return a == Long.MAX_VALUE ? -1 : a / 86400000L; }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in != null) for (String s : in) if (s != null && !s.trim().isEmpty()) out.add(s.trim().toLowerCase(java.util.Locale.ROOT));
        return out;
    }

    // ---------- 生命周期 ----------
    /** 启动时调用(异步线程): 库缺失则下载, 然后加载 */
    public void initAsync() {
        if (!enabled) return;
        if (Files.exists(dbFile)) { loadFromFile(); checkUpdate(true); return; }
        downloadAndLoadAsync("首次下载归属地库");
    }

    /** 定时调用(异步线程): 库文件超过 updateDays 天则自动更新 */
    public void checkUpdate(boolean force) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastUpdateCheck < 1800_000L) return; // 半小时内只查一次
        lastUpdateCheck = now;
        long age = dbAgeMs();
        if (age < (long) updateDays * 86400000L) return;
        if (now - lastDownloadAttempt < 600_000L) return; // 下载失败10分钟内不重试
        downloadAndLoadAsync("归属地库自动更新(" + dbAgeDays() + "天前)");
    }

    private void downloadAndLoadAsync(String why) {
        lastDownloadAttempt = System.currentTimeMillis();
        infoLog.accept("[地区库] " + why + " 开始下载 ip2region.xdb ...");
        Thread t = new Thread(() -> {
            Path tmp = dbFile.resolveSibling("ip2region.xdb.downloading");
            for (String mirror : DEFAULT_MIRRORS) {
                try {
                    byte[] data = httpGet(mirror, 30_000, 90_000);
                    if (data == null || data.length < 1_000_000) throw new IOException("文件过小(" + (data == null ? 0 : data.length) + "B)");
                    Files.write(tmp, data);
                    validateFile(tmp);
                    try {
                        Files.move(tmp, dbFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tmp, dbFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                    loadFromFile();
                    infoLog.accept("[地区库] 下载并加载成功: " + (dbSize() / 1024 / 1024) + "MB, 来源: " + mirror);
                    return;
                } catch (Exception e) {
                    warnLog.accept("[地区库] 镜像失败 " + mirror + " : " + e.getMessage());
                }
            }
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            warnLog.accept("[地区库] 全部镜像下载失败, 地区拦截暂不生效(下一轮定时重试; 已有旧库不受影响)");
        }, "BotSentinel-GeoDownload");
        t.setDaemon(true);
        t.start();
    }

    private void validateFile(Path p) throws Exception {
        byte[] cBuff = Files.readAllBytes(p);
        Searcher s = Searcher.newWithBuffer(cBuff);
        String r = s.search("1.2.4.8");
        if (r == null || r.isEmpty()) throw new IOException("样例检索失败");
    }

    private void loadFromFile() {
        try {
            byte[] cBuff = Files.readAllBytes(dbFile);
            Searcher s = Searcher.newWithBuffer(cBuff);
            s.search("223.5.5.5"); // 热身+自检
            synchronized (searchLock) { this.searcher = s; }
            infoLog.accept("[地区库] ip2region 本地库加载完成 (" + (dbSize() / 1024 / 1024) + "MB, 库龄" + dbAgeDays() + "天)");
        } catch (Exception e) {
            warnLog.accept("[地区库] 本地库加载失败: " + e.getMessage());
        }
    }

    // ---------- 检索与判定 ----------
    /** 返回原始地区串; 未启用/未就绪/检索失败返回 null */
    public String rawRegion(String ip) {
        Searcher s = searcher;
        if (s == null || ip == null || ip.isEmpty()) return null;
        try {
            synchronized (searchLock) { return s.search(ip); }
        } catch (Exception e) {
            return null;
        }
    }

    /** 地区归类展示名: 中国大陆/中国香港/中国澳门/中国台湾/国家名/内网IP/未知 */
    public static String classify(String raw) {
        if (raw == null || raw.isEmpty()) return "未知";
        String low = raw.toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("reserved")) return "内网IP";
        if (low.startsWith("中国")) {
            if (low.contains("香港")) return "中国香港";
            if (low.contains("澳门")) return "中国澳门";
            if (low.contains("台湾")) return "中国台湾";
            return "中国大陆";
        }
        int cut = raw.indexOf('|');
        return cut > 0 ? raw.substring(0, cut) : raw;
    }

    /** 归属地拦截判定: allow=是否放行 */
    public Verdict check(String ip) {
        Verdict v = new Verdict();
        if (!enabled) { v.allow = true; v.rule = "未启用"; return v; }
        if (IpUtil.isPrivateOrReserved(ip)) { v.allow = true; v.region = "内网IP"; v.rule = "内网豁免"; return v; }
        if (!allowIps.isEmpty() && IpUtil.inAnyCidr(ip, allowIps)) { v.allow = true; v.region = "豁免名单"; v.rule = "allow-ips"; return v; }

        String raw = rawRegion(ip);
        v.raw = raw == null ? "未知" : raw;
        v.region = "未知".equals(v.raw) ? "未知" : classify(raw);

        if (!allowedRegions.isEmpty()) {
            boolean hit = false;
            for (String kw : allowedRegions) if (v.raw.toLowerCase(java.util.Locale.ROOT).contains(kw) || v.region.toLowerCase(java.util.Locale.ROOT).contains(kw)) { hit = true; break; }
            v.allow = hit;
            v.rule = hit ? "地区白名单命中" : "不在地区白名单";
            return v;
        }

        for (String kw : blockedRegions) {
            String regLow = v.region.toLowerCase(java.util.Locale.ROOT);
            if (v.raw.toLowerCase(java.util.Locale.ROOT).contains(kw) || regLow.contains(kw)) {
                v.allow = false; v.rule = "黑名单地区:" + kw; return v;
            }
        }

        if (mainlandOnly) {
            v.allow = "中国大陆".equals(v.region) || "内网IP".equals(v.region);
            v.rule = v.allow ? "大陆放行" : "仅放行中国大陆";
            return v;
        }

        v.allow = true;
        v.rule = "默认放行";
        return v;
    }

    /** 管理命令用: 查询任意IP归属地(不受 enabled 影响) */
    public String lookup(String ip) {
        if (IpUtil.isPrivateOrReserved(ip)) return ip + " -> 内网/保留IP";
        String raw = rawRegion(ip);
        return ip + " -> " + (raw == null ? "库未就绪或未知" : raw + "  [" + classify(raw) + "]");
    }

    // ---------- HTTP ----------
    private static byte[] httpGet(String url, int connTimeout, int readTimeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(connTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "BotSentinel/2.2 (minecraft-plugin)");
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
        try (var in = conn.getInputStream(); var out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    /** 转储 UTF-8 调试用 */
    static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
