package cn.botsentinel;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * BotSentinel v2.0 主类
 *
 * 无感风控反机器人(Folia/Lophine 兼容):
 *  - 第一层: AsyncPlayerPreLoginEvent 进服前评分拦截(不产生join消息, QQ桥无感知)
 *  - 第二层: 进服后行为实锤(秒发/e注册、限速器踢、广告) -> 反哺特征库
 *  - 特征库全自动更新: 拦截学习 + 实锤学习 + 真人自动信任 + 定时落盘 + 置信度衰减
 */
public class BotSentinelPlugin extends JavaPlugin {

    private Config configWrapper;
    private LibraryStore library;
    private StatsStore stats;
    private RiskScorer scorer;
    private IpRiskTracker ipTracker;
    private AlertBus alert;
    private PreLoginListener preLogin;
    private JoinSentinel joinSentinel;
    private ChatGuard chatGuard;
    private GeoRegionManager geo;

    @Override
    public void onEnable() {
        this.configWrapper = new Config(this);
        this.library = new LibraryStore(new File(getDataFolder(), "library.json").toPath());
        this.stats = new StatsStore(new File(getDataFolder(), "stats.json").toPath());
        this.library.configure(configWrapper.decayDays, configWrapper.removeBelow);
        this.scorer = new RiskScorer(this);
        this.ipTracker = new IpRiskTracker(this);
        this.alert = new AlertBus(this);
        this.preLogin = new PreLoginListener(this);
        this.joinSentinel = new JoinSentinel(this);
        this.chatGuard = new ChatGuard(this);
        this.geo = new GeoRegionManager(new File(getDataFolder(), "geo/ip2region.xdb").toPath(),
                s -> getLogger().info(s.replace("[地区库] ", "[特征库] ")),
                s -> getLogger().warning(s.replace("[地区库] ", "[特征库] ")));
        this.geo.configure(configWrapper.geoEnabled, configWrapper.geoMainlandOnly,
                configWrapper.geoBlockedRegions, configWrapper.geoAllowedRegions,
                configWrapper.geoAllowIps, configWrapper.geoUpdateDays);

        getServer().getPluginManager().registerEvents(preLogin, this);
        getServer().getPluginManager().registerEvents(joinSentinel, this);
        getServer().getPluginManager().registerEvents(chatGuard, this);

        PluginCommand cmd = getCommand("atb");
        if (cmd != null) {
            AtbCommand executor = new AtbCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // 自动更新特征库的定时任务(全部异步, 不碰主线程)
        long saveMs = configWrapper.saveIntervalMinutes * 60000L;
        FoliaBridge.runAsyncTimer(this, saveMs, saveMs, () -> {
            library.saveIfDirtyAsync();
            stats.saveIfDirtyAsync();
            ipTracker.cleanup();
        });
        // 置信度衰减 + 清理 + 归属地库自动更新: 每6小时
        FoliaBridge.runAsyncTimer(this, 6 * 3600_000L, 6 * 3600_000L, () -> {
            int removed = library.decayAndCleanup();
            if (removed > 0) alert.info("[特征库] 衰减清理完成, 移除过期特征 " + removed + " 条");
            geo.checkUpdate(false);
        });

        // 归属地库: 首次自动下载/加载(异步, 失败不影响启动)
        if (configWrapper.geoEnabled) {
            FoliaBridge.runAsync(this, () -> geo.initAsync());
        }

        // usercache 播种已登记玩家(异步)
        if (configWrapper.seedFromUsercache) {
            FoliaBridge.runAsync(this, this::seedFromUsercache);
        }

        getLogger().info("BotSentinel v2.2 已启动 | 模式: " + (configWrapper.mode == Config.Mode.BLOCK ? "拦截" : "观察")
                + " | 内核: " + FoliaBridge.kernelName()
                + " | 特征库: 假人名" + library.botNameCount() + " 形态" + library.shapeCount()
                + " 签名" + library.signatureCount() + " 风险IP" + library.botIpCount()
                + " 已登记" + library.knownPlayerCount()
                + " | 地区拦截: " + (configWrapper.geoEnabled ? "开启" : "关闭"));
    }

    @Override
    public void onDisable() {
        // 判空保护: 即使 onEnable 阶段出错也不会连环报错
        if (library != null) library.forceSave();
        if (stats != null) stats.forceSave();
        if (joinSentinel != null) joinSentinel.shutdownCleanup();
        getLogger().info("BotSentinel 已关闭, 特征库与统计已保存");
    }

    /** reload 后重挂 geo 配置(/atb reload 调用) */
    public void reconfigureGeo() {
        geo.configure(configWrapper.geoEnabled, configWrapper.geoMainlandOnly,
                configWrapper.geoBlockedRegions, configWrapper.geoAllowedRegions,
                configWrapper.geoAllowIps, configWrapper.geoUpdateDays);
        if (configWrapper.geoEnabled) FoliaBridge.runAsync(this, () -> geo.initAsync());
    }

    /** 从 usercache.json 播种已登记玩家(弱信任), 自动过滤随机名bot */
    private void seedFromUsercache() {
        try {
            Path serverRoot = getDataFolder().getParentFile().getParentFile().toPath();
            Path cache = serverRoot.resolve("usercache.json");
            if (!Files.exists(cache)) {
                alert.info("[特征库] 未找到 usercache.json, 跳过播种(真人名单将随运行自动积累)");
                return;
            }
            JsonArray arr = JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)).getAsJsonArray();
            int seeded = 0, skipped = 0;
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String name = obj.has("name") ? obj.get("name").getAsString() : null;
                if (name == null || name.isEmpty()) continue;
                // 随机名bot也可能进过 usercache: 名字随机性>=50 的不播种
                if (scorer.randomness(name) >= 50) { skipped++; continue; }
                if (!library.isVerifiedPlayer(name)) {
                    library.addKnownPlayer(name, false, "usercache播种");
                    seeded++;
                }
            }
            library.saveIfDirtyAsync();
            getLogger().info("[特征库] usercache播种完成: 已登记玩家 +" + seeded + " (过滤随机名 " + skipped + " 个)");
        } catch (Exception e) {
            getLogger().warning("[特征库] usercache播种失败: " + e.getMessage());
        }
    }

    // ---------- 供组件调用的公共逻辑 ----------

    public boolean isWhitelisted(String name) {
        return configWrapper.whitelist.contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    public void banIp(String ip, int minutes, String reason) {
        if (ip == null || ip.isEmpty()) return;
        long until = System.currentTimeMillis() + minutes * 60000L;
        library.setBannedUntil(ip, until, reason);
        stats.d.ipBans++;
        stats.touch();
        alert.info("[IP封禁] " + ip + " 已临时封禁 " + minutes + " 分钟 | 原因: " + reason);

        // 联动网段封禁(封禁规避防御): 重启路由器换的动态IP仍落在同一运营商网段内
        if (configWrapper.banEvasionEnabled && configWrapper.banEvasionScope > 0) {
            int eff = library.banSubnet(ip, configWrapper.banEvasionScope, minutes, reason,
                    configWrapper.banEvasionEscalate, configWrapper.banEvasionEscalateAfter,
                    configWrapper.banEvasionMultiplier, configWrapper.banEvasionMaxMinutes);
            String key = LibraryStore.subnetKey(ip, configWrapper.banEvasionScope);
            if (eff > minutes) {
                alert.info("[网段封禁] " + key + " 第" + library.subnetBanCount(key)
                        + "次封禁, 时长加重至 " + eff + " 分钟");
            } else {
                alert.info("[网段封禁] " + key + " 同步封禁 " + minutes + " 分钟(换IP无法逃避)");
            }
        }
    }

    /** 真人封禁: 名字+IP+网段+客户端指纹 同时记录, 换IP/换名/换网络都逃不掉 */
    public void banHuman(String name, String reason) {
        if (name == null || name.isEmpty()) return;
        Player online = Bukkit.getPlayerExact(name);
        String ip = "", brand = "", locale = "";
        int protocol = -1;
        if (online != null) {
            ip = online.getAddress() == null ? "" : online.getAddress().getAddress().getHostAddress();
            try { brand = online.getClientBrandName() == null ? "" : online.getClientBrandName(); } catch (Throwable ignored) {}
            try { locale = online.getLocale() == null ? "" : online.getLocale(); } catch (Throwable ignored) {}
            protocol = JoinSentinel.viaProtocol(online.getUniqueId());
        } else {
            LibraryStore.Fingerprint f = library.fingerprintOf(name);
            if (f != null) { ip = f.lastIp; brand = f.brand; locale = f.locale; protocol = f.protocol; }
        }
        LibraryStore.HumanBan ban = library.banHuman(name, ip, brand, locale, protocol,
                configWrapper.banEvasionHumanMinutes, reason);
        stats.d.humanBans++;
        stats.touch();
        if (online != null && online.isOnline()) {
            final String kickMsg = configWrapper.humanBanMessage;
            FoliaBridge.runEntity(this, online, () -> {
                if (online.isOnline()) online.kickPlayer(kickMsg);
            });
        }
        String dur = configWrapper.banEvasionHumanMinutes <= 0 ? "永久" : configWrapper.banEvasionHumanMinutes + " 分钟";
        alert.info("[真人封禁] " + name + " (" + dur + ") | " + reason
                + (ban.subnet16.isEmpty() ? "" : " | 网段联动: " + ban.subnet16 + "(同网段未登记玩家一并限制)")
                + (brand.isEmpty() ? "" : " | 指纹: " + brand));
    }

    public boolean pardonHuman(String name) { return library.pardonHuman(name); }

    public Config.Mode mode() { return configWrapper.mode; }
    public Config config() { return configWrapper; }
    public LibraryStore library() { return library; }
    public StatsStore stats() { return stats; }
    public RiskScorer scorer() { return scorer; }
    public IpRiskTracker ipTracker() { return ipTracker; }
    public AlertBus alert() { return alert; }
    public PreLoginListener preLogin() { return preLogin; }
    public JoinSentinel joinSentinel() { return joinSentinel; }
    public GeoRegionManager geo() { return geo; }
}
