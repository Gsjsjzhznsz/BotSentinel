package cn.botsentinel;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置迁移器 (v2.3): 让旧版本配置无损升级, 用户以后更新插件永不再手改配置。
 *
 * 原则:
 *   - 用户改过的值 100% 保留
 *   - 新版本新增的键自动补上(带最新注释)
 *   - 迁移前自动备份 config.yml.bak-<旧版本>-<时间戳>
 *   - 注释始终刷新为当前版本说明(文档永不过期)
 *
 * 识别旧版本: config-version 缺失时按特征推断
 *   v1 = 2.0(无 ban-evasion / geo)  v2 = 2.1(有 ban-evasion)
 *   v3 = 2.2(有 geo)               v4 = 2.3(当前)
 */
public final class ConfigMigrator {

    public static final int CURRENT_VERSION = 4;

    private ConfigMigrator() {}

    /** 迁移结果描述(供日志), null=无需迁移 */
    public static String migrateIfNeeded(File configFile, java.util.function.Supplier<java.io.InputStream> bundledConfig) {
        try {
            if (!configFile.exists()) return null; // 全新安装, saveDefaultConfig 负责

            String oldText = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            YamlConfiguration old = YamlConfiguration.loadConfiguration(configFile);
            int oldVersion = old.getInt("config-version", guessVersion(old));

            // 加载内置最新模板(带完整注释)
            byte[] templateBytes;
            try (var in = bundledConfig.get()) {
                if (in == null) return null;
                templateBytes = in.readAllBytes();
            }
            YamlConfiguration latest = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(new ByteArrayInputStream(templateBytes), StandardCharsets.UTF_8));
            int newVersion = latest.getInt("config-version", CURRENT_VERSION);

            if (oldVersion >= newVersion) {
                // 同版本: 仍补齐缺失键(用户手动删过的), 但不备份不提示
                int missing = fillMissing(old, latest);
                if (missing == 0) return null;
                latest.save(configFile);
                return null;
            }

            // ---- 需要迁移: 备份 -> 保留用户值 -> 补新键 ----
            String backupName = "config.yml.bak-v" + oldVersion + "-"
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmmss"));
            Path backup = configFile.toPath().resolveSibling(backupName);
            Files.copy(configFile.toPath(), backup, StandardCopyOption.REPLACE_EXISTING);

            // 1) 收集用户现有值(扁平化)
            Map<String, Object> userValues = new LinkedHashMap<>();
            flatten(old, "", userValues);

            // 2) 在最新模板上回填用户值(模板注释全保留)
            int kept = 0, added = 0;
            for (Map.Entry<String, Object> e : userValues.entrySet()) {
                if (e.getKey().equals("config-version")) continue;
                if (latest.contains(e.getKey())) {
                    latest.set(e.getKey(), e.getValue());
                    kept++;
                }
            }
            for (String key : latest.getKeys(true)) {
                if (!old.contains(key)) added++;
            }
            latest.save(configFile);

            return "配置已从 v" + oldVersion + " 自动升级到 v" + newVersion
                    + " (保留你的设置 " + kept + " 项, 新增 " + added + " 项, 备份: " + backupName + ")";
        } catch (Exception e) {
            return "配置迁移失败(沿用现有配置): " + e.getMessage();
        }
    }

    /** 同版本补齐被手动删除的键 */
    private static int fillMissing(YamlConfiguration old, YamlConfiguration latest) {
        int missing = 0;
        for (String key : latest.getKeys(true)) {
            if (!old.contains(key)) {
                old.set(key, latest.get(key));
                missing++;
            }
        }
        return missing;
    }

    /** 推断旧配置版本 */
    private static int guessVersion(YamlConfiguration y) {
        if (y.contains("geo")) return 3;        // 2.2
        if (y.contains("ban-evasion")) return 2; // 2.1
        return 1;                                // 2.0
    }

    /** 扁平化: a.b.c -> value (列表/标量保留原样) */
    private static void flatten(ConfigurationSection sec, String prefix, Map<String, Object> out) {
        for (String key : sec.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object v = sec.get(key);
            if (v instanceof ConfigurationSection child) flatten(child, path, out);
            else out.put(path, v);
        }
    }
}
