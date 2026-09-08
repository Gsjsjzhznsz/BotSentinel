package cn.botsentinel.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动更新特征库 (v2.0 核心)。
 *
 * 五个特征区:
 *   botNames       已确认假人名(精确)
 *   usernameShapes 名字形态签名(如 XxxxXxXxXx), 抓"同一生成器"
 *   adSignatures   广告消息签名(自动学习)
 *   botIps+ipPrefixes 风险IP(+临时封禁到期) 与 /16 网段信誉
 *   knownPlayers   已登记真人(verified=运行时验证 / seeded=usercache播种)
 *
 * 自动更新闭环:
 *   进服前拦截 -> 学习(ip/形态/网段)
 *   进服后实锤(秒发/e注册、被限速器踢、发广告) -> 学习(名字/形态/ip/网段/签名)
 *   -> 同IP或同形态的下一只 bot 在进服前直接被拦, QQ群全程无感知。
 *   置信度随命中增长、随时间衰减(30天未命中衰减)、低于阈值自动清理。
 */
public class LibraryStore {

    // ---------- 数据模型 ----------
    public static class Entry {
        public double confidence = 0;
        public int hits = 0;
        public long lastSeen = 0;
        public String note = "";
        Entry() {}
        Entry(double c, String note) { this.confidence = c; this.note = note == null ? "" : note; this.lastSeen = now(); this.hits = 1; }
    }

    public static class IpEntry extends Entry {
        public long bannedUntil = 0;
        IpEntry() {}
        IpEntry(double c, String note) { super(c, note); }
    }

    public static class SigEntry extends Entry {
        public boolean regex = false;
        SigEntry() {}
        SigEntry(double c, String note, boolean regex) { super(c, note); this.regex = regex; }
    }

    public static class PlayerEntry {
        public long firstSeen = 0;
        public long lastSeen = 0;
        public int playMinutes = 0;
        public boolean verified = false;
        public String note = "";
    }

    /** 真人封禁(封禁规避防御): /24 或 /16 运营商段, 换IP无法逃避 */
    public static class SubnetBan {
        public long bannedUntil = 0;
        public int hits = 0;      // 已拦截的逃避尝试次数(换IP+换名再进被拒)
        public int banCount = 0;  // 该网段累计被封次数(用于逐犯加重)
        public String note = "";
    }

    /** 真人客户端指纹(进服自动采集, 供封禁追踪) */
    public static class Fingerprint {
        public String brand = "";   // 客户端品牌(vanilla/PCL/Lunar/Feather/Geyser...)
        public String locale = "";  // 语言(zh_cn等)
        public int protocol = -1;   // 协议版本(ViaVersion, 不可用=-1)
        public String lastIp = "";
        public long lastSeen = 0;
    }

    /** 真人封禁记录(专治重启路由器换IP+换用户名) */
    public static class HumanBan {
        public String name = "";
        public String reason = "";
        public long bannedAt = 0;
        public long until = 0;      // 0=永久
        public String lastIp = "";
        public String subnet24 = "";
        public String subnet16 = "";
        public String brand = "";   // 封禁时客户端指纹
        public String locale = "";
        public int protocol = -1;
        public int evasionHits = 0; // 逃避尝试次数
    }

    public static class Data {
        public int version = 4;
        public Map<String, Entry> botNames = new ConcurrentHashMap<>();
        public Map<String, Entry> usernameShapes = new ConcurrentHashMap<>();
        public Map<String, SigEntry> adSignatures = new ConcurrentHashMap<>();
        public Map<String, IpEntry> botIps = new ConcurrentHashMap<>();
        public Map<String, Entry> ipPrefixes = new ConcurrentHashMap<>();
        public Map<String, PlayerEntry> knownPlayers = new ConcurrentHashMap<>();
        public Map<String, SubnetBan> subnetBans = new ConcurrentHashMap<>();
        public Map<String, Fingerprint> fingerprints = new ConcurrentHashMap<>();
        public Map<String, HumanBan> humanBans = new ConcurrentHashMap<>();
        /** v2.3: 实锤bot用过的根命令(本服自适应登录/注册命令库, 跨服通用) */
        public Map<String, Entry> learnedAuthCommands = new ConcurrentHashMap<>();
        public long lastSaved = 0;
    }

    // ---------- 状态 ----------
    private final Data data = new Data();
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private double decayDays = 30;
    private double removeBelow = 0.12;

