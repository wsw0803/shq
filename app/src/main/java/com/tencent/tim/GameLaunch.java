package com.tencent.tim;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 token 交给目标游戏。
 *
 * 2026-10-25 第四次修订。上一版日志暴露了两个真问题：
 *
 * 一、把「组件收下 Intent 就结束」当成了成功。
 *
 *   [DISPATCH] A 精确启动 com.tencent.tauth.AuthActivity [auth] -> 已接收
 *     Status: ok LaunchState: UNKNOWN (0)
 *   [DISPATCH] 当前前台: com.tencent.tim/com.tencent.open.agent.AgentActivity
 *
 *   LaunchState: UNKNOWN 意味着这个 Activity 起来就立刻结束了，前台一分没变，
 *   说明它把 Intent 当成无效数据丢掉了。但代码只看 Status: ok 就继续，
 *   四个候选全部这样空转一遍。
 *
 * 二、把「游戏被唤醒」当成了 token 已消费。
 *
 *   [DISPATCH] B 主界面 SplashActivity [auth] -> LaunchState: WARM
 *   [DISPATCH] 当前前台: com.tencent.tmgp.dfm/com.epicgames.ue4.GameActivity
 *   [DISPATCH] B auth 已随 Intent 送进游戏主界面
 *
 *   三角洲本来就在后台活着，SplashActivity 只是把它带回前台，
 *   根本没有读取我们塞的 token。这句「已送进主界面」是错的。
 *
 * 三、MAX_TRIES=3 把最有希望的组件排除了。
 *
 *   三角洲的 8 个候选中，真正加载 URL 并截获 auth 回调的是
 *   MSDK 的 WebViewActivity / WebViewIPCActivity，它们在列表第 5、6 位，
 *   被「只试前 3 个」直接砍掉。
 *
 * 现在的判断标准：
 *
 * - 收下 + 停在前台（游戏内某个 Activity）= 强成功，token 很可能被消费了。
 * - 收下 + 立刻退出（LaunchState: UNKNOWN 且前台没变）= 失败，换下一个。
 * - Splash/主界面把游戏唤醒 = 弱成功，只算「唤醒了游戏」，
 *   会在结果里如实标注，不再冒充 token 交付成功。
 */
public class GameLaunch {

    private static final Map<String, String> PACKAGES = new HashMap<String, String>();

    static {
        PACKAGES.put("和平", "com.tencent.tmgp.pubgmhd");
        PACKAGES.put("王者", "com.tencent.tmgp.sgame");
        PACKAGES.put("三角洲", "com.tencent.tmgp.dfm");
        PACKAGES.put("暗区突围", "com.tencent.mf.uam");
        PACKAGES.put("无畏契约", "com.tencent.tmgp.codev");
    }

    /** 候选上限。MSDK 的 WebView 类组件往往排在后面，太少会全部错过。 */
    private static final int MAX_TRIES = 8;

    private static final char LF = (char) 10;
    private static final char CR = (char) 13;

    /** 派发结果的成色。 */
    public static final int RESULT_NONE = 0;
    /** 只是把游戏唤到前台，token 未必被读取。 */
    public static final int RESULT_WOKE = 1;
    /** 组件收下并停在游戏内，token 很可能已被消费。 */
    public static final int RESULT_LANDED = 2;

    public static String packageOf(String gameName) {
        if (gameName == null) {
            return "";
        }
        String p = PACKAGES.get(gameName.trim());
        return p == null ? "" : p;
    }

    public static boolean hasPackage(String gameName) {
        return !packageOf(gameName).isEmpty();
    }

