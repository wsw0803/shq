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
 * 2026-10-25 第六次修订。上一份日志说明了两件关键的事：
 *
 * 一、黑屏的成因：启动了游戏的内部界面。
 *
 *   前台: com.tencent.tmgp.dfm/...pixui.webview.WebViewActivity ✓
 *   WindowManager: ...WebViewActivity state from DRAW_PENDING to NO_SURFACE; destroySurface
 *   AppSenseClient: ...WebViewActivity; state: 0; event: GAME ... exit
 *
 *   MSDK 的 WebViewActivity 是「内部页」，靠 Intent 里的 url / title 等参数渲染。
 *   我们只给了 auth 数据，它拿不到 url，起来就 destroySurface 退出，
 *   留下一个空任务栈 → 黑屏。所以这类内部页一律不碰。
 *
 * 二、真正该走的路：把结果交回调用方。
 *
 *   getCallingActivity = com.tencent.tmgp.dfm/com.tencent.connect.common.AssistActivity
 *
 *   这不是 ColorOS 伪造的（伪造时调用方还是自己或别的包）。AssistActivity
 *   是 QQ SDK 里负责收结果、回调 IUiListener 的组件 —— 它确实在
 *   startActivityForResult 等我们。这条通道存在时，正确做法就是
 *   setResult + finish，一个多余界面都别启动。
 *
 * 所以本类的职责缩小为「没有真实通道时的兜底」：
 *   只试真正接收 auth 的回调组件（AuthActivity / AuthorityActivity /
 *   AssistActivity / AuthorizeActivity），内部 UI 页（webview、wechat、
 *   douyin、pay、permission、policy、qrcode）全部排除。
 *   每次启动后还要确认前台稳住了，起来就退的一律换下一个，避免再黑屏。
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

    /** 候选上限。回调组件一般只有两三个，留点余量即可。 */
    private static final int MAX_TRIES = 4;

    private static final char LF = (char) 10;
    private static final char CR = (char) 13;

    /** 派发结果的成色。 */
    public static final int RESULT_NONE = 0;
    /** 只是把游戏唤到前台，token 未必被读取。 */
    public static final int RESULT_WOKE = 1;
    /** 组件收下且前台稳住，token 很可能已被消费。 */
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

    /**
     * 内部 UI 页：靠 Intent 里的 url / title 参数渲染，我们给不出，
     * 起来就 destroySurface 退出并留下空任务栈 —— 黑屏就是这么来的。
     */
    private static boolean isInternalUi(String low) {
        return low.contains("webview") || low.contains("wechat") || low.contains("wxapi")
                || low.contains("wxentry") || low.contains("douyin") || low.contains("authorize_douyin")
                || low.contains("pay") || low.contains("permission") || low.contains("policy")
                || low.contains("qrcode") || low.contains("splash") || low.contains("downloader")
                || low.contains("gameactivity") || low.contains("tpush");
    }

    /** 真正接收 auth 回调的组件，按可能性从高到低。 */
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
     * 打分。只留回调类组件，内部 UI 页直接淘汰。
     */
    private static int score(String cls) {
        if (cls == null || cls.isEmpty()) {
            return -1;
        }
        String low = cls.toLowerCase(Locale.ROOT);
        if (low.contains("provider") || low.contains("receiver") || low.contains("service")
                || low.contains("proxy") || low.contains("stub")) {
            return -1;
        }
        if (isInternalUi(low)) {
            return -1;
        }
        if (low.equals("com.tencent.tauth.authactivity")) {
            return 20;
        }
        if (low.equals("com.tencent.connect.common.assistactivity")) {
            return 18;
        }
        if (low.contains("authorityactivity")) {
            return 16;
        }
        if (low.contains("authorize")) {
            return 14;
        }
        if (low.contains("auth")) {
            return 10;
        }
        if (low.contains("msdk") || low.contains("itop") || low.contains("gcloud")) {
            return 4;
        }
        return 0;
    }

    /** 兼容旧调用点：只要游戏在前台就算成功。 */
    public static boolean dispatch(Context ctx, String gameName, String authUrl) {
        return dispatchEx(ctx, gameName, authUrl, "") >= RESULT_WOKE;
    }

    /**
     * 直接派发，返回成色（RESULT_NONE / RESULT_WOKE / RESULT_LANDED）。
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

        List<String> all = activitiesOf(ctx, pkg);
        LoginDiag.add("DISPATCH", "游戏 Activity 共 " + all.size() + " 个");
        dumpActivitiesToFile(ctx, gameName, pkg, all, aid);
        LoginDiag.add("DISPATCH", "游戏自己注册 auth://: "
                + (hasAuthFilter(ctx, pkg, authUrl) ? "是" : "否") + " appid="
                + (aid.isEmpty() ? "未知" : aid));

        List<String> forms = dataForms(authUrl, aid);
        LoginDiag.add("DISPATCH", "data 形态 " + forms.size() + " 种");

        String manual = manualComponent(ctx, gameName);
        if (!manual.isEmpty()) {
            int r = tryOne(ctx, pkg, manual, authUrl, forms, "0 手动组件");
            if (r == RESULT_LANDED) {
                return RESULT_LANDED;
            }
        }

        List<String> cands = findAuthActivities(ctx, pkg);
        LoginDiag.add("DISPATCH", "aoth 回调组件 " + cands.size() + " 个: " + join(cands, 8));

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

        // 走到这里说明没有组件能真正消费 auth。
        // 只把游戏唤回前台，绝不启动它的内部界面 —— 那是黑屏的来源。
        boolean front = bringToFront(ctx, pkg);
        if (front) {
            LoginDiag.add("DISPATCH", "没有可用的回调组件，仅把游戏唤回前台");
            copyToClipboard(ctx, authUrl);
            return RESULT_WOKE;
        }

        copyToClipboard(ctx, authUrl);
        LoginDiag.add("DISPATCH", "全部方式失败，auth 已复制到剪贴板");
        return RESULT_NONE;
    }

    /**
     * 对一个组件依次试各种 data 形态。
     *
     * 落地后还要再等一会儿确认前台稳住：MSDK 内部页起来会 destroySurface 退出，
     * 那种必须视为失败并换下一个，否则用户看到的就是黑屏。
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
            if (!waitForeground(pkg, 2)) {
                LoginDiag.add("DISPATCH", tag + " " + who
                        + (unknown ? " -> 收下即结束，换下一个" : " -> 收到但游戏没起来"));
                continue;
            }
            // 前台虽然在游戏内，但要确认它不是「起来就退」的临时页
            if (stillFront(pkg, 700)) {
                LoginDiag.add("DISPATCH", tag + " " + who + " -> 落地且前台稳住");
                return RESULT_LANDED;
            }
            LoginDiag.add("DISPATCH", tag + " " + who + " -> 起来后立刻退出（黑屏风险），换下一个");
            bringToFront(ctx, pkg);
        }
        return RESULT_NONE;
    }

    /** 已经在游戏内，过一会儿再确认一次，避免把「一闪而过」当成成功。 */
    private static boolean stillFront(String pkg, long waitMs) {
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ignored) {
        }
        return gameForeground(pkg);
    }

    /** "LaunchState: UNKNOWN" 表示起来就结束，等于没处理。 */
    private static boolean isLaunchUnknown(String out) {
        return out != null && out.contains("LaunchState: UNKNOWN");
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
    public static String keyResponse(String authUrl) {
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
        LoginDiag.add("DISPATCH", "前台: " + (comp.isEmpty() ? "未知" : comp) + (hit ? " ✓" : ""));
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
                sb.append("## 疑似登录回调组件（自动尝试的顺序）").append(LF);
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
        if (pkg != null && !pkg.isEmpty() && cls.startsWith(pkg + ".")) {
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
