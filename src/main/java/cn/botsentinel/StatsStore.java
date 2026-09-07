package cn.botsentinel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统计数据(跨重启持久化)。
 *
 * v2.2 修复: 旧版用 gson.toJson(this) 把包含 Gson 自身(内含 ThreadLocal)的字段
 * 一并序列化, JDK17+ 强封装直接抛 InaccessibleObjectException, 导致每5分钟
 * 落盘报错一次。现改为只序列化纯数据的 Data DTO。
 */
public class StatsStore {

    // ---------- 持久化数据 ----------
    public static class Data {
        public long blockedPreLogin = 0;   // 拦截进服
        public long disposedBots = 0;      // 处置假人
        public long recalledAds = 0;       // 撤回广告消息
        public long warnings = 0;          // 警告级警报
        public long ipBans = 0;            // 累计临时封禁
        public long totalScored = 0;       // 累计评分次数
        public long learnedFromBlocks = 0; // 拦截反哺次数
        public long evasionBlocked = 0;    // 逃避封禁被拦(换IP+换名重进)
        public long humanBans = 0;         // 真人封禁累计
        public long geoBlocked = 0;        // 归属地拦截(v2.2)
        public long flashQuitBlocked = 0;  // 闪进闪退观察记录(v2.2)
        public long startedAt = System.currentTimeMillis();
    }

    public final Data d = new Data();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final transient Path file;
    private final transient AtomicBoolean dirty = new AtomicBoolean(false);

    public StatsStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (loaded != null) {
                    this.d.blockedPreLogin = nz(loaded.blockedPreLogin);
                    this.d.disposedBots = nz(loaded.disposedBots);
                    this.d.recalledAds = nz(loaded.recalledAds);
                    this.d.warnings = nz(loaded.warnings);
                    this.d.ipBans = nz(loaded.ipBans);
                    this.d.totalScored = nz(loaded.totalScored);
                    this.d.learnedFromBlocks = nz(loaded.learnedFromBlocks);
                    this.d.evasionBlocked = nz(loaded.evasionBlocked);
                    this.d.humanBans = nz(loaded.humanBans);
                    this.d.geoBlocked = nz(loaded.geoBlocked);
                    this.d.flashQuitBlocked = nz(loaded.flashQuitBlocked);
                    if (loaded.startedAt > 0) this.d.startedAt = loaded.startedAt;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static long nz(long v) { return v; }

    // 便捷访问(保持旧代码兼容)
    public long blockedPreLogin() { return d.blockedPreLogin; }
    public void touch() { dirty.set(true); }

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
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(d), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    public String uptimeText() {
        long minutes = (System.currentTimeMillis() - d.startedAt) / 60000;
        long days = minutes / 1440, hours = (minutes % 1440) / 60, mins = minutes % 60;
        if (days > 0) return days + "天" + hours + "小时";
        if (hours > 0) return hours + "小时" + mins + "分";
        return mins + "分钟";
    }
}