    public static boolean isInstalled(Context c, String pkg) {
        if (c == null || pkg == null || pkg.isEmpty()) {
            return false;
        }
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 游戏里真实存在的 Activity（不是 provider，不是 service）。 */
    public static List<String> activitiesOf(Context c, String pkg) {
        List<String> out = new ArrayList<String>();
        if (c == null || pkg == null || pkg.isEmpty()) {
            return out;
        }
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (pi != null && pi.activities != null) {
                LinkedHashSet<String> seen = new LinkedHashSet<String>();
                for (ActivityInfo ai : pi.activities) {
                    if (ai == null || ai.name == null || ai.name.isEmpty()) {
                        continue;
                    }
                    if (seen.add(ai.name)) {
                        out.add(ai.name);
                    }
                }
            }
        } catch (Throwable t) {
            LoginDiag.add("DISPATCH", "PackageManager 读 " + pkg + " 失败: " + t);
        }
        if (out.isEmpty()) {
            out.addAll(activitiesFromDumpsys(pkg));
        }
        return out;
    }

    /** PackageManager 被包可见性拦住时的兜底：只认类名以 Activity 结尾的条目。 */
    private static List<String> activitiesFromDumpsys(String pkg) {
        List<String> out = new ArrayList<String>();
        String dump = runSu("dumpsys package " + pkg);
        if (dump == null || dump.isEmpty()) {
            return out;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String raw : dump.split("\\n")) {
            String ln = raw.trim();
            int slash = ln.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            int space = ln.indexOf(' ');
            if (space > 0 && space < slash) {
                continue;
            }
            String left = ln.substring(0, slash).trim();
            if (!left.equals(pkg)) {
                continue;
            }
            String right = ln.substring(slash + 1).trim();
            int sp = right.indexOf(' ');
            if (sp > 0) {
                right = right.substring(0, sp);
            }
            right = right.replace(":", "").trim();
            if (right.isEmpty()) {
                continue;
            }
            String full = right.startsWith(".") ? (pkg + right) : right;
            if (!full.toLowerCase(Locale.ROOT).endsWith("activity")) {
                continue;
            }
            if (seen.add(full)) {
                out.add(full);
            }
        }
        return out;
    }

    /** 疑似接收 auth 的登录组件，按可能性从高到低。 */
    public static List<String> findAuthActivities(Context c, String pkg) {
        List<Hit> hits = new ArrayList<Hit>();
        Map<String, Boolean> exported = exportedMap(c, pkg);
        for (String cls : activitiesOf(c, pkg)) {
            int s = score(cls);
            if (s < 4) {
                continue;
            }
            if (Boolean.TRUE.equals(exported.get(cls))) {
                s += 2;
            }
            hits.add(new Hit(cls, s));
        }
        Collections.sort(hits, new HitCmp());
        List<String> out = new ArrayList<String>();
        for (Hit h : hits) {
            out.add(h.name);
        }
        return out;
    }

    /** 旧接口：没有 Context 拿不到组件列表，保留只为不破坏已有调用点。 */
    public static List<String> findAuthComponents(String pkg) {
        return new ArrayList<String>();
    }

