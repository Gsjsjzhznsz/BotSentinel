package cn.botsentinel;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 配置封装(mode 支持 block/observe, 兼容旧版 aggressive 值)。
 */
public class Config {

    public enum Mode { BLOCK, OBSERVE }

    private final BotSentinelPlugin plugin;
    private YamlConfiguration yaml;

    public Mode mode = Mode.BLOCK;
    public int blockThreshold = 70;
    public int observeThreshold = 45;
    public int minNameRandomness = 50;
    public String kickMessage;
    public String banMessage;
    public String disposeMessage;
    public Set<String> whitelist = new HashSet<>();

    public boolean ipBanEnabled = true;
    public int ipBanMinutes = 120;
    public int autoBanCount = 6;

    public int windowMinutes = 10;
    public boolean prefixLearn = true;

    public boolean autoLearn = true;
    public int saveIntervalMinutes = 5;
    public double decayDays = 30;
    public double removeBelow = 0.12;
    public boolean seedFromUsercache = true;

    public int watchSeconds = 45;
    public int instantRegisterSeconds = 8;
    public int confirmAuthAttempts = 2;
    public int autoTrustMinutes = 10;
    public List<String> authCommands;

    public boolean genericDomainHeuristic = true;
    public boolean protectKnownPlayers = true;

    // 封禁规避防御
    public boolean banEvasionEnabled = true;
    public int banEvasionScope = 16;   // 0=关 /24=/24网段 /16=/16运营商段
    public boolean banEvasionBlockBotlike = true;
    public boolean banEvasionEscalate = true;
    public int banEvasionEscalateAfter = 2;
    public int banEvasionMultiplier = 3;
    public int banEvasionMaxMinutes = 10080;
    // 真人封禁追踪
    public int banEvasionHumanMinutes = 10080;  // 0=永久
    public boolean banEvasionBlockNonverified = true;
    public boolean banEvasionFingerprint = true;
    public String humanBanMessage;

    // 归属地拦截(v2.2)
    public boolean geoEnabled = false;
    public boolean geoMainlandOnly = true;
    public List<String> geoBlockedRegions = new java.util.ArrayList<>();
    public List<String> geoAllowedRegions = new java.util.ArrayList<>();
    public List<String> geoAllowIps = new java.util.ArrayList<>();
    public int geoUpdateDays = 7;
    public String geoDenyMessage;

