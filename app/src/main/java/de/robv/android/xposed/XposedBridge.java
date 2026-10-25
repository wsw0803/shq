package de.robv.android.xposed;

import java.lang.reflect.Member;

/** 桩版本：日志走 android.util.Log，hook 注册为空操作。 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
        // 桩实现：真实环境里由框架写成 Xposed 日志（logcat 的 Xposed 标签）。
        // 这里刻意不引用 android.util.Log，避免桩类反过来依赖 Android SDK，
        // 让它能脱离 android.jar 独立编译校验。
        System.out.println("[Xposed] " + text);
    }

    public static void log(Throwable t) {
        log(String.valueOf(t));
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        // 桩：真实 hook 由框架完成
        return new XC_MethodHook.Unhook();
    }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        // 桩：真实 hook 由框架完成
        return new java.util.HashSet<XC_MethodHook.Unhook>();
    }
}
