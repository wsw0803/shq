package com.tencent.tim.core;

import java.lang.reflect.Method;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 进程内注入入口。
 *
 * 本类的四个 hook 目标全部来自逆向还原，不是猜测。
 *
 * 还原方法：老八 APK 用标准 StringFog（XOR）加密，
 * 数据数组与密钥数组都是 byte[]，两两配对解密即可。
 * 对 TrustBypassHook 里全部 14 个 byte[] 两两组合求解，
 * 得到下面这份完整清单（每组都已验证为可读字符串）：
 *
 *   :array_8  / :array_9   = android.webkit.WebView
 *   :array_a  / :array_b   = loadUrl
 *   :array_6  / :array_7   = queryIntentActivities
 *   :array_c  / :array_d   = com.tencent.mobileqq
 *   :array_3  / :array_4   = com.android.org.conscrypt.TrustManagerImpl
 *   :array_1  / :array_2   = checkTrustedRecursive
 *
 * 三处 findAndHookMethod 的参数组合也已确认：
 *
 *   hook 1: PackageManager.queryIntentActivities(Intent, int)
 *           参数含 Landroid/content/Intent; 与 Ljava/lang/Integer;->TYPE；
 *           回调 $2 里做 Intent.getPackage().equals("com.tencent.mobileqq")
 *   hook 2: WebView.loadUrl
 *           回调 $3 只打日志，不阻断
 *   hook 3: TrustManagerImpl.checkTrustedRecursive
 *           返回空 List，短路证书链
 *
 * 关键结论：「游戏看不到已安装的 QQ」不是靠 pm hide / 禁用组件，
 * 而是 hook queryIntentActivities，让包管理器查询结果里没有 QQ。
 * QQ 本体原封不动，所以叫「root 不卸载 QQ 也能登录」。
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "[SHQ-INPROC] ";

    private static final String TM_CLASS = "com.android.org.conscrypt.TrustManagerImpl";
    private static final String TM_METHOD = "checkTrustedRecursive";

    /** QQ 主包名，与老八 :array_c/:array_d 解出的一致。 */
    private static final String QQ_PKG = "com.tencent.mobileqq";

    /** 健康系统页面，命中即记日志（老八的 $3 也是只记录，不阻断）。 */
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

        GamePlan plan = GamePlan.ofPackage(lpparam.packageName);
        log("加载 " + lpparam.packageName
                + (plan == null ? "" : " / " + plan.game + " -> " + plan.note));

        int ssl = hookTrustManager();
        log("checkTrustedRecursive 已接管 " + ssl + " 个重载");

        int hide = hookQueryIntentActivities();
        log("queryIntentActivities 已接管 " + hide + " 个重载（隐藏 " + QQ_PKG + "）");

        int url = hookUrlCapture(lpparam);
        log("WebView.loadUrl 已接管 " + url);

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
    // hook 3：证书链绕过
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
    // hook 1：隐藏 QQ
    // ------------------------------------------------------------------

    /**
     * hook PackageManager.queryIntentActivities(Intent, int)。
     *
     * 老八的做法：在结果列表里把包名为 com.tencent.mobileqq 的项剔除。
     * 这里沿用，但更保守 —— 只剔除 QQ，不动其他包，
     * 避免影响游戏自身的组件查询。
     */
    private static int hookQueryIntentActivities() {
        int hooked = 0;
        try {
            Class<?> pm = XposedHelpers.findClass("android.content.pm.PackageManager", null);
            for (Method m : pm.getDeclaredMethods()) {
                if (!"queryIntentActivities".equals(m.getName())) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 2) {
                    continue;
                }
                if (!"android.content.Intent".equals(ps[0].getName())) {
                    continue;
                }
                if (ps[1] != int.class) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object r = param.getResult();
                            if (!(r instanceof List)) {
                                return;
                            }
                            try {
                                filterQQ((List<?>) r);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    hooked++;
                } catch (Throwable t) {
                    log("hook queryIntentActivities 失败: " + t);
                }
            }
        } catch (Throwable t) {
            log("queryIntentActivities 不可用: " + t);
        }
        return hooked;
    }

    /** 从 ResolveInfo 列表里剔除 QQ。全程反射，不引 Android 类。 */
    private static void filterQQ(List<?> list) {
        Iterator<?> it = list.iterator();
        while (it.hasNext()) {
            Object ri = it.next();
            if (ri == null) {
                continue;
            }
            try {
                Object ai = ri.getClass().getField("activityInfo").get(ri);
                if (ai == null) {
                    continue;
                }
                Object pkg = ai.getClass().getField("packageName").get(ai);
                if (QQ_PKG.equals(pkg)) {
                    it.remove();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // hook 2：监控健康系统 URL
    // ------------------------------------------------------------------

    private static volatile String lastAuthUrl = "";

    private static int hookUrlCapture(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> webView = null;
        try {
            webView = XposedHelpers.findClass("android.webkit.WebView", lpparam.classLoader);
        } catch (Throwable ignored) {
        }
        if (webView == null) {
            try {
                webView = XposedHelpers.findClass("android.webkit.WebView", null);
            } catch (Throwable t) {
                return 0;
            }
        }
        try {
            XposedBridge.hookAllMethods(webView, "loadUrl", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0
                            && param.args[0] instanceof String) {
                        scan((String) param.args[0]);
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            log("WebView hook 失败: " + t);
            return 0;
        }
    }

    private static void scan(String url) {
        if (url == null) {
            return;
        }
        for (String h : HEALTHY_HOSTS) {
            if (url.contains(h)) {
                // 老八的 $3 只在命中时打日志，不做阻断。
                // 这里保持一致，避免改变页面行为引入新问题。
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

    public static String lastAuthUrl() {
        return lastAuthUrl;
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
    }
}
