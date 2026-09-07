package cn.botsentinel.core;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 风险追踪: 滑动窗口统计每个 IP 的"新账号"数量。
 * 窗口内新账号越多, IP 加分越高; 达到 auto-ban-count 自动临时封禁。
 * 只统计未受信任的名字, 已登记真人不会推高计数。
 *
 * v2.2 新增: 闪进闪退窗口 —— 日志实锤的新漏网模式 Ciloat77422:
 *   进服 3 秒即断开重连, 反复数次。真人极少这样(掉线重连一般会停留),
 *   同 IP 短停留进出越频繁, 风险越高。
 */
public class IpRiskTracker {

    private static class Window {
        final java.util.ArrayDeque<Long> stamps = new java.util.ArrayDeque<>();
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Map<String, Window> flashWindows = new ConcurrentHashMap<>();
    private final long windowMs;

    public IpRiskTracker(long windowMinutes) {
        this.windowMs = Math.max(1, windowMinutes) * 60000L;
    }

    /** 记录一个新账号登录(未被信任的名字); 返回窗口内当前计数 */
    public int recordNewName(String ip, String name) {
        if (ip == null) return 0;
        Window w = windows.computeIfAbsent(ip.toLowerCase(Locale.ROOT), k -> new Window());
        synchronized (w) {
            long now = System.currentTimeMillis();
            while (!w.stamps.isEmpty() && now - w.stamps.peekFirst() > windowMs) w.stamps.pollFirst();
            w.stamps.addLast(now);
            return w.stamps.size();
        }
    }

    /** 当前窗口内新账号数(不新增) */
    public int newNameCount(String ip) {
        if (ip == null) return 0;
        Window w = windows.get(ip.toLowerCase(Locale.ROOT));
        if (w == null) return 0;
        synchronized (w) {
            long now = System.currentTimeMillis();
            while (!w.stamps.isEmpty() && now - w.stamps.peekFirst() > windowMs) w.stamps.pollFirst();
            return w.stamps.size();
        }
    }

    /** 记录一次"闪进闪退"(停留 < 20 秒即断开); 返回窗口内计数 */
    public int recordFlashQuit(String ip) {
        if (ip == null) return 0;
        Window w = flashWindows.computeIfAbsent(ip.toLowerCase(Locale.ROOT), k -> new Window());
        synchronized (w) {
            long now = System.currentTimeMillis();
            while (!w.stamps.isEmpty() && now - w.stamps.peekFirst() > windowMs) w.stamps.pollFirst();
            w.stamps.addLast(now);
            return w.stamps.size();
        }
    }

    /** 当前窗口内闪进闪退次数(不新增) */
    public int flashQuitCount(String ip) {
        if (ip == null) return 0;
        Window w = flashWindows.get(ip.toLowerCase(Locale.ROOT));
        if (w == null) return 0;
        synchronized (w) {
            long now = System.currentTimeMillis();
            while (!w.stamps.isEmpty() && now - w.stamps.peekFirst() > windowMs) w.stamps.pollFirst();
            return w.stamps.size();
        }
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Window> en : windows.entrySet()) {
            synchronized (en.getValue()) {
                while (!en.getValue().stamps.isEmpty() && now - en.getValue().stamps.peekFirst() > windowMs)
                    en.getValue().stamps.pollFirst();
                if (en.getValue().stamps.isEmpty()) windows.remove(en.getKey());
            }
        }
        for (Map.Entry<String, Window> en : flashWindows.entrySet()) {
            synchronized (en.getValue()) {
                while (!en.getValue().stamps.isEmpty() && now - en.getValue().stamps.peekFirst() > windowMs)
                    en.getValue().stamps.pollFirst();
                if (en.getValue().stamps.isEmpty()) flashWindows.remove(en.getKey());
            }
        }
    }
}
