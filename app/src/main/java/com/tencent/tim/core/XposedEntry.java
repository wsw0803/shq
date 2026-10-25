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
 * 重构要点：上一版凭猜测 hook 了 okhttp3.CertificatePinner 和
 * javax.net.ssl.X509TrustManager，两个都不对：
 *
 *   - okhttp3.CertificatePinner 只在游戏自己用 OkHttp 做 pinning 时才存在，
 *     腾讯 MSDK 的原生网络层根本不经过它。
 *   - X509TrustManager 是接口，hook 接口的抽象方法不会影响任何实现类，
 *     那条兜底从来就没生效过。
 *
 * 老八 1.1 版的真实目标（从其 TrustBypassHook 的 StringFog 密文还原）：
 *
 *   SSL_CLASS_NAME  = com.android.org.conscrypt.TrustManagerImpl
 *   SSL_METHOD_NAME = checkTrustedRecursive
 *   返回类型        = java.util.List
 *   参数含          = java.security.cert.X509Certificate
 *
 * checkTrustedRecursive 是 Android 系统 TLS 栈里真正做证书链校验的那一层。
 * 让它直接返回空 List，就把整条校验链短路了 —— 不论上层是 OkHttp、
 * HttpsURLConnection 还是 MSDK 自带网络层，都会被覆盖。
 * 这正是老八能过腾讯健康系统的原因。
 *
 * 参数个数随 Android 版本变化（7.0 是 5 个，10 以上 6~7 个），
 * 所以不能写死签名。老八的做法是反射遍历方法列表逐个筛选
 * （它的 checkSSLMethod(Method)Z，513 条指令），本类沿用同一策略。
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "[SHQ-INPROC] ";

    /** Conscrypt 的 TrustManagerImpl，位于系统 boot classpath。 */
    private static final String TM_CLASS = "com.android.org.conscrypt.TrustManagerImpl";
    /** 真正做证书链校验的方法名。 */
    private static final String TM_METHOD = "checkTrustedRecursive";

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

        int n = hookTrustManager();
        log(TM_METHOD + " 已接管 " + n + " 个重载");

        hookUrlCapture(lpparam);
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
    // 证书链校验绕过
    // ------------------------------------------------------------------

    /**
     * 反射遍历 TrustManagerImpl 的所有 checkTrustedRecursive 重载，
     * 逐个按签名特征筛选后 hook。
     *
     * 返回命中的重载个数：0 表示没接上（类不在，或方法改名了）。
     * 这个数字会进 Xposed 日志，现场能直接看出是哪种情况。
     */
    private static int hookTrustManager() {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(TM_CLASS, null);
        } catch (Throwable t) {
            log("找不到 " + TM_CLASS + ": " + t);
            return 0;
        }
        if (cls == null) {
            return 0;
        }

        Method[] all;
        try {
            all = cls.getDeclaredMethods();
        } catch (Throwable t) {
            log("读取方法列表失败: " + t);
            return 0;
        }

        int hooked = 0;
        for (Method m : all) {
            if (m == null || !TM_METHOD.equals(m.getName())) {
                continue;
            }
            if (!looksLikeTarget(m)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // 空 List = 「这条链我认了」，不抛异常
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

    /**
     * 认方法靠两个特征，不靠参数个数：
     *   1) 返回类型能赋给 List
     *   2) 参数里同时有 X509Certificate[] 和 List
     *
     * 这两条足以圈住 checkTrustedRecursive 的同族重载，
     * 又不会误伤同类的其他方法。
     */
    private static boolean looksLikeTarget(Method m) {
        try {
            if (!List.class.isAssignableFrom(m.getReturnType())) {
                return false;
            }
            boolean hasChain = false;
            boolean hasList = false;
            for (Class<?> p : m.getParameterTypes()) {
                if (p == X509Certificate[].class) {
                    hasChain = true;
                }
                if (List.class.isAssignableFrom(p)) {
                    hasList = true;
                }
            }
            return hasChain && hasList;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 登录回调截获
    // ------------------------------------------------------------------

    private static volatile String lastAuthUrl = "";

    private static void hookUrlCapture(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> webView = null;
        try {
            webView = XposedHelpers.findClass("android.webkit.WebView", lpparam.classLoader);
        } catch (Throwable ignored) {
        }
        if (webView == null) {
            try {
                // WebView 在 boot classpath，换系统 loader 再试
                webView = XposedHelpers.findClass("android.webkit.WebView", null);
            } catch (Throwable t) {
                log("WebView 不可用: " + t);
                return;
            }
        }
        if (webView == null) {
            return;
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
            log("WebView.loadUrl 已接管");
        } catch (Throwable t) {
            log("WebView hook 失败: " + t);
        }
    }

    private static void scan(String url) {
        if (url == null) {
            return;
        }
        String low = url.toLowerCase();
        if (!low.contains("access_token=") && !low.contains("auth://")) {
            return;
        }
        lastAuthUrl = url;
        log("捕获登录回调: " + mask(url));
    }

    private static String mask(String s) {
        if (s == null || s.length() <= 24) {
            return String.valueOf(s);
        }
        return s.substring(0, 24) + "..." + s.substring(s.length() - 6);
    }

    /** 供后续 MSDK 回灌读取。 */
    public static String lastAuthUrl() {
        return lastAuthUrl;
    }

    private static void log(String s) {
        try {
            XposedBridge.log(TAG + s);
        } catch (Throwable ignored) {
        }
    }
}
