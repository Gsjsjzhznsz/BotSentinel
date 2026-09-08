package cn.botsentinel.core;

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

    /**
     * v2.4 镜像池: 国内可达代理优先, 官方源收尾。
     * v2.3 实测教训: 国内服务器 raw/jsDelivr 下载其实能成功, 真正炸掉的是
     * 首次下载时 plugins/BotSentinel/geo/ 目录不存在 -> NoSuchFileException。
     * v2.4 已在写入前 createDirectories, 镜像池扩充为多层防御。
     */
    public static final String[] DEFAULT_MIRRORS = {
            "https://ghproxy.net/https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data/ip2region_v4.xdb",
            "https://gh-proxy.com/https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data/ip2region_v4.xdb",
            "https://ghfast.top/https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data/ip2region_v4.xdb",
            "https://github.moeyy.xyz/https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data/ip2region_v4.xdb",
            "https://cdn.jsdelivr.net/gh/lionsoul2014/ip2region@master/data/ip2region_v4.xdb",
            "https://fastly.jsdelivr.net/gh/lionsoul2014/ip2region@master/data/ip2region_v4.xdb",
            "https://testingcf.jsdelivr.net/gh/lionsoul2014/ip2region@master/data/ip2region_v4.xdb",
            "https://raw.githubusercontent.com/lionsoul2014/ip2region/master/data/ip2region_v4.xdb",
            "https://gitee.com/lionsoul/ip2region/raw/master/data/ip2region_v4.xdb"
    };

    /** 判定结果 */
    public static class Verdict {
        public boolean allow;
        public String raw = "";      // 库原始串: 国家|省|城市|ISP|码
        public String region = "";   // 归类: 中国大陆/中国香港/中国澳门/中国台湾/国家名/内网
        public String rule = "";     // 命中的规则说明
    }

    /** 库状态(v2.3: /atb geo status 直观展示, 修复"库没下载"黑箱) */
    public enum State { NOT_DOWNLOADED, DOWNLOADING, READY, FAILED }

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
    private volatile State state = State.NOT_DOWNLOADED;
    private volatile String lastError = "";

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
    public boolean isReady() { return state == State.READY; }
    public State state() { return state; }
    public String lastError() { return lastError; }
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
    /**
     * 启动时调用(异步线程): v2.3 修复 —— 不管 geo 开关与否都准备本地库。
     * 这样 /atb geo on 立即可用, /atb lookup 开箱即用。
     * 开关只控制"拦截判定", 不再控制"库下载"。
     */
    public void initAsync() {
        if (Files.exists(dbFile)) {
            loadFromFile();
            if (isReady()) { checkUpdate(true); return; }
            // 本地文件损坏: 走重新下载
        }
        downloadAndLoadAsync(Files.exists(dbFile) ? "本地库损坏, 重新下载" : "首次下载归属地库");
    }

    /** 定时调用(异步线程): 库文件超过 updateDays 天则自动更新; 失败自动重试(不再要求 enabled) */
    public void checkUpdate(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastUpdateCheck < 1800_000L) return; // 半小时内只查一次
        lastUpdateCheck = now;
        if (state == State.DOWNLOADING) return;
        long age = dbAgeMs();
        if (age != Long.MAX_VALUE && age < (long) updateDays * 86400000L) return;
        if (now - lastDownloadAttempt < 600_000L) return; // 下载失败10分钟内不重试
        downloadAndLoadAsync(age == Long.MAX_VALUE ? "归属地库缺失, 自动补下载"
                : "归属地库自动更新(" + dbAgeDays() + "天前)");
    }

    /** 手动立即下载(/atb geo download), 返回 false=已在下载中 */
    public boolean downloadNow() {
        if (state == State.DOWNLOADING) return false;
        downloadAndLoadAsync("手动触发下载");
        return true;
    }

    private void downloadAndLoadAsync(String why) {
        lastDownloadAttempt = System.currentTimeMillis();
        state = State.DOWNLOADING;
        infoLog.accept("[地区库] " + why + " 开始下载 ip2region_v4.xdb (约7MB, 多镜像自动切换) ...");
        Thread t = new Thread(() -> {
            Path tmp = dbFile.resolveSibling("ip2region.xdb.downloading");
            // v2.4 关键修复: 首次下载时 geo/ 目录可能还不存在, 不创建则所有镜像都会 NoSuchFileException
            try {
                if (dbFile.getParent() != null) Files.createDirectories(dbFile.getParent());
            } catch (Exception e) {
                state = State.FAILED;
                lastError = "无法创建目录 " + dbFile.getParent() + " : " + e.getMessage();
                warnLog.accept("[地区库] " + lastError);
                return;
            }
            StringBuilder tried = new StringBuilder();
            for (String mirror : DEFAULT_MIRRORS) {
                try {
                    byte[] data = httpGet(mirror, 15_000, 120_000);
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
                    lastError = mirror + " -> " + describe(e);
                    tried.append("\n  - ").append(lastError);
                    warnLog.accept("[地区库] 镜像失败 " + lastError);
                }
            }
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            state = State.FAILED;
            warnLog.accept("[地区库] 全部 " + DEFAULT_MIRRORS.length + " 个镜像下载失败, 10分钟后自动重试; /atb geo download 可手动重试。"
                    + "若你的网络全部不可达, 可手动下载 ip2region_v4.xdb 放到 " + dbFile + " 后重载。明细:" + tried);
        }, "BotSentinel-GeoDownload");
        t.setDaemon(true);
        t.start();
    }

    /** v2.4: 异常分类描述, 不再出现"只打印一个文件路径"的黑箱 */
    private static String describe(Throwable e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        if (e instanceof java.net.SocketTimeoutException) return "连接/读取超时";
        if (e instanceof java.net.ConnectException) return "连接失败(可能被墙): " + (m == null ? "" : m);
        if (e instanceof java.nio.file.NoSuchFileException) return "本地路径不存在: " + m;
        if (e instanceof java.net.UnknownHostException) return "域名解析失败: " + (m == null ? "" : m);
        if (e instanceof javax.net.ssl.SSLException) return "SSL错误: " + (m == null ? "" : m);
        return e.getClass().getSimpleName() + ": " + (m == null ? "" : m);
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
            state = State.READY;
            lastError = "";
            infoLog.accept("[地区库] ip2region 本地库加载完成 (" + (dbSize() / 1024 / 1024) + "MB, 库龄" + dbAgeDays() + "天)");
        } catch (Exception e) {
            lastError = e.getMessage();
            state = State.FAILED;
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
        // v2.4: 浏览器样式 UA + Referer, 避免 gitee 等站点反爬 403
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 BotSentinel/2.4");
        conn.setRequestProperty("Accept", "*/*");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + (code == 403 ? "(反爬拒绝)" : code == 404 ? "(文件不存在)" : ""));
            long total = conn.getContentLengthLong();
            try (var in = conn.getInputStream(); var out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[65536];
                int n; long got = 0;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); got += n; }
                if (total > 0 && got != total) throw new IOException("下载不完整(" + got + "/" + total + "B)");
                return out.toByteArray();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(describe(e));
        } finally {
            conn.disconnect();
        }
    }

    /** 转储 UTF-8 调试用 */
    static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
