package de.robv.android.xposed.callbacks;

/** 运行时由框架提供；此处仅为编译期桩。 */
public abstract class XC_LoadPackage {

    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
