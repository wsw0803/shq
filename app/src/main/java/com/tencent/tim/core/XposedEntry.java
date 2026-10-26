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
 *   hook 1: android.app.ApplicationPackageManager#getPackageInfo(String, int)
 *           与 #queryIntentActivities(Intent, int)
 *           参数分别含 String/int、Intent/int；
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

        int pkgHooks = hookPackageManager();
        log("ApplicationPackageManager 已接管 " + pkgHooks
                + " 个重载（getPackageInfo + queryIntentActivities，隐藏 " + QQ_PKG + "）");

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
     * 老八的 $1 + $2：具体 hook android.app.ApplicationPackageManager，
     * 不是抽象的 android.content.pm.PackageManager。
     *
     *   $1: getPackageInfo(String, int)
     *       查询 com.tencent.mobileqq 时返回 null，
     *       让游戏把 QQ 当作未安装。
     *
     *   $2: queryIntentActivities(Intent, int)
     *       结果里剔除 com.tencent.mobileqq；如果 Intent 明确指定 QQ，
     *       直接返回空列表。
     */
    private static int hookPackageManager() {
        int hooked = 0;
        try {
            Class<?> pm = XposedHelpers.findClass(
                    "android.app.ApplicationPackageManager", null);
            for (Method m : pm.getDeclaredMethods()) {
                String name = m.getName();
                Class<?>[] ps = m.getParameterTypes();

                if ("getPackageInfo".equals(name)
                        && ps.length == 2
                        && ps[0] == String.class
                        && ps[1] == int.class) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args != null && param.args.length > 0
                                        && QQ_PKG.equals(param.args[0])) {
                                    // 老八 $1 的屏蔽分支：setResult(null)
                                    param.setResult(null);
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

                if ("queryIntentActivities".equals(name)
                        && ps.length == 2
                        && ps[0].getName().equals("android.content.Intent")
                        && ps[1] == int.class) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object result = param.getResult();
                                if (!(result instanceof List)) {
                                    return;
                                }
                                Object intent = param.args != null && param.args.length > 0
                                        ? param.args[0] : null;
                                if (intent != null && QQ_PKG.equals(intentPackage(intent))) {
                                    param.setResult(new ArrayList<Object>());
                                    log("queryIntentActivities 直接隐藏 " + QQ_PKG);
                                    return;
                                }
                                List<?> source = (List<?>) result;
                                ArrayList<Object> filtered = new ArrayList<Object>();
                                for (Object item : source) {
                                    if (!isQqResolveInfo(item)) {
                                        filtered.add(item);
                                    }
                                }
                                if (filtered.size() != source.size()) {
                                    param.setResult(filtered);
                                    log("queryIntentActivities 过滤 "
                                            + (source.size() - filtered.size()) + " 个 QQ 结果");
                                }
                            }
                        });
                        hooked++;
                    } catch (Throwable t) {
                        log("hook queryIntentActivities 失败: " + t);
                    }
                }
            }
        } catch (Throwable t) {
            log("ApplicationPackageManager 不可用: " + t);
        }
        return hooked;
    }

    private static String intentPackage(Object intent) {
        try {
            Object value = intent.getClass().getMethod("getPackage").invoke(intent);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable t) {
            return "";
        }
    }

    /** ResolveInfo.activityInfo.packageName == com.tencent.mobileqq。 */
    private static boolean isQqResolveInfo(Object resolveInfo) {
        if (resolveInfo == null) {
            return false;
        }
        try {
            Object activityInfo = resolveInfo.getClass().getField("activityInfo")
                    .get(resolveInfo);
            if (activityInfo == null) {
                return false;
            }
            Object packageName = activityInfo.getClass().getField("packageName")
                    .get(activityInfo);
            return QQ_PKG.equals(packageName);
        } catch (Throwable t) {
            return false;
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

    /**
     * 双写。
     *
     * XposedBridge.log 走 logcat，需要 LSPosed 把日志级别开到详细
     * 才看得到 —— 上一轮就是因为这个，导出的 4MB 日志里
     * 一条 [SHQ-INPROC] 都没有。
     *
     * Diag 直接写文件，路径在游戏自己的外部 files 目录，
     * 不受日志级别影响，进程被杀也留得下。
     * 两条路都写，任一条可用就能定位问题。
     */
    private static void log(String s) {
        try {
            XposedBridge.log(TAG + s);
        } catch (Throwable ignored) {
        }
        Diag.log("INPROC", s);
    }
}
