package cn.botsentinel;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /atb 管理命令 (权限 botsentinel.admin, 默认OP) —— v2.2
 *
 *   状态: stats / mode / check / lookup / geo
 *   真人: ban / pardon / banlist
 *   IP:   banip / unbanip / evasion
 *   特征库: learn / learnmsg / trust / unlearn / undo / history / library
 *   其他: reload / help
 */
public class AtbCommand implements CommandExecutor, TabCompleter {

    private final BotSentinelPlugin plugin;

    public AtbCommand(BotSentinelPlugin plugin) {
        this.plugin = plugin;
    }

    private static final String P = "§8[§bBS§8] §7";

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "stats": case "status": stats(sender); return true;
            case "mode": mode(sender, args); return true;
            case "learn": learn(sender, args); return true;
            case "learnmsg": learnmsg(sender, args); return true;
            case "trust": trust(sender, args); return true;
            case "ban": banHuman(sender, args); return true;
            case "pardon": pardon(sender, args); return true;
            case "banlist": banlist(sender); return true;
            case "banip": banip(sender, args); return true;
            case "unbanip": unbanip(sender, args); return true;
            case "evasion": case "bans": evasion(sender); return true;
            case "geo": geo(sender, args); return true;
            case "lookup": lookup(sender, args); return true;
            case "engine": engine(sender, args); return true;
            case "unlearn": unlearn(sender, args); return true;
            case "undo": undo(sender, args); return true;
            case "history": history(sender, args); return true;
            case "check": check(sender, args); return true;
            case "library": library(sender); return true;
            case "reload": plugin.config().reload();
                plugin.library().configure(plugin.config().decayDays, plugin.config().removeBelow);
                plugin.reconfigureGeo();
                sender.sendMessage(P + "配置已重载 §8| §7模式: " + modeText()
                        + " §8| §7地区拦截: " + (plugin.config().geoEnabled ? "§a开§7" : "§c关§7"));
                return true;
            case "help": help(sender); return true;
            default:
                sender.sendMessage(P + "未知子命令 §8| §7输入 §e/atb help§7 查看用法");
                return true;
        }
    }

    // ---------- 帮助 ----------
    private void help(CommandSender s) {
        s.sendMessage("§8§m                                                  ");
        s.sendMessage(" §b§lBotSentinel v2.3 §8| §7无感风控 · 反机器人 · 地区拦截 · AI评分");
        s.sendMessage("§8§m                                                  ");
        s.sendMessage(P + "§e—— 状态 ——");
        s.sendMessage(P + "/atb stats §f- 风控总览 §8| §f/atb check <名> §f- 评分细节");
        s.sendMessage(P + "/atb lookup <IP> §f- 查IP归属地 §8| §f/atb mode <block|observe>");
        s.sendMessage(P + "§e—— AI 评分引擎 (v2.3) ——");
        s.sendMessage(P + "/atb engine <名字> §f- 各策略评分明细(规则/语言模型/熵/回归)");
        s.sendMessage(P + "/atb engine info §f- 引擎状态/权重/训练量 §8| §f重载改 config.yml [scoring]");
        s.sendMessage(P + "§e—— 地区拦截 ——");
        s.sendMessage(P + "/atb geo on|off §f- 一键开关(仅放行大陆, 配置可细化)");
        s.sendMessage(P + "/atb geo status §f- 库状态/规则/拦截量 §8| §f/atb geo test <IP>");
        s.sendMessage(P + "/atb geo download §f- 手动重新下载本地归属地库");
        s.sendMessage(P + "§e—— 封真人 (换IP/换名/换网络都进不来) ——");
        s.sendMessage(P + "/atb ban <玩家名> [原因] §f- 四重追踪封禁");
        s.sendMessage(P + "/atb pardon <玩家名> §f- 解封 §8| §f/atb banlist §f- 封禁与逃避记录");
        s.sendMessage(P + "§e—— 封IP / 网段 ——");
        s.sendMessage(P + "/atb banip <IP|CIDR> [分钟] §f- 自动联动" + scopeText() + "网段");
        s.sendMessage(P + "/atb unbanip <IP|CIDR> §f- 解封 §8| §f/atb evasion §f- 网段封禁记录");
        s.sendMessage(P + "§e—— 特征库 (加错了随时撤销) ——");
        s.sendMessage(P + "/atb learn <名|IP> [原因] §8| §f/atb learnmsg <广告原文>");
        s.sendMessage(P + "/atb trust <玩家名> §f- 加入真人名单");
        s.sendMessage(P + "/atb unlearn <name|shape|sig|ip|prefix|trust|cmd> <值> §f- 移除特征");
        s.sendMessage(P + "/atb undo [条数] §f- 撤销最近添加 §8| §f/atb history §f- 最近改动");
        s.sendMessage(P + "/atb library §f- 库概览 §8| §f/atb reload §f- 重载配置");
        s.sendMessage("§8§m                                                  ");
    }

    // ---------- 统计 ----------
    private void stats(CommandSender s) {
        StatsStore st = plugin.stats();
        LibraryStore lib = plugin.library();
        s.sendMessage("§6===== BotSentinel v2.3 风控总览 =====");
        s.sendMessage("§7运行: §f" + st.uptimeText() + " §8| §7模式: §f" + modeText()
                + " §8| §7内核: §f" + FoliaBridge.kernelName());
        s.sendMessage("§7拦截进服: §f" + st.d.blockedPreLogin
                + " §8(§7地区" + st.d.geoBlocked + " §7逃避" + st.d.evasionBlocked
                + " §7处置" + st.d.disposedBots + "§8)"
                + " §8| §7累计评分: §f" + st.d.totalScored);
        s.sendMessage("§7撤回广告: §f" + st.d.recalledAds
                + " §8| §7闪进闪退: §f" + st.d.flashQuitBlocked
                + " §8| §7警报: §f" + st.d.warnings);
        s.sendMessage("§7特征库: §f假人名" + lib.botNameCount()
                + " 形态" + lib.shapeCount()
                + " 签名" + lib.signatureCount()
                + " 风险IP" + lib.botIpCount() + "(封" + lib.activeBanCount() + ")"
                + " 网段" + lib.prefixCount());
        s.sendMessage("§7封禁: §fIP临时" + lib.activeBanCount()
                + " 网段" + lib.activeSubnetBanCount()
                + " 真人" + lib.humanBanCount() + "人(累计" + st.d.humanBans + "次)");
        s.sendMessage("§7真人名单: §f" + lib.knownPlayerCount() + "(已验证" + lib.verifiedPlayerCount() + ")"
                + " §8| §7拦截反哺: §f" + st.d.learnedFromBlocks + "次");
        if (plugin.config().geoEnabled) {
            GeoRegionManager g = plugin.geo();
            s.sendMessage("§7地区拦截: " + (g.isReady() ? "§a库就绪" : g.isEnabled() ? "§e下载中" : "§c未启用")
                    + " §8| §7库龄: §f" + (g.dbAgeDays() < 0 ? "无" : g.dbAgeDays() + "天")
                    + " §8| §7拦截量: §f" + st.d.geoBlocked);
        }
    }

    // ---------- AI 评分引擎 (v2.3) ----------
    private void engine(CommandSender s, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("info")) {
            var m = plugin.scoreEngine().statsMap();
            s.sendMessage("§6===== AI 评分引擎 =====");
            s.sendMessage(P + "引擎: §e" + m.get("engine") + " §8| §7权重: §f" + m.get("weights"));
            s.sendMessage(P + "Markov语料: §f" + m.get("markovCorpus") + " §8| §7回归训练样本: §f" + m.get("logisticSamples"));
            s.sendMessage(P + "策略: §fheuristic§7(规则) §fmarkov§7(语言模型) §fentropy§7(熵) §flogistic§7(在线回归)");
            s.sendMessage(P + "§8明细: /atb engine <名字> §8| §7切引擎/权重: config.yml [scoring] 段 + /atb reload");
            return;
        }
        String name = args[1];
        ScoreEngine eng = plugin.scoreEngine();
        ScoreEngine.NameFeatures f = eng.features(name);
        int[] b = eng.breakdown(name, f); // [规则,语言模型,熵,回归,模型加分,保险丝,总分]
        s.sendMessage(P + "名字: §e" + name + " §8| §7形态: §f" + LibraryStore.shapeOf(name));
        s.sendMessage(P + "策略分: 规则§f" + b[0] + " §8| §7语言模型§f" + b[1]
                + " §8| §7熵§f" + b[2] + " §8| §7回归§f" + b[3]);
        s.sendMessage(P + "保险丝: §e" + b[5] + " §8(§7决定能否参与拦截§8) §8| §7模型加分: §f+" + b[4]
                + " §8| §7→§b总分: §e" + b[6]);
        s.sendMessage(P + "特征: 熵§f" + String.format(Locale.ROOT, "%.2f", f.entropy)
                + " §7元音比§f" + String.format(Locale.ROOT, "%.2f", f.vowelRatio)
                + " §7大写岛§f" + f.upperIslands
                + " §7最长辅音§f" + f.maxConsonantRun
                + " §7bigramLogP§f" + String.format(Locale.ROOT, "%.2f", f.markovLogP)
                + (f.hasCommonWord ? " §a含常见词" : ""));
        s.sendMessage(P + "§8保险丝: 最终随机性≥" + plugin.config().minNameRandomness
                + "才会参与拦截判定, 拼音/数字名永不误拦");
    }

    // ---------- 地区拦截 ----------
    private void geo(CommandSender s, String[] args) {
        GeoRegionManager g = plugin.geo();
        if (args.length < 2) {
            s.sendMessage(P + "用法: /atb geo <on|off|status|test> [IP]");
            s.sendMessage(P + "当前: " + (plugin.config().geoEnabled ? "§a已开启" : "§c已关闭")
                    + " §8| §7模式: " + (plugin.config().geoMainlandOnly ? "仅放行中国大陆" : "自定义规则"));
            return;
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "on" -> {
                plugin.config().setGeoEnabled(true);
                FoliaBridge.runAsync(plugin, () -> plugin.geo().initAsync());
                s.sendMessage(P + "地区拦截已 §a开启 §7(默认仅放行中国大陆, 内网IP自动豁免)");
                if (!plugin.geo().isReady()) {
                    s.sendMessage(P + "§e本地库未就绪, 正在后台准备(首次约11MB, 详见控制台)...");
                }
                s.sendMessage(P + "§8豁免: 已登记真人/白名单/geo.allow-ips; 细则在 config.yml [geo] 段");
            }
            case "off" -> {
                plugin.config().setGeoEnabled(false);
                s.sendMessage(P + "地区拦截已 §c关闭");
            }
            case "download" -> {
                boolean started = plugin.geo().downloadNow();
                if (started) s.sendMessage(P + "已开始重新下载归属地库(多镜像自动切换), 结果见控制台日志");
                else s.sendMessage(P + "§e已在下载中, 请稍候(结果见控制台日志)");
            }
            case "status" -> {
                s.sendMessage("§6===== 地区拦截状态 =====");
                s.sendMessage("§7开关: " + (plugin.config().geoEnabled ? "§a开" : "§c关")
                        + " §8| §7模式: " + (plugin.config().geoMainlandOnly ? "仅放行大陆" : "黑/白名单"));
                String st2 = switch (g.state()) {
                    case NOT_DOWNLOADED -> "§7未下载";
                    case DOWNLOADING -> "§e下载中...";
                    case READY -> "§a就绪";
                    case FAILED -> "§c失败(自动重试中)";
                };
                s.sendMessage("§7本地库: " + st2
                        + (g.isReady() ? " §f" + (g.dbSize() / 1024 / 1024) + "MB" : ""));
                if (g.state() == GeoRegionManager.State.FAILED && !g.lastError().isEmpty()) {
                    s.sendMessage("§7失败原因: §c" + g.lastError());
                }
                s.sendMessage("§7库龄: §f" + (g.dbAgeDays() < 0 ? "无" : g.dbAgeDays() + "天")
                        + " §8(§f每" + plugin.config().geoUpdateDays + "天自动更新§8)");
                s.sendMessage("§7累计拦截: §f" + plugin.stats().d.geoBlocked
                        + " §8| §7手动重下: §f/atb geo download");
                if (!plugin.config().geoBlockedRegions.isEmpty())
                    s.sendMessage("§7黑名单关键词: §f" + String.join(", ", plugin.config().geoBlockedRegions));
                if (!plugin.config().geoAllowedRegions.isEmpty())
                    s.sendMessage("§7白名单关键词: §f" + String.join(", ", plugin.config().geoAllowedRegions));
                if (!plugin.config().geoAllowIps.isEmpty())
                    s.sendMessage("§7IP豁免: §f" + String.join(", ", plugin.config().geoAllowIps));
            }
            case "test" -> {
                if (args.length < 3) { s.sendMessage(P + "§c用法: /atb geo test <IP>"); return; }
                if (!g.isReady()) { s.sendMessage(P + "§c本地库未就绪, 稍后再试(首次下载约11MB)"); return; }
                GeoRegionManager.Verdict v = g.check(args[2]);
                s.sendMessage(P + "IP: §e" + args[2]);
                s.sendMessage(P + "归属: §f" + (v.raw.isEmpty() ? "未知" : v.raw));
                s.sendMessage(P + "归类: §e" + v.region + " §8| §7判定: "
                        + (v.allow ? "§a放行" : "§c拦截") + " §8(" + v.rule + ")");
            }
            default -> s.sendMessage(P + "用法: /atb geo <on|off|status|test> [IP]");
        }
    }

    private void lookup(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(P + "§c用法: /atb lookup <IP>");
            return;
        }
        if (!plugin.geo().isReady()) {
            s.sendMessage(P + "§c归属地库未就绪(首次使用会自动下载, 详见控制台日志)");
            return;
        }
        s.sendMessage(P + plugin.geo().lookup(args[1]));
    }

    // ---------- 真人封禁 ----------
    private void banHuman(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(P + "§c用法: /atb ban <玩家名> [原因]");
            s.sendMessage(P + "§7四重追踪: 账号名+网段连坐+客户端指纹+逃避计数");
            s.sendMessage(P + "§8重启路由器/换用户名/换热点都进不来, 解封: /atb pardon");
            return;
        }
        String target = args[1];
        if (target.matches("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")) {
            s.sendMessage(P + "§c检测到IP, 封IP请用: /atb banip <IP> [分钟]");
            return;
        }
        String reason = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "管理员封禁";
        plugin.banHuman(target, reason);
        s.sendMessage(P + "已封禁真人 §e" + target + " §8| §7/atb banlist 查看追踪详情");
    }

    private void pardon(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(P + "§c用法: /atb pardon <玩家名>");
            return;
        }
        if (plugin.pardonHuman(args[1])) {
            s.sendMessage(P + "已解封真人: §e" + args[1] + " §8(§7网段联动与指纹追踪随之失效§8)");
        } else {
            s.sendMessage(P + "§c未找到该玩家的真人封禁记录(封IP的请用 /atb unbanip)");
        }
    }

    private void banlist(CommandSender s) {
        var bans = plugin.library().activeHumanBans();
        s.sendMessage("§6===== 真人封禁 (" + bans.size() + "人) =====");
        if (bans.isEmpty()) {
            s.sendMessage(P + "§7(空) 用法: /atb ban <玩家名> [原因]");
            return;
        }
        for (var b : bans) {
            String remain = b.until <= 0 ? "§c永久"
                    : "剩" + Math.max(0, (b.until - System.currentTimeMillis()) / 60000) + "分钟";
            s.sendMessage(P + "§e" + b.name + " §8| §7" + remain
                    + " §8| §7逃避§c" + b.evasionHits + "次"
                    + " §8| §7网段 " + (b.subnet16.isEmpty() ? "未知" : b.subnet16)
                    + " §8| " + (b.brand.isEmpty() ? "§7无指纹" : "§f指纹:" + b.brand)
                    + " §8| " + (b.reason.isEmpty() ? "§7无备注" : "§f" + b.reason));
        }
    }

    // ---------- 模式 ----------
    private void mode(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(P + "当前模式: §e" + modeText() + " §8| §7切换: /atb mode <block|observe>");
            return;
        }
        String m = args[1].toLowerCase(Locale.ROOT);
        if (m.equals("block") || m.equals("aggressive")) {
            plugin.config().saveMode(Config.Mode.BLOCK);
            s.sendMessage(P + "已切换为 §c拦截模式§7(进服前直接拒绝)");
        } else if (m.equals("observe")) {
            plugin.config().saveMode(Config.Mode.OBSERVE);
            s.sendMessage(P + "已切换为 §e观察模式§7(只记录不拦截)");
        } else {
            s.sendMessage(P + "用法: /atb mode <block|observe>");
        }
    }

    // ---------- 特征库 ----------
    private void learn(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(P + "§c用法: /atb learn <玩家名|IP> [原因]");
            s.sendMessage(P + "§7示例: /atb learn WnyrLuSkBHhWO 手动确认 §8/ §7/atb learn 14.150.8.168 手动确认");
            s.sendMessage(P + "§8加错了? /atb undo 撤销 或 /atb unlearn 移除");
            return;
        }
        String target = args[1];
        String reason = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "手动确认";
        String ipRegex = "^([0-9]{1,3}\\.){3}[0-9]{1,3}$";

        if (target.matches(ipRegex)) {
            plugin.library().recordBotIpManual(target, 0.8, reason);
            if (plugin.config().prefixLearn) plugin.library().recordIpPrefixManual(target, 0.15, reason);
            s.sendMessage(P + "已学习风险IP: §e" + target + "§7, 同网段信誉同步提升");
            return;
        }

        Player online = Bukkit.getPlayerExact(target);
        if (online != null) {
            plugin.joinSentinel().confirmBot(online, "手动确认: " + reason);
            s.sendMessage(P + "已确认并处置在线玩家 §e" + online.getName() + "§7(学习+踢出+封IP)");
        } else {
            plugin.library().recordBotNameManual(target, 0.6, reason);
            plugin.library().recordShapeManual(target, 0.15, reason);
            s.sendMessage(P + "已学习假人名: §e" + target + " §8(§7该名字或同形态下次进服将被拦§8)");
        }
    }

    private void learnmsg(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(P + "§c用法: /atb learnmsg <广告原文...>");
            s.sendMessage(P + "§7示例: /atb learnmsg 欢迎来到mc点xemc点cn");
            return;
        }
        String raw = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        plugin.library().addSignatureManual(raw);
        var learned = plugin.library().learnAdSignature(raw);
        s.sendMessage(P + "已学习签名: §e" + LibraryStore.normalizeText(raw)
                + (learned.isEmpty() ? "" : " §8(§7自动抽取: " + String.join(", ", learned) + "§8)"));
        s.sendMessage(P + "§8加错了? /atb unlearn sig <签名> 或 /atb undo");
    }

    private void trust(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(P + "§c用法: /atb trust <玩家名>  §8(§7永不拦截§8)");
            return;
        }
        plugin.library().addTrustedManual(args[1], "管理员手动信任");
        plugin.scoreEngine().learnHuman(args[1]); // v2.3: 小模型负样本训练
        s.sendMessage(P + "已将 §e" + args[1] + " §7加入已登记真人名单");
    }

    private void unlearn(CommandSender s, String[] args) {
        if (args.length < 3) {
            s.sendMessage(P + "§c用法: /atb unlearn <类型> <值>");
            s.sendMessage(P + "§7类型: §ename§f 假人名 §8| §eshape§f 形态(填玩家名即可) §8| §esig§f 广告签名");
            s.sendMessage(P + "§8      §eip§f 风险IP §8| §eprefix§f 风险网段 §8| §etrust§f 取消真人信任");
            s.sendMessage(P + "§8      §ecmd§f 移除自适应学习的本服命令");
            s.sendMessage(P + "§7示例: /atb unlearn name Ciloat77422");
            s.sendMessage(P + "§8提示: /atb undo 可直接撤销最近一次添加");
            return;
        }
        String type = args[1].toLowerCase(Locale.ROOT);
        String value = args.length >= 3 ? args[2] : "";
        boolean ok;
        switch (type) {
            case "name", "botname" -> ok = plugin.library().removeBotName(value);
            case "shape" -> ok = plugin.library().removeShape(value);
            case "sig", "signature", "word" -> ok = plugin.library().removeSignature(value);
            case "ip" -> ok = plugin.library().removeBotIp(value);
            case "prefix" -> ok = plugin.library().removeIpPrefix(value);
            case "trust", "player" -> ok = plugin.library().removeTrusted(value);
            case "cmd", "command", "authcmd" -> ok = plugin.library().removeLearnedAuthCommand(value);
            default -> { s.sendMessage(P + "§c未知类型: " + type + " §8(name/shape/sig/ip/prefix/trust)"); return; }
        }
        if (ok) s.sendMessage(P + "已移除 §e" + type + " §7: §f" + value + " §8(§7可用 /atb undo 恢复§8)");
        else s.sendMessage(P + "§c特征库中没有这一条: " + value);
    }

    private void undo(CommandSender s, String[] args) {
        int n = 1;
        if (args.length >= 2) {
            try { n = Math.max(1, Math.min(20, Integer.parseInt(args[1]))); }
            catch (NumberFormatException e) { s.sendMessage(P + "§c条数应为数字: /atb undo [N]"); return; }
        }
        int done = 0;
        LibraryStore.LearnOp last = null;
        for (int i = 0; i < n; i++) {
            LibraryStore.LearnOp op = plugin.library().undoLast();
            if (op == null) break;
            last = op;
            done++;
        }
        if (done == 0) {
            s.sendMessage(P + "§7没有可撤销的记录(只有本插件运行期间的添加/移除才会入历史)");
        } else {
            s.sendMessage(P + "已撤销 §e" + done + " §7条(最后一条: "
                    + last.kindText() + " " + last.key + ")");
        }
    }

    private void history(CommandSender s, String[] args) {
        int n = 10;
        if (args.length >= 2) {
            try { n = Math.max(1, Math.min(60, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) {}
        }
        List<LibraryStore.LearnOp> ops = plugin.library().recentOps(n);
        s.sendMessage("§6===== 特征库最近改动 (" + ops.size() + "条, 新的在上) =====");
        if (ops.isEmpty()) {
            s.sendMessage(P + "§7(空) 本次运行期间还没有特征库改动");
            return;
        }
        for (LibraryStore.LearnOp op : ops) {
            String time = String.format(Locale.ROOT, "%1$tm-%1$td %1$tH:%1$tM", op.at);
            s.sendMessage(P + "§7" + time + " " + (op.removal ? "§c[移除]" : "§a[添加]")
                    + " §f" + op.kindText() + " §e" + op.key
                    + (op.source.isEmpty() ? "" : " §8(" + op.source + ")"));
        }
        s.sendMessage(P + "§8提示: /atb undo 撤销最近一条; /atb undo N 撤销最近N条");
    }

    // ---------- IP 封禁 ----------
    private void banip(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(P + "§c用法: /atb banip <IP|CIDR> [分钟]");
            s.sendMessage(P + "§7示例: /atb banip 14.24.176.62 120 §8(自动联动" + scopeText() + "网段)");
            s.sendMessage(P + "§7示例: /atb banip 14.24.0.0/16 120 §8(直接封整个网段)");
            return;
        }
        String target = args[1];
        int minutes = args.length >= 3 ? parseMinutes(args[2]) : plugin.config().ipBanMinutes;

        // CIDR 形式: 直接封网段(不联动, 因为目标已是网段)
        if (target.contains("/")) {
            String[] parts = target.split("/");
            int scope;
            try { scope = Integer.parseInt(parts[1]); } catch (NumberFormatException e) {
                s.sendMessage(P + "§cCIDR 格式错误, 示例: 14.24.0.0/16"); return;
            }
            int eff = plugin.library().banSubnet(parts[0], scope, minutes, "手动网段封禁",
                    plugin.config().banEvasionEscalate, plugin.config().banEvasionEscalateAfter,
                    plugin.config().banEvasionMultiplier, plugin.config().banEvasionMaxMinutes);
            s.sendMessage(P + "已封禁网段 §e" + LibraryStore.subnetKey(parts[0], scope)
                    + " §7" + eff + " 分钟§8(换IP无法逃避)");
            return;
        }

        // 普通IP: 封IP + 联动网段(带逐犯加重)
        plugin.banIp(target, minutes, "手动封禁");
        String scopeKey = LibraryStore.subnetKey(target, plugin.config().banEvasionScope);
        s.sendMessage(P + "已封禁 §e" + target + " §7" + minutes + " 分钟"
                + (scopeKey != null && plugin.config().banEvasionScope > 0
                        ? " §8| §7网段 §e" + scopeKey + " §7已联动" : ""));
    }

    private void evasion(CommandSender s) {
        s.sendMessage("§6===== 网段封禁 / 逃避拦截 =====");
        s.sendMessage("§7联动范围: §f" + scopeText()
                + " §8| §7逃避拦截累计: §f" + plugin.stats().d.evasionBlocked + "次");
        boolean any = false;
        for (String line : plugin.library().topSummary(10)) {
            if (line.startsWith("-- 生效中的网段封禁")) { any = true; }
            else if (any) s.sendMessage(P + line);
        }
        if (plugin.library().activeSubnetBanCount() == 0) {
            s.sendMessage(P + "§7(当前无生效中的网段封禁)");
        }
    }

    private String scopeText() {
        int sc = plugin.config().banEvasionScope;
        if (!plugin.config().banEvasionEnabled || sc == 0) return "关闭(仅封单IP)";
        return "/" + sc;
    }

    private void unbanip(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(P + "§c用法: /atb unbanip <IP|CIDR>");
            return;
        }
        String target = args[1];
        if (target.contains("/")) {
            String[] parts = target.split("/");
            try {
                int scope = Integer.parseInt(parts[1]);
                String key = LibraryStore.subnetKey(parts[0], scope);
                plugin.library().clearSubnetBan(key);
                s.sendMessage(P + "已解除网段封禁: §e" + key);
            } catch (NumberFormatException e) {
                s.sendMessage(P + "§cCIDR 格式错误, 示例: 14.24.0.0/16");
            }
            return;
        }
        plugin.library().clearBan(target);
        s.sendMessage(P + "已解除封禁: §e" + target + " §8(§7含联动网段§8)");
    }

    // ---------- 检查 ----------
    private void check(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(P + "§c用法: /atb check <玩家名>");
            return;
        }
        String name = args[1];
        int rand = plugin.scorer().randomness(name);
        double shape = plugin.library().shapeConfidence(name);
        s.sendMessage(P + "名字: §e" + name + " §8| §7形态: §f" + LibraryStore.shapeOf(name));
        s.sendMessage(P + "随机性: §e" + rand + " §8| §7形态库置信: §f" + String.format(Locale.ROOT, "%.2f", shape)
                + " §8| §7假人名库: " + (plugin.library().isKnownBotName(name) ? "§c命中" : "§a未命中"));
        s.sendMessage(P + "已登记真人: " + (plugin.library().isVerifiedPlayer(name) ? "§a是"
                : plugin.library().isSeededPlayer(name) ? "§e弱信任(usercache)" : "§7否"));

        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            String ip = online.getAddress() == null ? "" : online.getAddress().getAddress().getHostAddress();
            s.sendMessage(P + "在线: §a是 §8| §7IP: §f" + ip
                    + " §8| §7窗口新账号: §f" + plugin.ipTracker().newNameCount(ip)
                    + " §8| §7闪进闪退: §f" + plugin.ipTracker().flashQuitCount(ip));
            if (plugin.config().geoEnabled && plugin.geo().isReady()) {
                s.sendMessage(P + "归属地: §f" + plugin.geo().lookup(ip));
            }
            int session = plugin.preLogin().sessionScore(name);
            if (session >= 0) s.sendMessage(P + "会话分: §e" + session + (session >= 45 ? " §c(盯防中)" : ""));
        } else {
            s.sendMessage(P + "在线: §7否");
        }
    }

    private void library(CommandSender s) {
        s.sendMessage("§6===== BotSentinel 特征库概览 =====");
        for (String line : plugin.library().topSummary(5)) {
            s.sendMessage(P + line);
        }
        s.sendMessage(P + "§8修正: /atb unlearn <类型> <值> /atb undo /atb history");
    }

    private int parseMinutes(String v) {
        try { return Math.max(1, Integer.parseInt(v)); } catch (NumberFormatException e) { return plugin.config().ipBanMinutes; }
    }

    private String modeText() {
        return plugin.mode() == Config.Mode.BLOCK ? "拦截(block)" : "观察(observe)";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        String a0 = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return filter(Arrays.asList("stats", "mode", "geo", "lookup", "engine", "ban", "pardon", "banlist",
                    "banip", "unbanip", "evasion", "learn", "learnmsg", "trust", "unlearn", "undo",
                    "history", "check", "library", "reload", "help"), a0);
        }
        if (args.length == 2) {
            switch (a0) {
                case "mode": return filter(Arrays.asList("block", "observe"), args[1]);
                case "geo": return filter(Arrays.asList("on", "off", "status", "test", "download"), args[1]);
                case "engine": return filter(Arrays.asList("info"), args[1]);
                case "unlearn": return filter(Arrays.asList("name", "shape", "sig", "ip", "prefix", "trust", "cmd"), args[1]);
                case "learn", "trust", "check", "pardon":
                    return filter(onlineNames(), args[1]);
                case "undo", "history": return Arrays.asList("5", "10");
                case "ban": return new ArrayList<>();
                case "lookup": return new ArrayList<>();
            }
        }
        if (args.length == 3 && a0.equals("unlearn") && args[1].equalsIgnoreCase("trust")) {
            return filter(onlineNames(), args[2]);
        }
        return new ArrayList<>();
    }

    private List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    private static List<String> filter(List<String> in, String start) {
        String low = start.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : in) if (s.toLowerCase(Locale.ROOT).startsWith(low)) out.add(s);
        return out;
    }
}