    private static Map<String, Boolean> exportedMap(Context c, String pkg) {
        Map<String, Boolean> m = new HashMap<String, Boolean>();
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (pi != null && pi.activities != null) {
                for (ActivityInfo ai : pi.activities) {
                    if (ai != null && ai.name != null) {
                        m.put(ai.name, ai.exported);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    /**
     * 打分。
     *
     * 排在最前的必须是真的会「读 URL、处理 auth 回调」的组件：
     * MSDK 的 WebViewActivity 才是登录页宿主，AuthActivity / AssistActivity
     * 只是 QQ SDK 的转发壳，实测收到数据就立刻结束。
     */
    private static int score(String cls) {
        if (cls == null || cls.isEmpty()) {
            return -1;
        }
        String low = cls.toLowerCase(Locale.ROOT);
        // 明确不是 Activity 的、或者纯壳界面，直接淘汰
        if (low.contains("provider") || low.contains("receiver") || low.contains("service")
                || low.contains("proxy") || low.contains("permission")
                || low.contains("policy") || low.contains("qrcode")
                || low.contains("pay")) {
            return -1;
        }
        int s = 0;
        // MSDK 的 webview 宿主：加载 ptlogin 页、截获 auth:// 回调的就是它
        if (low.contains("webviewactivity")) {
            s += 20;
        }
        if (low.contains("webviewipcactivity")) {
            s += 18;
        }
        if (low.contains("webview")) {
            s += 12;
        }
        // 微信/QQ 授权中转
        if (low.contains("wechatagent") || low.contains("wxentry") || low.contains("wxapi")) {
            s += 10;
        }
        // 第三方登录授权入口
        if (low.contains("authorize") || low.contains("authorityactivity")) {
            s += 8;
        }
        // QQ SDK 的两个壳：能收，但通常收完就结束，排在后面
        if (low.equals("com.tencent.tauth.authactivity")) {
            s += 6;
        }
        if (low.equals("com.tencent.connect.common.assistactivity")) {
            s += 5;
        }
        if (low.contains("msdk") || low.contains("itop") || low.contains("gcloud")) {
            s += 5;
        }
        if (low.contains("login")) {
            s += 4;
        }
        if (low.contains("auth")) {
            s += 4;
        }
        if (low.contains("qq") || low.contains("sso")) {
            s += 2;
        }
        return s;
    }

    /** 兼容旧调用点：只要游戏在前台就算成功。 */
    public static boolean dispatch(Context ctx, String gameName, String authUrl) {
        return dispatchEx(ctx, gameName, authUrl, "") >= RESULT_WOKE;
    }

    /**
     * 直接派发，返回成色（RESULT_NONE / RESULT_WOKE / RESULT_LANDED）。
     *
     * appid 用于拼 tencent&lt;appid&gt;://tauth 这种 QQ 回调形态。
     */
    public static int dispatchEx(Context ctx, String gameName, String authUrl, String appid) {
        String pkg = packageOf(gameName);
        if (pkg.isEmpty()) {
            LoginDiag.add("DISPATCH", "未配置「" + gameName + "」的包名，跳过");
            return RESULT_NONE;
        }
        if (authUrl == null || authUrl.isEmpty()) {
            LoginDiag.add("DISPATCH", "auth 数据为空");
            return RESULT_NONE;
        }
        if (!isInstalled(ctx, pkg)) {
            LoginDiag.add("DISPATCH", "游戏未安装: " + pkg);
            return RESULT_NONE;
        }

        String aid = cleanAppid(appid);
        if (aid.isEmpty()) {
            aid = cleanAppid(param(authUrl, "client_id"));
        }
        if (aid.isEmpty()) {
            aid = cleanAppid(param(authUrl, "appid"));
        }

        logChannel(ctx);

        List<String> all = activitiesOf(ctx, pkg);
        LoginDiag.add("DISPATCH", "游戏 Activity 共 " + all.size() + " 个");
        dumpActivitiesToFile(ctx, gameName, pkg, all, aid);

        boolean gameHasFilter = hasAuthFilter(ctx, pkg, authUrl);
        LoginDiag.add("DISPATCH", "游戏自己注册 auth://: " + (gameHasFilter ? "是" : "否")
                + " appid=" + (aid.isEmpty() ? "未知" : aid));

        List<String> forms = dataForms(authUrl, aid);
        LoginDiag.add("DISPATCH", "data 形态 " + forms.size() + " 种");

        // 0) 手动指定的组件优先
        String manual = manualComponent(ctx, gameName);
        if (!manual.isEmpty()) {
            int r = tryOne(ctx, pkg, manual, authUrl, forms, "0 手动组件");
            if (r == RESULT_LANDED) {
                return RESULT_LANDED;
            }
        }

        List<String> cands = findAuthActivities(ctx, pkg);
        LoginDiag.add("DISPATCH", "疑似登录组件 " + cands.size() + " 个: " + join(cands, 10));

        int tried = 0;
        for (String cls : cands) {
            if (tried >= MAX_TRIES) {
                LoginDiag.add("DISPATCH", "候选超过 " + MAX_TRIES + " 个，后面的不再尝试");
                break;
            }
            tried++;
            int r = tryOne(ctx, pkg, cls, authUrl, forms, "A");
            if (r == RESULT_LANDED) {
                LoginDiag.add("DISPATCH", "A 命中 " + cls);
                return RESULT_LANDED;
            }
        }

        // B) 主界面：只能把游戏唤到前台，token 不会被这里读取，
        // 所以它只算「唤醒了」，不能冒充交付成功。
        String main = mainActivity(ctx, pkg);
        if (!main.isEmpty()) {
            int r = tryMain(ctx, pkg, main, authUrl, forms);
            if (r >= RESULT_WOKE) {
                LoginDiag.add("DISPATCH", "B 只把游戏唤到前台，token 未被读取");
                return RESULT_WOKE;
            }
        } else {
            LoginDiag.add("DISPATCH", "B 找不到游戏主界面，跳过");
        }

        String out = runSu("am start -W -a android.intent.action.VIEW -d " + RootUtil.shellQuote(authUrl)
                + " -p " + pkg + " -f 0x10000000");
        LoginDiag.add("DISPATCH", "C 包内 VIEW 解析 -> " + oneLine(out));
        if (okAm(out) && waitForeground(pkg, 2)) {
            LoginDiag.add("DISPATCH", "C 命中");
            return RESULT_WOKE;
        }

        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
            it.setPackage(pkg);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            LoginDiag.add("DISPATCH", "D 普通 Intent 已发出");
            if (waitForeground(pkg, 2)) {
                return RESULT_WOKE;
            }
        } catch (Throwable t) {
            LoginDiag.add("DISPATCH", "D 普通 Intent 失败: " + t);
        }

        copyToClipboard(ctx, authUrl);
        boolean front = bringToFront(ctx, pkg);
        LoginDiag.add("DISPATCH", "全部方式失败，auth 已复制到剪贴板，切回游戏=" + front);
        return front ? RESULT_WOKE : RESULT_NONE;
    }

    /**
     * 对一个组件依次试各种 data 形态。
     *
     * 判定成色的依据是「启动后前台还在不在游戏里」：
     * LaunchState: UNKNOWN 且前台仍是 shq，说明组件收下就结束了，数据被丢弃；
     * 前台变成游戏的某个 Activity，才算真的落地。
     */
    private static int tryOne(Context ctx, String pkg, String cls, String authUrl,
                              List<String> forms, String tag) {
        for (int i = 0; i < forms.size(); i++) {
            String data = forms.get(i);
            String out = runSu(amStart(pkg, cls, authUrl, data));
            boolean accepted = okAm(out);
            boolean unknown = isLaunchUnknown(out);
            String who = shortName(pkg, cls) + " [" + (i == 0 ? "auth" : "tauth") + "]";
            if (!accepted) {
                LoginDiag.add("DISPATCH", tag + " " + who + " -> 被拒 " + oneLine(out));
                continue;
            }
            boolean front = waitForeground(pkg, 2);
            if (front) {
                LoginDiag.add("DISPATCH", tag + " " + who + " -> 落地，前台已是游戏");
                return RESULT_LANDED;
            }
            if (unknown) {
                LoginDiag.add("DISPATCH", tag + " " + who + " -> 收下即结束（LaunchState UNKNOWN），换下一个");
            } else {
                LoginDiag.add("DISPATCH", tag + " " + who + " -> 收到但游戏没起来 " + oneLine(out));
            }
        }
        return RESULT_NONE;
    }

    /** 主界面只负责唤醒，成功也只算 WOKE。 */
    private static int tryMain(Context ctx, String pkg, String main, String authUrl,
                               List<String> forms) {
        String data = forms.isEmpty() ? authUrl : forms.get(0);
        String out = runSu(amStart(pkg, main, authUrl, data));
        boolean accepted = okAm(out);
        LoginDiag.add("DISPATCH", "B 主界面 " + shortName(pkg, main) + " -> "
                + (accepted ? "已接收" : "被拒") + " " + oneLine(out));
        if (!accepted) {
            return RESULT_NONE;
        }
        return waitForeground(pkg, 3) ? RESULT_WOKE : RESULT_NONE;
    }

    /** "LaunchState: UNKNOWN" 表示起来就结束，等于没处理。 */
    private static boolean isLaunchUnknown(String out) {
        if (out == null) {
            return false;
        }
        return out.contains("LaunchState: UNKNOWN");
    }

    /** 候选 data 形态：原始 auth:// 与 QQ 真正用的 tencent&lt;appid&gt;://tauth。 */
    private static List<String> dataForms(String authUrl, String appid) {
        List<String> out = new ArrayList<String>();
        if (authUrl != null && !authUrl.isEmpty()) {
            out.add(authUrl);
        }
        String q = queryOf(authUrl);
        if (appid != null && !appid.isEmpty() && !q.isEmpty()) {
            out.add("tencent" + appid + "://tauth?#" + q);
        }
        return out;
    }

    private static String queryOf(String url) {
        if (url == null) {
            return "";
        }
        int i = url.indexOf("access_token=");
        if (i < 0) {
            return "";
        }
        return url.substring(i);
    }

    private static String param(String url, String key) {
        if (url == null || key == null) {
            return "";
        }
        String token = key + "=";
        int i = url.indexOf(token);
        if (i < 0) {
            return "";
        }
        int b = i + token.length();
        int e = b;
        while (e < url.length() && url.charAt(e) != '&' && url.charAt(e) != '#' && url.charAt(e) != ' ') {
            e++;
        }
        return url.substring(b, e);
    }

    private static String cleanAppid(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch >= '0' && ch <= '9') {
                sb.append(ch);
            } else {
                break;
            }
        }
        return sb.length() >= 5 ? sb.toString() : "";
    }

    /** AssistActivity 读 key_response，这里按 QQ SDK 的字段拼一份。 */
    private static String keyResponse(String authUrl) {
        String at = param(authUrl, "access_token");
        if (at.isEmpty()) {
            return "";
        }
        String exp = param(authUrl, "expires_in");
        if (exp.isEmpty()) {
            exp = "7776000";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ret\":\"0\"");
        sb.append(",\"openid\":\"").append(param(authUrl, "openid")).append('"');
        sb.append(",\"access_token\":\"").append(at).append('"');
        sb.append(",\"pay_token\":\"").append(param(authUrl, "pay_token")).append('"');
        sb.append(",\"expires_in\":\"").append(exp).append('"');
        sb.append(",\"pf\":\"desktop_m_qq-10000144-android-2002-\"");
        sb.append(",\"page_type\":\"1\"}");
        return sb.toString();
    }

    private static String manualComponent(Context ctx, String gameName) {
        if (ctx == null || gameName == null) {
            return "";
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences("myPrefs", Context.MODE_PRIVATE);
            String v = sp.getString("dispatch_class_" + gameName.trim(), "");
            if (v == null) {
                return "";
            }
            v = v.trim();
            int slash = v.indexOf('/');
            if (slash > 0) {
                v = v.substring(slash + 1).trim();
            }
            if (!v.isEmpty()) {
                LoginDiag.add("DISPATCH", "使用手动指定组件: " + v);
            }
            return v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** getCallingActivity 只记录，不作为成败判断 —— 它不代表对方在等结果。 */
    private static void logChannel(Context ctx) {
        if (!(ctx instanceof Activity)) {
            LoginDiag.add("DISPATCH", "调用方不是 Activity，无法判断结果通道");
            return;
        }
        ComponentName c = null;
        try {
            c = ((Activity) ctx).getCallingActivity();
        } catch (Throwable ignored) {
        }
        LoginDiag.add("DISPATCH", "getCallingActivity=" + (c == null ? "null" : c.flattenToShortString())
                + "（只作参考，不代表游戏在等结果）");
    }

    private static boolean hasAuthFilter(Context c, String pkg, String authUrl) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
            it.setPackage(pkg);
            List<ResolveInfo> rs = c.getPackageManager().queryIntentActivities(it, 0);
            return rs != null && !rs.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String amStart(String pkg, String cls, String authUrl, String data) {
        StringBuilder sb = new StringBuilder("am start -W");
        if (cls != null && !cls.isEmpty()) {
            sb.append(" -n ").append(pkg).append('/').append(cls);
        } else {
            sb.append(" -a android.intent.action.VIEW -p ").append(pkg);
        }
        if (data != null && !data.isEmpty()) {
            sb.append(" -d ").append(RootUtil.shellQuote(data));
        }
        sb.append(" --es auth ").append(RootUtil.shellQuote(authUrl));
        String kr = keyResponse(authUrl);
        if (!kr.isEmpty()) {
            sb.append(" --es key_response ").append(RootUtil.shellQuote(kr));
        }
        sb.append(" -f 0x30000000");
        return sb.toString();
    }

    /** am -W 的成功判定：有 Status: ok 或 Activity: 才算投递成功。 */
    private static boolean okAm(String out) {
        if (out == null || out.isEmpty()) {
            return false;
        }
        String low = out.toLowerCase(Locale.ROOT);
        if (low.contains("error") || low.contains("exception") || low.contains("unable to resolve")
                || low.contains("does not exist") || low.contains("permission denial")
                || low.contains("not exported")) {
            return false;
        }
        return low.contains("status: ok") || low.contains("activity: ");
    }

    private static boolean waitForeground(String pkg, int tries) {
        for (int i = 0; i < tries; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(350);
                } catch (InterruptedException ignored) {
                }
            }
            if (gameForeground(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean gameForeground(String pkg) {
        String dump = runSu("dumpsys window | grep -m 1 -E 'mCurrentFocus|mFocusedApp'");
        String comp = componentOf(dump);
        if (comp.isEmpty()) {
            dump = runSu("dumpsys activity activities | grep -m 1 -E 'topResumedActivity|mResumedActivity'");
            comp = componentOf(dump);
        }
        boolean hit = !comp.isEmpty() && comp.startsWith(pkg + "/");
        LoginDiag.add("DISPATCH", "前台: " + (comp.isEmpty() ? "未知" : comp)
                + (hit ? " ✓" : ""));
        return hit;
    }

    private static String componentOf(String dump) {
        if (dump == null || dump.isEmpty()) {
            return "";
        }
        int i = 0;
        while (true) {
            int slash = dump.indexOf('/', i);
            if (slash < 0) {
                return "";
            }
            int b = slash;
            while (b > 0 && isCompChar(dump.charAt(b - 1))) {
                b--;
            }
            int e = slash + 1;
            while (e < dump.length() && isCompChar(dump.charAt(e))) {
                e++;
            }
            String left = dump.substring(b, slash);
            String right = dump.substring(slash + 1, e);
            if (left.indexOf('.') > 0 && !right.isEmpty() && right.charAt(0) != '0') {
                return left + "/" + right;
            }
            i = slash + 1;
        }
    }

    private static boolean isCompChar(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '$' || ch == '-';
    }

    public static String mainActivity(Context c, String pkg) {
        if (c == null || pkg == null || pkg.isEmpty()) {
            return "";
        }
        try {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.addCategory(Intent.CATEGORY_LAUNCHER);
            it.setPackage(pkg);
            List<ResolveInfo> rs = c.getPackageManager().queryIntentActivities(it, 0);
            if (rs != null && !rs.isEmpty()) {
                ResolveInfo ri = rs.get(0);
                if (ri.activityInfo != null && ri.activityInfo.name != null) {
                    return ri.activityInfo.name;
                }
            }
        } catch (Throwable ignored) {
        }
        for (String cls : activitiesOf(c, pkg)) {
            String low = cls.toLowerCase(Locale.ROOT);
            if (low.contains("splash") || low.endsWith(".main") || low.endsWith(".mainactivity")) {
                return cls;
            }
        }
        return "";
    }

    public static boolean bringToFront(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) {
            return false;
        }
        String main = mainActivity(ctx, pkg);
        if (!main.isEmpty()) {
            String out = runSu("am start -W -n " + pkg + "/" + main + " -f 0x10000000");
            LoginDiag.add("DISPATCH", "切回游戏 " + shortName(pkg, main) + " -> " + oneLine(out));
            if (waitForeground(pkg, 3)) {
                return true;
            }
        }
        try {
            Intent it = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (it != null) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
                LoginDiag.add("DISPATCH", "已用启动 Intent 切回 " + pkg);
                return true;
            }
        } catch (Throwable t) {
            LoginDiag.add("DISPATCH", "切回游戏失败: " + t);
        }
        return false;
    }

    private static void dumpActivitiesToFile(Context ctx, String gameName, String pkg,
                                            List<String> all, String appid) {
        if (ctx == null || all == null || all.isEmpty()) {
            return;
        }
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            dir = ctx.getFilesDir();
        }
        if (dir == null) {
            return;
        }
        File f = new File(dir, "dispatch_" + (gameName == null ? "game" : gameName.trim()) + ".txt");
        FileOutputStream fos = null;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(gameName).append("  ").append(pkg)
                    .append("  appid=").append(appid).append(LF);
            sb.append("# 共 ").append(all.size()).append(" 个 Activity").append(LF).append(LF);
            List<String> ranked = findAuthActivities(ctx, pkg);
            if (!ranked.isEmpty()) {
                sb.append("## 疑似登录组件（自动尝试的顺序）").append(LF);
                for (String s : ranked) {
                    sb.append(s).append(LF);
                }
                sb.append(LF);
            }
            sb.append("## 全部 Activity").append(LF);
            for (String s : all) {
                sb.append(s).append(LF);
            }
            fos = new FileOutputStream(f, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable t) {
            LoginDiag.add("DISPATCH", "写组件清单失败: " + t);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void copyToClipboard(Context c, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("auth", text));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String shortName(String pkg, String cls) {
        if (cls == null) {
            return "";
        }
        if (cls.startsWith(pkg + ".")) {
            return cls.substring(pkg.length());
        }
        return cls;
    }

    private static String join(List<String> list, int max) {
        if (list == null || list.isEmpty()) {
            return "(空)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < max; i++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(shortName("", list.get(i)));
        }
        if (list.size() > max) {
            sb.append(" ...");
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace(LF, ' ').replace(CR, ' ').trim();
        int i = t.indexOf("LaunchState");
        if (i > 0) {
            int end = t.indexOf("Activity:", i);
            if (end > i) {
                t = t.substring(0, i).trim() + " " + t.substring(end).trim();
            }
        }
        if (t.length() > 180) {
            t = t.substring(0, 180) + "...";
        }
        return t.isEmpty() ? "(无输出)" : t;
    }

    private static String runSu(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(cmd + " 2>&1");
            os.write(LF);
            os.writeBytes("exit");
            os.write(LF);
            os.flush();
            os.close();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) {
                sb.append(ln).append(LF);
            }
            br.close();
            try {
                p.waitFor();
            } catch (InterruptedException ignored) {
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "执行异常: " + e;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    private static class Hit {
        final String name;
        final int score;

        Hit(String n, int s) {
            name = n;
            score = s;
        }
    }

    private static class HitCmp implements Comparator<Hit> {
        public int compare(Hit a, Hit b) {
            if (a.score != b.score) {
                return b.score - a.score;
            }
            return a.name.compareTo(b.name);
        }
    }
}
