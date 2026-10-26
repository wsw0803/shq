package com.tencent.tim.core;

import java.lang.reflect.Method;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 进程内注入入口。
 *
 * 使用 libxposed API 102（io.github.libxposed:api:102.0.0），
 * 不是老的 de.robv.android.xposed:api:82。
 *
 * 为什么要换：
 *
 *   最初为了避免 Maven 依赖，把 de.robv.android.xposed.* 的桩类写进了源码，
 *   结果跟着 APK 一起打包。LSPosed 检测到 APK 自带 Xposed API 类，
 *   直接拒绝加载并报：
 *
 *     Cannot load module: com.tencent.tim
 *     The Xposed API classes are compiled into the module's APK.
 *
 *   现在改用官方 API 并声明 compileOnly，API 类不进 APK，模块才能被加载。
 *
 * 四个 hook 目标全部来自老八 APK 的真实还原，不是猜的：
 *
 *   android.app.ApplicationPackageManager#getPackageInfo(String, int)
 *       → 回调 $1：查询 com.tencent.mobileqq 时返回 null
 *   android.app.ApplicationPackageManager#queryIntentActivities(Intent, int)
 *       → 回调 $2：结果里剔除 QQ；Intent 点名 QQ 时返回空列表
 *   android.webkit.WebView#loadUrl
 *       → 回调 $3：只记录健康系统 URL，不阻断
 *   com.android.org.conscrypt.TrustManagerImpl#checkTrustedRecursive
 *       → 回调 $4：返回空 List，短路证书链
 */
public class XposedEntry extends XposedModule {

    private static final String TAG = "SHQ-INPROC";

    private static final String TM_CLASS = "com.android.org.conscrypt.TrustManagerImpl";
    private static final String TM_METHOD = "checkTrustedRecursive";
    private static final String PM_CLASS = "android.app.ApplicationPackageManager";
    private static final String QQ_PKG = "com.tencent.mobileqq";

    private static final String[] HEALTHY_HOSTS = {
            "jiazhang.qq.com/healthy/dist/faceRecognition/game_no.html",
            "jiazhang.qq.com/healthy/dist/faceRecognition/guide.html",
            "jiazhang.qq.com/wap/family/dist/msdk/parent.html",
            "jiazhang.qq.com/wap/zk/dist/app_cert.html",
    };

    private static final String[] TARGETS = {
            "com.tencent.tmgp.dfm",
            "com.tencent.tmgp.pubgmhd",
            "com.tencent.tmgp.sgame",
            "com.tencent.mf.uam",
            "com.tencent.tmgp.codev",
    };

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (param == null) {
            return;
        }
        String pkg = param.getPackageName();
        if (!targeted(pkg)) {
            return;
        }

        log("加载 " + pkg);

        int ssl = hookTrustManager();
        log("checkTrustedRecursive 已接管 " + ssl + " 个重载");

        int pms = hookPackageManager();
        log("ApplicationPackageManager 已接管 " + pms
                + " 个重载（getPackageInfo + queryIntentActivities，隐藏 " + QQ_PKG + "）");

        int url = hookUrlCapture();
        log("WebView.loadUrl 已接管 " + url + " 个重载");

