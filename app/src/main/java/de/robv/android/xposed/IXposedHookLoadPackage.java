package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 模块入口接口之一。
 *
 * 注意：这是编译期「桩」，不是真的 Xposed API 实现。
 * 运行时由 LSPosed / EdXposed 提供同名同签名类，
 * 它们会优先于本包中的同名类被加载（模块类由框架注入的 classloader 解析）。
 * 目的只是让源码在没有任何外部仓库的情况下能编译通过。
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
