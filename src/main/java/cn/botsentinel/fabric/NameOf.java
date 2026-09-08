package cn.botsentinel.fabric;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * v2.4 跨版本: GameProfile 取名兼容层。
 * MC 1.21.9 起的 authlib 把 GameProfile 改为 record 风格(getName() 移除, 变成 name()),
 * 直接调用任一名字都会在另一半版本上编译失败。这里用反射按优先级探测:
 * getName() -> name(), Method 缓存一次, 每次调用仅一次反射查找(无遍历)。
 */
public final class NameOf {

    private static final AtomicReference<Method> CACHE = new AtomicReference<>();

    private static Method resolve() {
        Method m = CACHE.get();
        if (m != null) return m;
        for (String name : new String[]{"getName", "name"}) {
            try {
                Method mm = GameProfile.class.getMethod(name);
                mm.setAccessible(true);
                CACHE.set(mm);
                return mm;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 取玩家名; 失败返回 "" 永不抛出 */
    public static String of(GameProfile profile) {
        if (profile == null) return "";
        try {
            Method m = resolve();
            if (m != null) {
                Object r = m.invoke(profile);
                return r == null ? "" : r.toString();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private NameOf() {}
}