        writeStatus(ssl);
    }

    private static boolean targeted(String pkg) {
        for (String t : TARGETS) {
            if (t.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // hook 4：证书链绕过
    // ------------------------------------------------------------------

    private int hookTrustManager() {
        Class<?> cls = cls(TM_CLASS, null);
        if (cls == null) {
            log("找不到 " + TM_CLASS);
            return 0;
        }
        Method[] all;
        try {
            all = cls.getDeclaredMethods();
        } catch (Throwable t) {
            return 0;
        }
        int hooked = 0;
        for (Method m : all) {
            if (m == null || !TM_METHOD.equals(m.getName())) {
                continue;
            }
            if (!List.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            try {
                hook(m).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) {
                        // 空 List = 这条证书链我认了，校验短路
                        return new ArrayList<X509Certificate>(0);
                    }
                });
                hooked++;
            } catch (Throwable t) {
                log("hook " + m + " 失败: " + t);
            }
        }
        return hooked;
    }

    // ------------------------------------------------------------------
    // hook 1 + 2：隐藏 QQ
    // ------------------------------------------------------------------

    private int hookPackageManager() {
        Class<?> cls = cls(PM_CLASS, null);
        if (cls == null) {
            log("找不到 " + PM_CLASS + "，退回 PackageManager");
            cls = cls("android.content.pm.PackageManager", null);
        }
        if (cls == null) {
            return 0;
        }
        int hooked = 0;
        Method[] all;
        try {
            all = cls.getDeclaredMethods();
        } catch (Throwable t) {
            return 0;
        }

        for (Method m : all) {
            String name = m.getName();
            Class<?>[] ps = m.getParameterTypes();

            if ("getPackageInfo".equals(name) && ps.length == 2
                    && ps[0] == String.class && ps[1] == int.class) {
                try {
                    hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object arg0 = chain.getArg(0);
                            if (QQ_PKG.equals(arg0)) {
                                log("getPackageInfo 隐藏 " + QQ_PKG);
                                return null;   // 当作未安装
                            }
                            return chain.proceed();
                        }
                    });
                    hooked++;
                } catch (Throwable t) {
                    log("hook getPackageInfo 失败: " + t);
                }
                continue;
            }

            if ("queryIntentActivities".equals(name) && ps.length == 2
                    && "android.content.Intent".equals(ps[0].getName()) && ps[1] == int.class) {
                try {
                    hook(m).intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            Object intent = chain.getArg(0);
                            if (intent != null && QQ_PKG.equals(intentPackage(intent))) {
                                log("queryIntentActivities 直接隐藏 " + QQ_PKG);
                                return new ArrayList<Object>();
                            }
                            if (!(result instanceof List)) {
                                return result;
                            }
                            List<?> src = (List<?>) result;
                            ArrayList<Object> filtered = new ArrayList<Object>();
                            for (Object item : src) {
                                if (!isQqResolveInfo(item)) {
                                    filtered.add(item);
                                }
                            }
                            if (filtered.size() != src.size()) {
                                log("queryIntentActivities 过滤 "
                                        + (src.size() - filtered.size()) + " 个 QQ 结果");
                                return filtered;
                            }
                            return result;
                        }
                    });
                    hooked++;
                } catch (Throwable t) {
                    log("hook queryIntentActivities 失败: " + t);
                }
            }
        }
        return hooked;
    }

    private static String intentPackage(Object intent) {
        try {
            Object v = intent.getClass().getMethod("getPackage").invoke(intent);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isQqResolveInfo(Object resolveInfo) {
        if (resolveInfo == null) {
            return false;
        }
        try {
            Object ai = resolveInfo.getClass().getField("activityInfo").get(resolveInfo);
            if (ai == null) {
                return false;
            }
            Object pkg = ai.getClass().getField("packageName").get(ai);
            return QQ_PKG.equals(pkg);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // hook 3：监控健康系统 URL
    // ------------------------------------------------------------------

    private volatile String lastAuthUrl = "";

    private int hookUrlCapture() {
        Class<?> cls = cls("android.webkit.WebView", null);
        if (cls == null) {
            return 0;
        }
        int hooked = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (!"loadUrl".equals(m.getName())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length < 1 || ps[0] != String.class) {
                continue;
            }
            try {
                hook(m).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object arg0 = chain.getArg(0);
                        if (arg0 instanceof String) {
                            scan((String) arg0);
                        }
                        return chain.proceed();
                    }
                });
                hooked++;
            } catch (Throwable t) {
                log("hook loadUrl 失败: " + t);
            }
        }
        return hooked;
    }

    private void scan(String url) {
        if (url == null) {
            return;
        }
        for (String h : HEALTHY_HOSTS) {
            if (url.contains(h)) {
                // 老八的 $3 也只记录，不阻断
                log("健康系统命中: " + h);
                return;
            }
        }
        String low = url.toLowerCase();
        if (low.contains("access_token=") || low.contains("auth://")) {
            lastAuthUrl = url;
            log("捕获登录回调: " + mask(url));
        }
    }

    private static String mask(String s) {
        if (s == null || s.length() <= 24) {
            return String.valueOf(s);
        }
        return s.substring(0, 24) + "..." + s.substring(s.length() - 6);
    }

    // ------------------------------------------------------------------

    /** 把接管数写进目标进程自己的 files 目录，供本 App 用 root 读取。 */
    private void writeStatus(int n) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app == null) {
                return;
            }
            Object dir = app.getClass().getMethod("getFilesDir").invoke(app);
            if (!(dir instanceof java.io.File)) {
                return;
            }
            java.io.File f = new java.io.File((java.io.File) dir, "shq_xposed.status");
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            try {
                w.write("hooked=" + n);
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            log("写状态失败: " + t);
        }
    }

    private static Class<?> cls(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private void log(String msg) {
        try {
            log(android.util.Log.INFO, TAG, msg);
        } catch (Throwable ignored) {
        }
    }
}
