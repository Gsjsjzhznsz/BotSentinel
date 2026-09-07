package cn.botsentinel.core;

import java.util.List;
import java.util.Locale;

/**
 * IPv4 工具: 私有/保留段判定 + CIDR 匹配 (纯 Java, 可移植)。
 */
public final class IpUtil {

    private IpUtil() {}

    public static boolean isIpv4(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String[] p = ip.split("\\.");
        if (p.length != 4) return false;
        for (String s : p) {
            if (s.isEmpty() || s.length() > 3) return false;
            try {
                int v = Integer.parseInt(s);
                if (v < 0 || v > 255) return false;
            } catch (NumberFormatException e) { return false; }
        }
        return true;
    }

    /** IPv4 -> int; 非法返回 0 */
    public static int toInt(String ip) {
        if (!isIpv4(ip)) return 0;
        String[] p = ip.split("\\.");
        return (Integer.parseInt(p[0]) << 24) | (Integer.parseInt(p[1]) << 16)
                | (Integer.parseInt(p[2]) << 8) | Integer.parseInt(p[3]);
    }

    /** 10/8, 172.16/12, 192.168/16, 127/8, 169.254/16, 0.0.0.0 */
    public static boolean isPrivateOrReserved(String ip) {
        if (!isIpv4(ip)) return ip == null || ip.isEmpty(); // 非IPv4(如IPv6)视为需上层处理
        int a = Integer.parseInt(ip.split("\\.")[0]);
        int b = Integer.parseInt(ip.split("\\.")[1]);
        if (ip.startsWith("0.")) return true;
        if (a == 10) return true;
        if (a == 127) return true;
        if (a == 172 && b >= 16 && b <= 31) return true;
        if (a == 192 && b == 168) return true;
        if (a == 169 && b == 254) return true;
        return false;
    }

    /** ip 是否落在 cidr (形如 1.2.3.0/24 或单IP) 内 */
    public static boolean inCidr(String ip, String cidr) {
        if (ip == null || cidr == null || cidr.isEmpty()) return false;
        cidr = cidr.trim();
        int slash = cidr.indexOf('/');
        String base = slash < 0 ? cidr : cidr.substring(0, slash);
        int bits = slash < 0 ? 32 : Integer.parseInt(cidr.substring(slash + 1));
        if (!isIpv4(ip) || !isIpv4(base) || bits < 0 || bits > 32) return false;
        int mask = bits == 0 ? 0 : (0xFFFFFFFF << (32 - bits));
        return (toInt(ip) & mask) == (toInt(base) & mask);
    }

    public static boolean inAnyCidr(String ip, List<String> cidrs) {
        if (cidrs == null) return false;
        for (String c : cidrs) if (inCidr(ip, c)) return true;
        return false;
    }

    public static String lc(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
