package com.tencent.tim.core;

import java.lang.reflect.Method;

/**
 * 反射小工具。
 *
 * 重构要点：此前 GameLaunch 里到处 try/catch Throwable 直接吞掉，
 * 出问题时日志里只剩一句「失败」，分不清是类不存在、方法签名变了、
 * 还是权限不够。这里统一成「找不到 -> null，调用失败 -> null」，
 * 调用方拿 null 就能判断是哪一层断了。
 */
public final class Reflect {

    private Reflect() {
    }

    /** 找不到就返回 null，不抛。 */
    public static Class<?> cls(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 沿父类链找方法，找到就 setAccessible。 */
    public static Method method(Class<?> c, String name, Class<?>... sig) {
        if (c == null) {
            return null;
        }
        Class<?> p = c;
        while (p != null && p != Object.class) {
            try {
                Method m = p.getDeclaredMethod(name, sig);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
                // 这个层级没有，继续往上
            }
            p = p.getSuperclass();
        }
        return null;
    }

    public static Object call(Object target, Method m, Object... args) {
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }
}