    public Config(BotSentinelPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File f = new File(plugin.getDataFolder(), "config.yml");
        if (!f.exists()) plugin.saveDefaultConfig();
        yaml = YamlConfiguration.loadConfiguration(f);

        String m = yaml.getString("mode", "block");
        if ("observe".equalsIgnoreCase(m)) mode = Mode.OBSERVE;
        else mode = Mode.BLOCK;   // block / aggressive 一律拦截

        blockThreshold = clamp(yaml.getInt("block-threshold", 70), 10, 100);
        observeThreshold = clamp(yaml.getInt("observe-threshold", 45), 5, 100);
        minNameRandomness = clamp(yaml.getInt("min-name-randomness", 50), 0, 95);

        kickMessage = color(yaml.getString("kick-message", "§e[风控] 该账号特征被判定为疑似机器人, 无法加入"));
        banMessage = color(yaml.getString("ban-message", "§e[风控] 该网络地址已被临时限制, 请稍后再试"));
        disposeMessage = color(yaml.getString("dispose-message", "§e[风控] 该账号行为异常, 已被移出服务器"));

        whitelist.clear();
        for (String s : yaml.getStringList("whitelist")) whitelist.add(s.toLowerCase(java.util.Locale.ROOT));

        ipBanEnabled = yaml.getBoolean("temp-ip-ban.enabled", true);
        ipBanMinutes = clamp(yaml.getInt("temp-ip-ban.minutes", 120), 1, 10080);
        autoBanCount = clamp(yaml.getInt("temp-ip-ban.auto-ban-count", 6), 2, 100);

        windowMinutes = clamp(yaml.getInt("ip-risk.window-minutes", 10), 1, 120);
        prefixLearn = yaml.getBoolean("ip-risk.prefix-learn", true);

        autoLearn = yaml.getBoolean("auto-learn.enabled", true);
        saveIntervalMinutes = clamp(yaml.getInt("auto-learn.save-interval-minutes", 5), 1, 60);
        decayDays = clamp(yaml.getInt("auto-learn.decay-days", 30), 1, 365);
        removeBelow = yaml.getDouble("auto-learn.remove-below", 0.12);
        seedFromUsercache = yaml.getBoolean("auto-learn.seed-from-usercache", true);

        watchSeconds = clamp(yaml.getInt("sentinel.watch-seconds", 45), 10, 600);
        instantRegisterSeconds = clamp(yaml.getInt("sentinel.instant-register-seconds", 8), 1, 120);
        confirmAuthAttempts = clamp(yaml.getInt("sentinel.confirm-auth-attempts", 2), 1, 10);
        autoTrustMinutes = clamp(yaml.getInt("sentinel.auto-trust-minutes", 10), 1, 180);
        authCommands = yaml.getStringList("sentinel.auth-commands");
        if (authCommands.isEmpty()) {
            authCommands = java.util.Arrays.asList("e", "l", "li", "log", "login", "reg", "regi", "regis", "register");
        }

        genericDomainHeuristic = yaml.getBoolean("chat.generic-domain-heuristic", true);
        protectKnownPlayers = yaml.getBoolean("chat.protect-known-players", true);

        // 封禁规避防御(ban-evasion): 针对重启路由器换IP+换用户名绕过封禁
        banEvasionEnabled = yaml.getBoolean("ban-evasion.enabled", true);
        String scope = yaml.getString("ban-evasion.scope", "16");
        if ("24".equals(scope)) banEvasionScope = 24;
        else if ("off".equalsIgnoreCase(scope) || "0".equals(scope)) banEvasionScope = 0;
        else banEvasionScope = 16;
        banEvasionBlockBotlike = yaml.getBoolean("ban-evasion.block-botlike-names", true);
        banEvasionEscalate = yaml.getBoolean("ban-evasion.escalate", true);
        banEvasionEscalateAfter = clamp(yaml.getInt("ban-evasion.escalate-after", 2), 1, 50);
        banEvasionMultiplier = clamp(yaml.getInt("ban-evasion.escalate-multiplier", 3), 2, 100);
        banEvasionMaxMinutes = clamp(yaml.getInt("ban-evasion.max-minutes", 10080), 1, 43200);
        // 真人封禁追踪(重启路由器换IP+换名也逃不掉)
        banEvasionHumanMinutes = clamp(yaml.getInt("ban-evasion.human-ban-minutes", 10080), 0, 43200); // 0=永久
        banEvasionBlockNonverified = yaml.getBoolean("ban-evasion.block-nonverified", true);
        banEvasionFingerprint = yaml.getBoolean("ban-evasion.fingerprint", true);
        humanBanMessage = color(yaml.getString("human-ban-message", "§e[风控] 该账号已被限制登录"));

        // 归属地拦截(geo): 国外/港澳台等, /atb geo on 一键开启
        geoEnabled = yaml.getBoolean("geo.enabled", false);
        geoMainlandOnly = yaml.getBoolean("geo.mainland-only", true);
        geoBlockedRegions = yaml.getStringList("geo.blocked-regions");
        geoAllowedRegions = yaml.getStringList("geo.allowed-regions");
        geoAllowIps = yaml.getStringList("geo.allow-ips");
        geoUpdateDays = clamp(yaml.getInt("geo.update-days", 7), 1, 365);
        geoDenyMessage = color(yaml.getString("geo.denied-message",
                "§e[风控] 当前地区暂未开放加入本服务器"));
    }

    /** geo 开关持久化(/atb geo on|off 用) */
    public void setGeoEnabled(boolean on) {
        geoEnabled = on;
        File f = new File(plugin.getDataFolder(), "config.yml");
        yaml.set("geo.enabled", on);
        try { yaml.save(f); } catch (Exception ignored) {}
    }

    public void saveMode(Mode m) {
        mode = m;
        File f = new File(plugin.getDataFolder(), "config.yml");
        yaml.set("mode", m == Mode.BLOCK ? "block" : "observe");
        try { yaml.save(f); } catch (Exception ignored) {}
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static String color(String s) {
        return s == null ? "" : s.replace('&', '§');
    }
}
