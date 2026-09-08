package cn.botsentinel.fabric;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v2.4 跨版本改造: 按"字段类型"反射提取网络连接, 不再依赖 mixin accessor / 映射字段名。
 *
 * 背景: ServerCommonNetworkHandler 是 1.20.2 才引入的类, 在 1.20.1 上
 * accessor mixin 的目标类不存在; 未来版本(26.x+)字段名也可能漂移。
 * 改为在 handler 类层级中查找第一个 ClientConnection 类型的字段 ——
 * 与 yarn/intermediary 映射名完全无关, 1.20.1 ~ 26.x 全部适用。
 * ClientConnection#getRemoteAddress 为 yarn 长期稳定 API, 由 loom 按
 * 目标版本自动重映射。
 */
public final class ConnectionIps {

    private static final Map<Class<?>, Field> CACHE = new ConcurrentHashMap<>();

    private ConnectionIps() {}

    /** 提取玩家远程IP(仅IPv4字面量); 失败返回 "" , 永不抛出 */
    public static String remoteIp(Object networkHandler) {
        if (networkHandler == null) return "";
        try {
            Object conn = connectionOf(networkHandler);
            if (!(conn instanceof net.minecraft.network.ClientConnection)) return "";
            // v2.4: 取远程地址不按方法名(yarn 1.20.1=getAddress, 1.21+=getRemoteAddress),
            // 改按"无参且返回 SocketAddress"反射 —— java.net.SocketAddress 是 JDK 类,
            // 永不被重映射, 1.20.1 ~ 26.x 全部适用。
            SocketAddress addr = null;
            for (java.lang.reflect.Method m : conn.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == SocketAddress.class) {
                    addr = (SocketAddress) m.invoke(conn);
                    break;
                }
            }
            if (addr instanceof InetSocketAddress isa && isa.getAddress() != null) {
                return isa.getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {
            // 风控永不影响正常游戏
        }
        return "";
    }

    private static Object connectionOf(Object handler) throws IllegalAccessException {
        if (handler == null) return null;
        Field f = CACHE.computeIfAbsent(handler.getClass(), c -> {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field ff : k.getDeclaredFields()) {
                    if (net.minecraft.network.ClientConnection.class.isAssignableFrom(ff.getType())) {
                        try { ff.setAccessible(true); return ff; } catch (Throwable ignored) {}
                    }
                }
            }
            return null;
        });
        return f == null ? null : f.get(handler);
    }
}
