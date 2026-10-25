package de.robv.android.xposed;

/** 运行时由框架提供；此处仅为编译期桩。 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader cl) {
        try {
            return Class.forName(className, false, cl);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader cl) {
        try {
            return Class.forName(className, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }
}
