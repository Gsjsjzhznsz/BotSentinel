package cn.botsentinel.forge;

import cn.botsentinel.core.GeoRegionManager;
import cn.botsentinel.core.LibraryStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * /botsentinel 与 /atb 命令 (Brigadier, 权限等级 >= 2)。
 */
public final class ModCommands {

    private ModCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("botsentinel").requires(s -> s.hasPermission(2));
        buildTree(root);
        dispatcher.register(root);
        var alias = Commands.literal("atb").requires(s -> s.hasPermission(2));
        buildTree(alias);
        dispatcher.register(alias);
    }

    private static void buildTree(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.executes(ModCommands::help);

        root.then(Commands.literal("help").executes(ModCommands::help));
        root.then(Commands.literal("stats").executes(ModCommands::stats));
        root.then(Commands.literal("library").executes(ModCommands::library));
        root.then(Commands.literal("undo")
                .executes(ctx -> undo(ctx, 1))
                .then(Commands.argument("N", IntegerArgumentType.integer(1, 20))
                        .executes(ctx -> undo(ctx, IntegerArgumentType.getInteger(ctx, "N")))));
        root.then(Commands.literal("history")
                .executes(ctx -> history(ctx, 10))
                .then(Commands.argument("N", IntegerArgumentType.integer(1, 60))
                        .executes(ctx -> history(ctx, IntegerArgumentType.getInteger(ctx, "N")))));

        root.then(Commands.literal("ban")
                .then(Commands.argument("玩家名", StringArgumentType.word())
                        .executes(ctx -> ban(ctx, StringArgumentType.getString(ctx, "玩家名"), "管理员封禁"))
                        .then(Commands.argument("原因", StringArgumentType.greedyString())
                                .executes(ctx -> ban(ctx, StringArgumentType.getString(ctx, "玩家名"),
                                        StringArgumentType.getString(ctx, "原因"))))));
        root.then(Commands.literal("pardon")
                .then(Commands.argument("玩家名", StringArgumentType.word())
                        .executes(ModCommands::pardon)));
        root.then(Commands.literal("banip")
                .then(Commands.argument("IP|CIDR", StringArgumentType.word())
                        .executes(ctx -> banIp(ctx, StringArgumentType.getString(ctx, "IP|CIDR"), 0))
                        .then(Commands.argument("分钟", IntegerArgumentType.integer(1, 43200))
                                .executes(ctx -> banIp(ctx, StringArgumentType.getString(ctx, "IP|CIDR"),
                                        IntegerArgumentType.getInteger(ctx, "分钟"))))));
        root.then(Commands.literal("unbanip")
                .then(Commands.argument("IP|CIDR", StringArgumentType.word())
                        .executes(ModCommands::unbanIp)));
        root.then(Commands.literal("trust")
                .then(Commands.argument("玩家名", StringArgumentType.word())
                        .executes(ModCommands::trust)));
        root.then(Commands.literal("unlearn")
                .then(Commands.literal("name").then(Commands.argument("值", StringArgumentType.word()).executes(ctx -> unlearn(ctx, "name"))))
                .then(Commands.literal("shape").then(Commands.argument("值", StringArgumentType.word()).executes(ctx -> unlearn(ctx, "shape"))))
                .then(Commands.literal("sig").then(Commands.argument("值", StringArgumentType.greedyString()).executes(ctx -> unlearn(ctx, "sig"))))
                .then(Commands.literal("ip").then(Commands.argument("值", StringArgumentType.word()).executes(ctx -> unlearn(ctx, "ip"))))
                .then(Commands.literal("prefix").then(Commands.argument("值", StringArgumentType.word()).executes(ctx -> unlearn(ctx, "prefix"))))
                .then(Commands.literal("trust").then(Commands.argument("值", StringArgumentType.word()).executes(ctx -> unlearn(ctx, "trust")))));
        root.then(Commands.literal("learn")
                .then(Commands.argument("玩家名|IP", StringArgumentType.word())
                        .executes(ctx -> learn(ctx, StringArgumentType.getString(ctx, "玩家名|IP"), "手动确认"))));
        root.then(Commands.literal("learnmsg")
                .then(Commands.argument("广告原文", StringArgumentType.greedyString())
                        .executes(ModCommands::learnmsg)));

        root.then(Commands.literal("geo")
                .executes(ModCommands::geoStatus)
                .then(Commands.literal("on").executes(ctx -> geoToggle(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> geoToggle(ctx, false)))
                .then(Commands.literal("status").executes(ModCommands::geoStatus))
                .then(Commands.literal("test").then(Commands.argument("IP", StringArgumentType.word())
                        .executes(ModCommands::geoTest))));
        root.then(Commands.literal("lookup")
                .then(Commands.argument("IP", StringArgumentType.word()).executes(ModCommands::geoTest)));
        root.then(Commands.literal("reload").executes(ModCommands::reload));
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack s = ctx.getSource();
        s.sendSuccess(() -> Component.literal("§b§lBotSentinel v2.2 §7(Forge 服务端 mod)"), false);
        s.sendSuccess(() -> Component.literal("§8[§bBS§8] §7/botsentinel stats|library|history|undo [N]"), false);
        s.sendSuccess(() -> Component.literal("§8[§bBS§8] §7/botsentinel ban <名> [原因] | pardon <名> | banip <IP|CIDR> [分钟] | unbanip"), false);
        s.sendSuccess(() -> Component.literal("§8[§bBS§8] §7/botsentinel learn <名|IP> | learnmsg <原文> | trust <名> | unlearn <类型> <值>"), false);
        s.sendSuccess(() -> Component.literal("§8[§bBS§8] §7/botsentinel geo on|off|status|test <IP> | lookup <IP> | reload"), false);
        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        SentinelState st = SentinelState.INSTANCE;
        ctx.getSource().sendSuccess(() -> Component.literal("§6===== BotSentinel v2.2 (Forge) ====="), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7拦截进服: " + st.stats.d.blockedPreLogin
                + " (地区" + st.stats.d.geoBlocked + " 逃避" + st.stats.d.evasionBlocked
                + " 处置" + st.stats.d.disposedBots + ") | 评分: " + st.stats.d.totalScored), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7特征库: 假人名" + st.library.botNameCount()
                + " 形态" + st.library.shapeCount() + " 签名" + st.library.signatureCount()
                + " 风险IP" + st.library.botIpCount() + " 网段" + st.library.prefixCount()), false);
        ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7封禁: IP临时" + st.library.activeBanCount()
                + " 网段" + st.library.activeSubnetBanCount() + " 真人" + st.library.humanBanCount() + "人"
                + " | 真人名单: " + st.library.verifiedPlayerCount()), false);
        return 1;
    }

    private static int library(CommandContext<CommandSourceStack> ctx) {
        for (String line : SentinelState.INSTANCE.library.topSummary(5)) {
            String l = line;
            ctx.getSource().sendSuccess(() -> Component.literal("§7" + l), false);
        }
        return 1;
    }

    private static int undo(CommandContext<CommandSourceStack> ctx, int n) {
        int done = 0;
        LibraryStore.LearnOp last = null;
        for (int i = 0; i < n; i++) {
            LibraryStore.LearnOp op = SentinelState.INSTANCE.library.undoLast();
            if (op == null) break;
            last = op;
            done++;
        }
        String text = done == 0 ? "没有可撤销的记录" : "已撤销 " + done + " 条" + (last != null ? "(最后: " + last.kindText() + " " + last.key + ")" : "");
        ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7" + text), false);
        return 1;
    }

    private static int history(CommandContext<CommandSourceStack> ctx, int n) {
        List<LibraryStore.LearnOp> ops = SentinelState.INSTANCE.library.recentOps(n);
        ctx.getSource().sendSuccess(() -> Component.literal("§6===== 特征库最近改动(" + ops.size() + ") ====="), false);
        for (LibraryStore.LearnOp op : ops) {
            String line = "§7" + (op.removal ? "[移除]" : "[添加]") + " §f" + op.kindText() + " §e" + op.key;
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int ban(CommandContext<CommandSourceStack> ctx, String name, String reason) {
        SentinelState st = SentinelState.INSTANCE;
        if (name.matches("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")) {
            ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §c检测到IP, 封IP请用 /botsentinel banip"), false);
            return 0;
        }
        String ip = BotSentinelMod.ipOfOnline(name);
        st.banHuman(name, ip, st.config.humanBanMinutes, reason);
        ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7已封禁真人 §e" + name), false);
        BotSentinelMod.kickOnline(name, st.config.humanBanMessage);
        return 1;
    }

    private static int pardon(CommandContext<CommandSourceStack> ctx) {
        boolean ok = SentinelState.INSTANCE.library.pardonHuman(StringArgumentType.getString(ctx, "玩家名"));
        ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7" + (ok ? "已解封真人" : "未找到真人封禁记录")), false);
        return ok ? 1 : 0;
    }

    private static int banIp(CommandContext<CommandSourceStack> ctx, String target, int minutes) {
        SentinelState st = SentinelState.INSTANCE;
        int m = minutes > 0 ? minutes : st.config.ipBanMinutes;
        if (target.contains("/")) {
            String[] parts = target.split("/");
            int scope = Integer.parseInt(parts[1]);
            int eff = st.library.banSubnet(parts[0], scope, m, "手动网段封禁",
                    st.config.banEvasionEscalate, st.config.banEvasionEscalateAfter,
                    st.config.banEvasionEscalateMultiplier, st.config.banEvasionMaxMinutes);
            String key = LibraryStore.subnetKey(parts[0], scope);
            ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7已封禁网段 §e" + key + " §7" + eff + " 分钟"), false);
        } else {
            st.banIp(target, m, "手动封禁");
            ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7已封禁 §e" + target + " §7" + m + " 分钟(含联动网段)"), false);
        }
        return 1;
    }

    private static int unbanIp(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "IP|CIDR");
        if (target.contains("/")) {
            String[] parts = target.split("/");
            String key = LibraryStore.subnetKey(parts[0], Integer.parseInt(parts[1]));
            SentinelState.INSTANCE.library.clearSubnetBan(key);
            ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7已解除网段封禁: " + key), false);
        } else {
            SentinelState.INSTANCE.library.clearBan(target);
            ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7已解除封禁: " + target), false);
        }
        return 1;
    }

    private static int trust(CommandContext<CommandSourceStack> ctx) {
        SentinelState.INSTANCE.library.addTrustedManual(StringArgumentType.getString(ctx, "玩家名"), "管理员手动信任");
        ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7已加入已登记真人名单"), false);
        return 1;
    }

    private static int unlearn(CommandContext<CommandSourceStack> ctx, String type) {
        String value = StringArgumentType.getString(ctx, "值");
        LibraryStore lib = SentinelState.INSTANCE.library;
        boolean ok = switch (type) {
            case "name" -> lib.removeBotName(value);
            case "shape" -> lib.removeShape(value);
            case "sig" -> lib.removeSignature(value);
            case "ip" -> lib.removeBotIp(value);
            case "prefix" -> lib.removeIpPrefix(value);
            case "trust" -> lib.removeTrusted(value);
            default -> false;
        };
        String text = ok ? "已移除 " + type + ": " + value : "特征库中没有这一条: " + value;
        ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7" + text), false);
        return ok ? 1 : 0;
    }

    private static int learn(CommandContext<CommandSourceStack> ctx, String target, String reason) {
        SentinelState st = SentinelState.INSTANCE;
        if (target.matches("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")) {
            st.library.recordBotIpManual(target, 0.8, reason);
            if (st.config.prefixLearn) st.library.recordIpPrefixManual(target, 0.15, reason);
            ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7已学习风险IP: " + target), false);
        } else {
            st.library.recordBotNameManual(target, 0.6, reason);
            st.library.recordShapeManual(target, 0.15, reason);
            ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7已学习假人名: " + target), false);
        }
        return 1;
    }

    private static int learnmsg(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "广告原文");
        SentinelState.INSTANCE.library.addSignatureManual(raw);
        ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7已学习签名: " + LibraryStore.normalizeText(raw)), false);
        return 1;
    }

    private static int geoStatus(CommandContext<CommandSourceStack> ctx) {
        SentinelState st = SentinelState.INSTANCE;
        GeoRegionManager g = st.geo;
        ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7地区拦截: " + (st.config.geoEnabled ? "§a开" : "§c关")
                + " | 库: " + (g.isReady() ? "就绪 " + (g.dbSize() / 1024 / 1024) + "MB" : "未就绪")
                + " | 库龄: " + (g.dbAgeDays() < 0 ? "无" : g.dbAgeDays() + "天")
                + " | 拦截量: " + st.stats.d.geoBlocked), false);
        return 1;
    }

    private static int geoToggle(CommandContext<CommandSourceStack> ctx, boolean on) {
        SentinelState st = SentinelState.INSTANCE;
        st.config.geoEnabled = on;
        st.config.save();
        st.geo.configure(on, st.config.geoMainlandOnly, st.config.geoBlockedRegions,
                st.config.geoAllowedRegions, st.config.geoAllowIps, st.config.geoUpdateDays);
        if (on) st.geo.initAsync();
        ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7地区拦截已 " + (on ? "§a开启(仅放行中国大陆)" : "§c关闭")), false);
        return 1;
    }

    private static int geoTest(CommandContext<CommandSourceStack> ctx) {
        String ip = StringArgumentType.getString(ctx, "IP");
        GeoRegionManager g = SentinelState.INSTANCE.geo;
        if (!g.isReady()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §c归属地库未就绪"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7" + g.lookup(ip)), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        SentinelState.INSTANCE.reload();
        ctx.getSource().sendSuccess(() -> Component.literal("§8[§bBS§8] §7配置已重载"), false);
        return 1;
    }
}
