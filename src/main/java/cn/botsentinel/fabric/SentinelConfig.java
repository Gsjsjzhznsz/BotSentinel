package cn.botsentinel.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fabric 端配置 (config/BotSentinel.json, 与插件版 config.yml 字段一一对应)。
 */
public class SentinelConfig {

    public String mode = "block";
    public int blockThreshold = 70;
    public int observeThreshold = 45;
    public int minNameRandomness = 50;

    public String kickMessage = "§e[风控] 该账号特征被判定为疑似机器人, 无法加入";
    public String banMessage = "§e[风控] 该网络地址已被临时限制, 请稍后再试";
    public String disposeMessage = "§e[风控] 该账号行为异常, 已被移出服务器";
    public String humanBanMessage = "§e[风控] 该账号已被限制登录";
    public String geoDenyMessage = "§e[风控] 当前地区暂未开放加入本服务器";

    public List<String> whitelist = new ArrayList<>();

    public boolean ipBanEnabled = true;
    public int ipBanMinutes = 120;
    public int autoBanCount = 6;

    public int windowMinutes = 10;
    public boolean prefixLearn = true;

    public boolean autoLearn = true;
    public int saveIntervalMinutes = 5;
    public int decayDays = 30;
    public double removeBelow = 0.12;

    public int watchSeconds = 45;
    public int instantRegisterSeconds = 8;
    public int confirmAuthAttempts = 2;
    public int autoTrustMinutes = 10;
    public List<String> authCommands = Arrays.asList(
            "e", "l", "li", "log", "login", "reg", "regi", "regis", "register");

    public List<String> builtInSignatures = Arrays.asList("xemcchat", "xemc.cn");
    public boolean genericDomainHeuristic = true;
    public boolean protectKnownPlayers = true;

    // 封禁规避防御
    public boolean banEvasionEnabled = true;
    public int banEvasionScope = 16;
    public int humanBanMinutes = 10080;      // 0=永久
    public boolean banEvasionBlockNonverified = true;
    public boolean banEvasionFingerprint = true;
    public boolean banEvasionBlockBotlike = true;
    public boolean banEvasionEscalate = true;
    public int banEvasionEscalateAfter = 2;
    public int banEvasionEscalateMultiplier = 3;
    public int banEvasionMaxMinutes = 10080;

    // 归属地拦截
    public boolean geoEnabled = false;
    public boolean geoMainlandOnly = true;
    public List<String> geoBlockedRegions = new ArrayList<>();
    public List<String> geoAllowedRegions = new ArrayList<>();
    public List<String> geoAllowIps = new ArrayList<>();
    public int geoUpdateDays = 7;

    public boolean blockMode() { return !"observe".equalsIgnoreCase(mode); }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Path file;

    public static SentinelConfig load(Path file) {
        SentinelConfig c = new SentinelConfig();
        c.file = file;
        try {
            if (Files.exists(file)) {
                SentinelConfig loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), SentinelConfig.class);
                if (loaded != null) return loaded;
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(c), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        c.file = file;
        return c;
    }

    public void save() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
        }
    }

    public boolean isWhitelisted(String name) {
        if (whitelist == null) return false;
        for (String s : whitelist) if (s != null && s.equalsIgnoreCase(name)) return true;
        return false;
    }
}
