package com.tencent.tim.core;

import java.lang.reflect.Method;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 进程内注入入口。
 *
 * 用经典 Xposed API（de.robv.android.xposed:api:82，compileOnly）。
 *
 * 这一段踩了两次坑，记录清楚免得再犯：
 *
 *   坑 1：把 de.robv.android.xposed.* 的桩类写进源码，跟着 APK 一起打包。
 *         LSPosed 报：
 *           Cannot load module: com.tencent.tim
 *           The Xposed API classes are compiled into the module's APK.
 *         结论：API 类绝对不能进 APK，必须 compileOnly。
 *
 *   坑 2：以为要换成 libxposed 新版 API（io.github.libxposed:api:102）。
 *         实机 LSPosed 装的是老版本，只认经典 API，于是报：
 *           Failed to load class com.tencent.tim.core.XposedEntry
 *           Caused by: ClassNotFoundException:
 *             Didn't find class "io.github.libxposed.api.XposedModule"
 *         结论：用哪个 API 要跟设备上的 LSPosed 版本匹配，不能想当然。
 *
 * 四个 hook 目标全部来自老八 APK 的真实还原，不是猜的：
 *
 *   android.app.ApplicationPackageManager#getPackageInfo(String, int)
 *       → 回调 $1：查 com.tencent.mobileqq 时返回 null
 *   android.app.ApplicationPackageManager#queryIntentActivities(Intent, int)
 *       → 回调 $2：结果里剔除 QQ；Intent 点名 QQ 时返回空列表
 *   android.webkit.WebView#loadUrl
 *       → 回调 $3：只记录健康系统 URL，不阻断
 *   com.android.org.conscrypt.TrustManagerImpl#checkTrustedRecursive
 *       → 回调 $4：返回空 List，短路证书链
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "[SHQ-INPROC] ";

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
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        if (!targeted(lpparam.packageName)) {
            return;
        }

        log("加载 " + lpparam.packageName);

        int ssl = hookTrustManager();
        log("checkTrustedRecursive 已接管 " + ssl + " 个重载");

        int pms = hookPackageManager(lpparam.classLoader);
        log("ApplicationPackageManager 已接管 " + pms
                + " 个重载（getPackageInfo + queryIntentActivities，隐藏 " + QQ_PKG + "）");

        int url = hookUrlCapture();
        log("WebView.loadUrl 已接管 " + url + " 个重载");

        Diag.log("INPROC", "加载完成 ssl=" + ssl + " pms=" + pms + " url=" + url);
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

    private static int hookTrustManager() {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(TM_CLASS, null);
        } catch (Throwable t) {
            log("找不到 " + TM_CLASS + ": " + t);
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
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // 空 List = 这条证书链我认了，校验短路
                        param.setResult(new ArrayList<X509Certificate>(0));
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

    private static int hookPackageManager(ClassLoader cl) {
        Class<?> cls = null;
        try {
            cls = XposedHelpers.findClass(PM_CLASS, cl);
        } catch (Throwable t) {
            log("找不到 " + PM_CLASS);
        }
        if (cls == null) {
            try {
                cls = XposedHelpers.findClass("android.content.pm.PackageManager", cl);
            } catch (Throwable t) {
                return 0;
            }
        }

        Method[] all;
        try {
            all = cls.getDeclaredMethods();
        } catch (Throwable t) {
            return 0;
        }

        int hooked = 0;
        for (Method m : all) {
            String name = m.getName();
            Class<?>[] ps = m.getParameterTypes();

            if ("getPackageInfo".equals(name) && ps.length == 2
                    && ps[0] == String.class && ps[1] == int.class) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length > 0
                                    && QQ_PKG.equals(param.args[0])) {
                                param.setResult(null);   // 当作未安装
                                log("getPackageInfo 隐藏 " + QQ_PKG);
                            }
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
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object intent = param.args != null && param.args.length > 0
                                    ? param.args[0] : null;
                            if (intent != null && QQ_PKG.equals(intentPackage(intent))) {
                                param.setResult(new ArrayList<Object>());
                                log("queryIntentActivities 直接隐藏 " + QQ_PKG);
                                return;
                            }
                            Object result = param.getResult();
                            if (!(result instanceof List)) {
                                return;
                            }
                            List<?> src = (List<?>) result;
                            ArrayList<Object> filtered = new ArrayList<Object>();
                            for (Object item : src) {
                                if (!isQqResolveInfo(item)) {
                                    filtered.add(item);
                                }
                            }
                            if (filtered.size() != src.size()) {
                                param.setResult(filtered);
                                log("queryIntentActivities 过滤 "
                                        + (src.size() - filtered.size()) + " 个 QQ 结果");
                            }
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

    private static volatile String lastAuthUrl = "";

    private static int hookUrlCapture() {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass("android.webkit.WebView", null);
        } catch (Throwable t) {
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
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof String) {
                            scan((String) param.args[0]);
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                log("hook loadUrl 失败: " + t);
            }
        }
        return hooked;
    }

    private static void scan(String url) {
        if (url == null) {
            return;
        }
        for (String h : HEALTHY_HOSTS) {
            if (url.contains(h)) {
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
    private static void writeStatus(int n) {
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

    private static void log(String s) {
        try {
            XposedBridge.log(TAG + s);
        } catch (Throwable ignored) {
        }
        Diag.log("INPROC", s);
    }
}