    private static final Pattern DOMAIN = Pattern.compile(
            "[a-z0-9\\-\\.]{2,}(?:\\.)[a-z0-9\\-\\.]{1,}\\.(?:cn|com|net|org|xyz|top|cc|vip|shop|club|io|me|tv)");
    private static final Pattern CN_DOMAIN = Pattern.compile(
            "[a-z0-9\\-\\.]{2,}\\.[a-z0-9\\-\\.]{1,}\\.cn");
    private static final Pattern WORD_TOKEN = Pattern.compile("[a-z0-9]{5,}");

    public LibraryStore(Path file) {
        this.file = file;
        load();
    }

    public void configure(double decayDays, double removeBelow) {
        this.decayDays = Math.max(1, decayDays);
        this.removeBelow = removeBelow;
    }

    private static long now() { return System.currentTimeMillis(); }
    private static long dayMs() { return 86400000L; }
    private static String lc(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }

    // ---------- 持久化 ----------
    public synchronized void load() {
        try {
            if (Files.exists(file)) {
                Data d = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (d != null) {
                    if (d.botNames != null) data.botNames = d.botNames;
                    if (d.usernameShapes != null) data.usernameShapes = d.usernameShapes;
                    if (d.adSignatures != null) data.adSignatures = d.adSignatures;
                    if (d.botIps != null) data.botIps = d.botIps;
                    if (d.ipPrefixes != null) data.ipPrefixes = d.ipPrefixes;
                    if (d.knownPlayers != null) data.knownPlayers = d.knownPlayers;
                    if (d.subnetBans != null) data.subnetBans = d.subnetBans;
                    if (d.fingerprints != null) data.fingerprints = d.fingerprints;
                    if (d.humanBans != null) data.humanBans = d.humanBans;
                    if (d.learnedAuthCommands != null) data.learnedAuthCommands = d.learnedAuthCommands;
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void markDirty() { dirty.set(true); }

    public void saveIfDirtyAsync() {
        if (!dirty.compareAndSet(true, false)) return;
        writeAtomic();
    }

    public synchronized void forceSave() {
        dirty.set(false);
        writeAtomic();
    }

    private void writeAtomic() {
        try {
            data.lastSaved = now();
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    // ---------- 置信度内核 ----------
    private <T extends Entry> void bump(Map<String, T> map, String key, double add, double cap,
                                        String note, java.util.function.Supplier<T> factory) {
        if (key == null || key.isEmpty()) return;
        T e = map.computeIfAbsent(key, k -> factory.get());
        e.hits++;
        e.lastSeen = now();
        e.confidence = Math.min(cap, e.confidence + add * (1.0 - e.confidence));
        if (note != null && !note.isEmpty()) e.note = note;
        dirty.set(true);
    }

    private double hitConf(Map<String, ? extends Entry> map, String key) {
        Entry e = map.get(key);
        if (e == null) return 0;
        long age = now() - e.lastSeen;
        if (age > (long) (decayDays * dayMs())) return e.confidence * 0.3;
        return e.confidence;
    }

    // ---------- 名字 / 形态 ----------
    /** 名字形态签名: X=大写 x=小写 d=数字 ?=其他 */
    public static String shapeOf(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) sb.append('X');
            else if (Character.isLowerCase(c)) sb.append('x');
            else if (Character.isDigit(c)) sb.append('d');
            else sb.append('?');
        }
        return sb.toString();
    }

    public void recordBotName(String name, double conf, String note) {
        bump(data.botNames, lc(name), conf, 0.99, note, () -> new Entry(0, note));
    }

    public void recordShape(String name, double conf, String note) {
        bump(data.usernameShapes, shapeOf(name), conf, 0.95, note, () -> new Entry(0, note));
    }

    public double shapeConfidence(String name) {
        return hitConf(data.usernameShapes, shapeOf(name));
    }

    public boolean isKnownBotName(String name) {
        Entry e = data.botNames.get(lc(name));
        return e != null && e.confidence >= 0.45 && (now() - e.lastSeen) < (long) (decayDays * dayMs());
    }

    // ---------- IP / 网段 ----------
    public void recordBotIp(String ip, double conf, String note) {
        bump(data.botIps, ip, conf, 0.99, note, () -> new IpEntry(0, note));
    }

    public void recordIpPrefix(String ip, double conf, String note) {
        String prefix = prefix16(ip);
        if (prefix == null) return;
        bump(data.ipPrefixes, prefix, conf, 0.85, note, () -> new Entry(0, note));
    }

    public double ipConfidence(String ip) { return hitConf(data.botIps, ip); }

    public double ipPrefixConfidence(String ip) {
        String prefix = prefix16(ip);
        return prefix == null ? 0 : hitConf(data.ipPrefixes, prefix);
    }

    /** IPv4 /16 网段 (如 14.150.0.0/16); 同运营商段聚合信誉 */
    public static String prefix16(String ip) {
        if (ip == null) return null;
        String[] p = ip.split("\\.");
        if (p.length != 4) return null;
        try {
            Integer.parseInt(p[0]); Integer.parseInt(p[1]);
        } catch (NumberFormatException e) { return null; }
        return p[0] + "." + p[1] + ".0.0/16";
    }

    // ---------- 广告签名 ----------
    /** 归一化: 去色码/去空格/小写; 中文"点"当"."用 */
    public static String normalizeText(String raw) {
        String s = raw == null ? "" : raw;
        s = s.replaceAll("§.", "");
        s = s.replace('点', '.');
        s = s.replaceAll("[\\s\\*\\_\\~\\[\\]\\(\\)\\<>「」【】]", "");
        s = s.toLowerCase(Locale.ROOT);
        return s;
    }

    /** 从广告文本自动抽取签名: 域名优先, 其次长token(如xemcchat), 兜底整句压缩 */
    public List<String> learnAdSignature(String rawText) {
        List<String> learned = new ArrayList<>();
        String norm = normalizeText(rawText);
        if (norm.length() < 5) return learned;

        Matcher m = DOMAIN.matcher(norm);
        while (m.find()) {
            String dom = m.group();
            if (dom.length() >= 6 && !data.adSignatures.containsKey(dom)) {
                data.adSignatures.put(dom, new SigEntry(0.7, "自动抽取域名", false));
                learned.add(dom);
            }
        }
        if (learned.isEmpty()) {
            Matcher t = WORD_TOKEN.matcher(norm);
            while (t.find()) {
                String tok = t.group();
                if (tok.contains("xemc") || tok.endsWith("chat") || tok.endsWith("mc") || CN_DOMAIN.matcher(tok).matches()) {
                    if (!data.adSignatures.containsKey(tok)) {
                        data.adSignatures.put(tok, new SigEntry(0.6, "自动token", false));
                        learned.add(tok);
                    }
                }
            }
        }
        if (learned.isEmpty()) {
            String sig = norm.length() > 40 ? norm.substring(0, 40) : norm;
            if (!data.adSignatures.containsKey(sig)) {
                data.adSignatures.put(sig, new SigEntry(0.6, "自动整句", false));
                learned.add(sig);
            }
        }
        if (!learned.isEmpty()) dirty.set(true);
        return learned;
    }

    public void addSignatureManual(String rawText) {
        String sig = normalizeText(rawText);
        if (sig.length() >= 4) {
            data.adSignatures.put(sig, new SigEntry(0.8, "手动添加", false));
            dirty.set(true);
        }
    }

    /** 返回命中的签名; null 表示无 */
    public String matchAdSignature(String rawText) {
        String norm = normalizeText(rawText);
        if (norm.isEmpty()) return null;
        for (Map.Entry<String, SigEntry> en : data.adSignatures.entrySet()) {
            if (en.getValue().confidence >= 0.3 && norm.contains(en.getKey())) return en.getKey();
        }
        return null;
    }

    // ---------- 已登记真人 ----------
    public void addKnownPlayer(String name, boolean verified, String note) {
        PlayerEntry e = data.knownPlayers.computeIfAbsent(lc(name), k -> new PlayerEntry());
        if (e.firstSeen == 0) e.firstSeen = now();
        e.lastSeen = now();
        if (verified) e.verified = true;
        if (note != null && !note.isEmpty()) e.note = note;
        dirty.set(true);
    }

    public void addPlayMinutes(String name, int minutes) {
        PlayerEntry e = data.knownPlayers.get(lc(name));
        if (e != null) { e.playMinutes += minutes; dirty.set(true); }
    }

    public boolean isVerifiedPlayer(String name) {
        PlayerEntry e = data.knownPlayers.get(lc(name));
        return e != null && e.verified;
    }

    public boolean isSeededPlayer(String name) {
        PlayerEntry e = data.knownPlayers.get(lc(name));
        return e != null && !e.verified;
    }

    public int knownPlayerCount() { return data.knownPlayers.size(); }

    public int verifiedPlayerCount() {
        int n = 0;
        for (PlayerEntry e : data.knownPlayers.values()) if (e.verified) n++;
        return n;
    }

    // ---------- 衰减与清理 ----------
    public synchronized int decayAndCleanup() {
        long decayMs = (long) (decayDays * dayMs());
        int removed = 0;
        removed += decayMap(data.botNames, decayMs);
        removed += decayMap(data.usernameShapes, decayMs);
        removed += decayMap(data.ipPrefixes, decayMs);
        removed += decaySigMap(decayMs);
        long now = now();
        for (Map.Entry<String, IpEntry> en : data.botIps.entrySet()) {
            IpEntry e = en.getValue();
            if (e.bannedUntil > 0 && e.bannedUntil < now) { e.bannedUntil = 0; dirty.set(true); }
            if (now - e.lastSeen > decayMs) e.confidence *= 0.7;
            if (e.confidence < removeBelow && e.bannedUntil == 0) { data.botIps.remove(en.getKey()); removed++; }
            else if (e.confidence < removeBelow) e.confidence = removeBelow; // 封禁中的IP只压分不清除
        }
        return removed;
    }

    private int decayMap(Map<String, ? extends Entry> map, long decayMs) {
        int removed = 0;
        long now = now();
        for (Map.Entry<String, ? extends Entry> en : map.entrySet()) {
            Entry e = en.getValue();
            if (now - e.lastSeen > decayMs) e.confidence *= 0.75;
            if (e.confidence < removeBelow) { map.remove(en.getKey()); removed++; }
        }
        return removed;
    }

    private int decaySigMap(long decayMs) {
        int removed = 0;
        long now = now();
        for (Map.Entry<String, SigEntry> en : data.adSignatures.entrySet()) {
            SigEntry e = en.getValue();
            if (now - e.lastSeen > decayMs && e.confidence < 0.5) { data.adSignatures.remove(en.getKey()); removed++; }
        }
        return removed;
    }

    // ---------- 临时封禁 ----------
    public void setBannedUntil(String ip, long until, String note) {
        IpEntry e = data.botIps.computeIfAbsent(ip, k -> new IpEntry(0.3, note));
        e.bannedUntil = Math.max(e.bannedUntil, until);
        e.lastSeen = now();
        if (note != null && !note.isEmpty()) e.note = note;
        dirty.set(true);
    }

    public long getBannedUntil(String ip) {
        IpEntry e = data.botIps.get(ip);
        if (e == null) return 0;
        if (e.bannedUntil > 0 && e.bannedUntil < now()) { e.bannedUntil = 0; dirty.set(true); return 0; }
        return e.bannedUntil;
    }

    public void clearBan(String ip) {
        IpEntry e = data.botIps.get(ip);
        if (e != null) { e.bannedUntil = 0; e.confidence = Math.min(e.confidence, 0.2); dirty.set(true); }
        // 同步清除网段封禁(/24 + /16)
        for (int scope : new int[]{24, 16}) {
            String key = subnetKey(ip, scope);
            if (key != null) {
                SubnetBan b = data.subnetBans.get(key);
                if (b != null) { b.bannedUntil = 0; b.hits = 0; dirty.set(true); }
            }
        }
    }

    // ---------- 网段封禁(封禁规避防御) ----------

    /** IPv4 子网键: scope=24 -> a.b.c.0/24, scope=16 -> a.b.0.0/16; 非IPv4返回null */
    public static String subnetKey(String ip, int scope) {
        if (ip == null || scope <= 0) return null;
        String[] p = ip.split("\\.");
        if (p.length != 4) return null;
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]), c = Integer.parseInt(p[2]);
            if (a > 255 || b > 255 || c > 255) return null;
            if (scope == 24) return a + "." + b + "." + c + ".0/24";
            if (scope == 16) return a + "." + b + ".0.0/16";
        } catch (NumberFormatException ignored) {}
        return null;
    }

    /**
     * 封禁网段, 返回实际生效分钟数(逐犯加重后)。
     * 同一网段第 escalateAfter 次之后, 时长 = minutes * multiplier^(n-after), 封顶 maxMinutes。
     * 例: 120分钟 x3倍 第2次后 -> 120/120/360/1080/3240... 封顶 maxMinutes。
     */
    public synchronized int banSubnet(String ip, int scope, int minutes, String note,
                                      boolean escalate, int escalateAfter, int multiplier, int maxMinutes) {
        String key = subnetKey(ip, scope);
        if (key == null) return 0;
        SubnetBan b = data.subnetBans.computeIfAbsent(key, k -> new SubnetBan());
        b.banCount++;
        if (note != null && !note.isEmpty()) b.note = note;
        int eff = minutes;
        if (escalate && b.banCount > escalateAfter) {
            long mult = 1;
            for (int i = escalateAfter; i < b.banCount && mult < 1000000L; i++) mult *= multiplier;
            eff = (int) Math.min(maxMinutes, minutes * mult);
        }
        long until = now() + eff * 60000L;
        b.bannedUntil = Math.max(b.bannedUntil, until);
        dirty.set(true);
        return eff;
    }

    /** 命中网段封禁: /24 优先(更精确), 其次 /16; 过期自动清理; 无封禁返回null */
    public SubnetBan activeSubnetBan(String ip) {
        String k24 = subnetKey(ip, 24);
        if (k24 != null) {
            SubnetBan b = data.subnetBans.get(k24);
            if (b != null) {
                if (b.bannedUntil > now()) return b;
                if (b.bannedUntil > 0) { b.bannedUntil = 0; dirty.set(true); }
            }
        }
        String k16 = subnetKey(ip, 16);
        if (k16 != null) {
            SubnetBan b = data.subnetBans.get(k16);
            if (b != null) {
                if (b.bannedUntil > now()) return b;
                if (b.bannedUntil > 0) { b.bannedUntil = 0; dirty.set(true); }
            }
        }
        return null;
    }

    public int subnetBanCount(String key) {
        SubnetBan b = data.subnetBans.get(key);
        return b == null ? 0 : b.banCount;
    }

    /** 手动解除指定网段键的封禁(键形如 14.24.0.0/16) */
    public void clearSubnetBan(String key) {
        if (key == null) return;
        SubnetBan b = data.subnetBans.get(key);
        if (b != null) { b.bannedUntil = 0; b.hits = 0; dirty.set(true); }
    }

    /**
     * 逃避尝试被拦(换IP+换名重进): 计数 + 每次延长 addMinutes,
     * 总时长封顶为 now + maxMinutes(防无限膨胀)。返回累计逃避次数。
     */
    public synchronized int evasionHit(String ip, int addMinutes, int maxMinutes) {
        SubnetBan b = activeSubnetBan(ip);
        if (b == null) return 0;
        b.hits++;
        long maxUntil = now() + maxMinutes * 60000L;
        b.bannedUntil = Math.max(b.bannedUntil, Math.min(b.bannedUntil + addMinutes * 60000L, maxUntil));
        dirty.set(true);
        return b.hits;
    }

    public int activeSubnetBanCount() {
        int n = 0;
        long now = now();
        for (SubnetBan b : data.subnetBans.values()) if (b.bannedUntil > now) n++;
        return n;
    }

    // ---------- 真人封禁追踪 ----------

    /** 采集/更新玩家客户端指纹(进服时自动调用, 空字段不覆盖) */
    public void recordFingerprint(String name, String brand, String locale, int protocol, String ip) {
        if (name == null || name.isEmpty()) return;
        Fingerprint f = data.fingerprints.computeIfAbsent(lc(name), k -> new Fingerprint());
        if (brand != null && !brand.isEmpty()) f.brand = brand;
        if (locale != null && !locale.isEmpty()) f.locale = locale;
        if (protocol >= 0) f.protocol = protocol;
        if (ip != null && !ip.isEmpty()) f.lastIp = ip;
        f.lastSeen = now();
        dirty.set(true);
    }

    public Fingerprint fingerprintOf(String name) { return data.fingerprints.get(lc(name)); }

    /** 封禁真人(在线则带实时指纹, 离线用最近一次缓存指纹); minutes<=0 表示永久 */
    public synchronized HumanBan banHuman(String name, String ip, String brand, String locale,
                                          int protocol, int minutes, String reason) {
        HumanBan b = data.humanBans.computeIfAbsent(lc(name), k -> new HumanBan());
        b.name = lc(name);
        b.reason = reason == null ? "" : reason;
        b.bannedAt = now();
        b.until = minutes <= 0 ? 0 : now() + minutes * 60000L;
        if (ip != null && !ip.isEmpty()) {
            b.lastIp = ip;
            String k24 = subnetKey(ip, 24);
            if (k24 != null) b.subnet24 = k24;
            String k16 = subnetKey(ip, 16);
            if (k16 != null) b.subnet16 = k16;
        }
        if (brand != null && !brand.isEmpty()) b.brand = brand;
        if (locale != null && !locale.isEmpty()) b.locale = locale;
        if (protocol >= 0) b.protocol = protocol;
        dirty.set(true);
        return b;
    }

    /** 名字是否处于真人封禁中(过期自动清除) */
    public HumanBan activeHumanBan(String name) {
        HumanBan b = data.humanBans.get(lc(name));
        if (b == null) return null;
        if (b.until > 0 && b.until < now()) { data.humanBans.remove(lc(name)); dirty.set(true); return null; }
        return b;
    }

    /** 命中真人封禁网段(/24 或 /16); 无则 null */
    public HumanBan humanBanBySubnet(String ip) {
        String k24 = subnetKey(ip, 24), k16 = subnetKey(ip, 16);
        if (k24 == null && k16 == null) return null;
        for (HumanBan b : data.humanBans.values()) {
            if (b.until > 0 && b.until < now()) continue;
            if ((k16 != null && k16.equals(b.subnet16)) || (k24 != null && k24.equals(b.subnet24))) return b;
        }
        return null;
    }

    public boolean pardonHuman(String name) { return data.humanBans.remove(lc(name)) != null; }

    public int humanEvasionHit(HumanBan b) { b.evasionHits++; dirty.set(true); return b.evasionHits; }

    public int humanBanCount() { return data.humanBans.size(); }

    public List<HumanBan> activeHumanBans() {
        List<HumanBan> out = new ArrayList<>();
        for (HumanBan b : data.humanBans.values()) if (b.until <= 0 || b.until > now()) out.add(b);
        return out;
    }

    public int activeBanCount() {
        int n = 0;
        long now = now();
        for (IpEntry e : data.botIps.values()) if (e.bannedUntil > now) n++;
        return n;
    }

    // ---------- 本服命令自适应学习 (v2.3: 跨服务器通用) ----------

    /** 实锤bot用过的根命令 -> 学进本服命令库(之后按注册类命令对待) */
    public void learnAuthCommand(String root, String note) {
        if (root == null || root.isEmpty() || root.length() > 24) return;
        String k = lc(root);
        Entry e = data.learnedAuthCommands.get(k);
        if (e == null) {
            data.learnedAuthCommands.put(k, new Entry(0.8, note == null ? "实锤bot自动学习" : note));
        } else {
            e.confidence = Math.min(0.99, e.confidence + 0.05);
            e.hits++;
            e.lastSeen = now();
            if (note != null && !note.isEmpty()) e.note = note;
        }
        dirty.set(true);
    }

    public boolean isLearnedAuthCommand(String root) {
        if (root == null) return false;
        Entry e = data.learnedAuthCommands.get(lc(root));
        return e != null && e.confidence >= 0.5;
    }

    public boolean removeLearnedAuthCommand(String root) {
        boolean removed = data.learnedAuthCommands.remove(lc(root)) != null;
        if (removed) dirty.set(true);
        return removed;
    }

    public int learnedAuthCommandCount() { return data.learnedAuthCommands.size(); }

    public List<String> learnedAuthCommands(int limit) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Entry> en : data.learnedAuthCommands.entrySet()) {
            out.add(en.getKey() + "(命中" + en.getValue().hits + ")");
            if (out.size() >= limit) break;
        }
        return out;
    }

    // ---------- 手动修正: 移除 / 撤销 / 历史 (v2.2) ----------

    /** 可撤销的学习条目类型 */
    public enum LearnKind { BOT_NAME, SHAPE, SIG, BOT_IP, IP_PREFIX, TRUST }

    /** 一条可撤销的学习/移除记录 */
    public static class LearnOp {
        public LearnKind kind;
        public String key;
        public long at;
        public String source;   // 手动/自动
        public boolean removal; // true=这是一次移除(undo会恢复)
        public double oldConf;  // 移除时旧的置信度(用于恢复)
        public String oldNote = "";

        public String kindText() {
            switch (kind) {
                case BOT_NAME: return "假人名";
                case SHAPE: return "名字形态";
                case SIG: return "广告签名";
                case BOT_IP: return "风险IP";
                case IP_PREFIX: return "风险网段";
                case TRUST: return "已登记真人";
            }
            return kind.name();
        }
    }

    private final java.util.ArrayDeque<LearnOp> learnHistory = new java.util.ArrayDeque<>();
    private static final int HISTORY_CAP = 60;

    private void pushHistory(LearnKind kind, String key, String source, boolean removal, double oldConf, String oldNote) {
        if (key == null || key.isEmpty()) return;
        LearnOp op = new LearnOp();
        op.kind = kind; op.key = key; op.source = source == null ? "" : source;
        op.at = now(); op.removal = removal; op.oldConf = oldConf; op.oldNote = oldNote == null ? "" : oldNote;
        synchronized (learnHistory) {
            learnHistory.addLast(op);
            while (learnHistory.size() > HISTORY_CAP) learnHistory.removeFirst();
        }
    }

    /** 手动学习重载: 带 source, 记入可撤销历史 */
    public void recordBotNameManual(String name, double conf, String note) {
        recordBotName(name, conf, note);
        pushHistory(LearnKind.BOT_NAME, lc(name), note, false, 0, "");
    }

    public void recordShapeManual(String name, double conf, String note) {
        recordShape(name, conf, note);
        pushHistory(LearnKind.SHAPE, shapeOf(name), note, false, 0, "");
    }

    public void recordBotIpManual(String ip, double conf, String note) {
        recordBotIp(ip, conf, note);
        pushHistory(LearnKind.BOT_IP, ip, note, false, 0, "");
    }

    public void recordIpPrefixManual(String ip, double conf, String note) {
        recordIpPrefix(ip, conf, note);
        pushHistory(LearnKind.IP_PREFIX, prefix16(ip), note, false, 0, "");
    }

    public void addTrustedManual(String name, String note) {
        addKnownPlayer(name, true, note);
        pushHistory(LearnKind.TRUST, lc(name), note, false, 0, "");
    }

    /** 移除假人名特征 */
    public boolean removeBotName(String name) {
        Entry e = data.botNames.remove(lc(name));
        if (e != null) {
            pushHistory(LearnKind.BOT_NAME, lc(name), "手动移除", true, e.confidence, e.note);
            dirty.set(true);
        }
        return e != null;
    }

    /** 移除名字形态(参数填玩家名或形态串均可) */
    public boolean removeShape(String nameOrShape) {
        String shape = nameOrShape.matches("^[Xxd?]+$") ? nameOrShape : shapeOf(nameOrShape);
        Entry e = data.usernameShapes.remove(shape);
        if (e != null) {
            pushHistory(LearnKind.SHAPE, shape, "手动移除", true, e.confidence, e.note);
            dirty.set(true);
        }
        return e != null;
    }

    /** 移除广告签名 */
    public boolean removeSignature(String sig) {
        SigEntry e = data.adSignatures.remove(normalizeText(sig));
        if (e != null) {
            pushHistory(LearnKind.SIG, normalizeText(sig), "手动移除", true, e.confidence, e.note);
            dirty.set(true);
        }
        return e != null;
    }

    /** 移除风险IP(不动封禁, 只清信誉特征) */
    public boolean removeBotIp(String ip) {
        IpEntry e = data.botIps.remove(ip);
        if (e != null) {
            pushHistory(LearnKind.BOT_IP, ip, "手动移除", true, e.confidence, e.note);
            dirty.set(true);
        }
        return e != null;
    }

    /** 移除风险网段(参数填IP或 a.b.0.0/16 均可) */
    public boolean removeIpPrefix(String ipOrPrefix) {
        String prefix = ipOrPrefix.contains("/") ? ipOrPrefix : prefix16(ipOrPrefix);
        if (prefix == null) return false;
        Entry e = data.ipPrefixes.remove(prefix);
        if (e != null) {
            pushHistory(LearnKind.IP_PREFIX, prefix, "手动移除", true, e.confidence, e.note);
            dirty.set(true);
        }
        return e != null;
    }

    /** 取消真人信任(降为未验证) */
    public boolean removeTrusted(String name) {
        PlayerEntry e = data.knownPlayers.get(lc(name));
        if (e != null && e.verified) {
            e.verified = false;
            pushHistory(LearnKind.TRUST, lc(name), "取消信任", true, 0, e.note);
            dirty.set(true);
            return true;
        }
        return false;
    }

    /** 最近的学习/移除记录(新的在前) */
    public List<LearnOp> recentOps(int n) {
        List<LearnOp> out = new ArrayList<>();
        synchronized (learnHistory) {
            var it = learnHistory.descendingIterator();
            while (it.hasNext() && out.size() < n) out.add(it.next());
        }
        return out;
    }

    /** 撤销最近一条学习/移除记录: 学习->移除, 移除->恢复。成功返回该条, 无可撤销返回 null */
    public synchronized LearnOp undoLast() {
        LearnOp op;
        synchronized (learnHistory) { op = learnHistory.pollLast(); }
        if (op == null) return null;
        switch (op.kind) {
            case BOT_NAME -> { if (op.removal) data.botNames.put(op.key, new Entry(Math.max(op.oldConf, 0.45), "撤销恢复")); else data.botNames.remove(op.key); }
            case SHAPE -> { if (op.removal) data.usernameShapes.put(op.key, new Entry(Math.max(op.oldConf, 0.15), "撤销恢复")); else data.usernameShapes.remove(op.key); }
            case SIG -> { if (op.removal) data.adSignatures.put(op.key, new SigEntry(Math.max(op.oldConf, 0.6), "撤销恢复", false)); else data.adSignatures.remove(op.key); }
            case BOT_IP -> { if (op.removal) data.botIps.put(op.key, new IpEntry(Math.max(op.oldConf, 0.3), "撤销恢复")); else data.botIps.remove(op.key); }
            case IP_PREFIX -> { if (op.removal) data.ipPrefixes.put(op.key, new Entry(Math.max(op.oldConf, 0.15), "撤销恢复")); else data.ipPrefixes.remove(op.key); }
            case TRUST -> {
                PlayerEntry e = data.knownPlayers.computeIfAbsent(op.key, k -> new PlayerEntry());
                if (op.removal) e.verified = true; else e.verified = false;
                if (e.firstSeen == 0) e.firstSeen = now();
                e.lastSeen = now();
            }
        }
        dirty.set(true);
        return op;
    }

    // ---------- 概览 ----------
    public int botNameCount() { return data.botNames.size(); }
    public int shapeCount() { return data.usernameShapes.size(); }
    public int botIpCount() { return data.botIps.size(); }
    public int prefixCount() { return data.ipPrefixes.size(); }
    public int signatureCount() { return data.adSignatures.size(); }

    public List<String> topSummary(int n) {
        List<String> out = new ArrayList<>();
        out.add("假人名 " + data.botNames.size() + " | 名字形态 " + data.usernameShapes.size()
                + " | 广告签名 " + data.adSignatures.size()
                + " | 风险IP " + data.botIps.size() + "(封禁中 " + activeBanCount() + ")"
                + " | 风险网段 " + data.ipPrefixes.size()
                + " | 网段封禁 " + activeSubnetBanCount()
                + " | 已登记玩家 " + data.knownPlayers.size() + "(真人 " + verifiedPlayerCount() + ")");
        out.add("-- 高置信名字形态 --");
        data.usernameShapes.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue().confidence, a.getValue().confidence))
                .limit(n).forEach(e -> out.add("  " + e.getKey() + "  置信" + f2(e.getValue().confidence) + "  命中" + e.getValue().hits));
        out.add("-- 高置信风险IP --");
        data.botIps.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue().confidence, a.getValue().confidence))
                .limit(n).forEach(e -> out.add("  " + e.getKey() + "  置信" + f2(e.getValue().confidence)
                        + "  命中" + e.getValue().hits + (e.getValue().bannedUntil > now() ? "  [封禁中]" : "")));
        out.add("-- 广告签名 --");
        data.adSignatures.keySet().stream().limit(n).forEach(s -> out.add("  " + s));
        out.add("-- 生效中的网段封禁 --");
        data.subnetBans.entrySet().stream()
                .filter(e -> e.getValue().bannedUntil > now())
                .sorted((a, b) -> Long.compare(b.getValue().bannedUntil, a.getValue().bannedUntil))
                .limit(n).forEach(e -> out.add("  " + e.getKey() + "  剩余" + ((e.getValue().bannedUntil - now()) / 60000)
                        + "分钟  逃避" + e.getValue().hits + "次  累封" + e.getValue().banCount + "次"));
        return out;
    }

    private static String f2(double d) { return String.format(Locale.ROOT, "%.2f", d); }
}
