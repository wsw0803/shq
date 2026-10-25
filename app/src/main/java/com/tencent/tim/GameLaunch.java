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
 * 上一版为什么必然失败（2026-10-25 07:49 诊断日志）：
 *
 *   [DISPATCH] 找到候选组件 3 个:
 *     ...pixui.wechat.WeChatFileProvider: , ...SystemFileProvider: , ...qq.QQFileProvider:
 *   [DISPATCH] 精确启动 ... Error: Activity class {...} does not exist.
 *
 * 三个候选全是 ContentProvider（FileProvider 是 provider，不是 Activity），
 * 是从 dumpsys 文本里按 pkg/Class 硬切出来的，连名字末尾的冒号都留着。
 * am start -n 必然报 does not exist，三次机会全废，后面两条策略跟着废。
 * 真正的登录组件根本没被找到。
 *
 * 现在的策略：
 *
 * 1) 用 PackageManager 拿真实 Activity 列表，provider / service / receiver 混不进来；
 *    PackageManager 被包可见性拦住时退回 dumpsys，但只接受类名以 Activity 结尾的条目。
 * 2) 优先识别腾讯 SDK 的真正回调入口：
 *    com.tencent.tauth.AuthActivity、com.tencent.connect.common.AssistActivity、
 *    com.tencent.open.agent.AuthorityActivity —— 这三个才是 QQ 登录结果的落点，
 *    比「名字里带 msdk 的随便什么页面」可靠得多。
 * 3) 每个组件试两种 data 形态：
 *    原始 auth://tauth.qq.com/?#...
 *    以及 tencent<appid>://tauth?#...  —— 这是 QQ 回调游戏时真正用的 scheme。
 *    同时附带 auth / key_response / appid 三个 extra，
 *    对应 QQ SDK AssistActivity 读 key_response 的那条分支。
 * 4) am start 一律带 -W，用「Status: ok 或 Activity:」判定，不再靠「没输出就是成功」；
 *    启动后必须确认游戏真的到了前台，才算这条路径成功。
 * 5) 全失败：auth 复制到剪贴板并把游戏切回前台，token 不丢、人不被困在 shq。
 * 6) 把游戏的完整 Activity 清单落到 dispatch_<游戏>.txt，方便手动挑组件。
 *
 * 手动指定组件（不用重新打包）：
 *   在 AgentActivity 上号界面长按「执行登录」选一个，
 *   或者直接往 myPrefs 写 dispatch_class_<游戏名> = 完整类名（或 包名/类名）。
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

    /** 最多试几个候选组件，避免把时间耗在几十个 msdk 页面上。 */
    private static final int MAX_TRIES = 3;

    private static final char LF = (char) 10;
    private static final char CR = (char) 13;

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
        if (!out.isEmpty()) {
            LoginDiag.add("DISPATCH", "PackageManager 无数据，dumpsys 兜底找到 " + out.size() + " 个 Activity");
        }
        return out;
    }

    /** 疑似接收 auth 的登录组件，按可能性从高到低，已导出的优先。 */
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
     * provider / receiver / service / stub 直接淘汰（-1）；
     * 腾讯 SDK 的几个已知回调入口给最高分，其余按关键词累加。
     */
    private static int score(String cls) {
        if (cls == null || cls.isEmpty()) {
            return -1;
        }
        String low = cls.toLowerCase(Locale.ROOT);
        if (low.contains("provider") || low.contains("receiver") || low.contains("service")
                || low.contains("stub") || low.contains("proxy")) {
            return -1;
        }
        if (low.equals("com.tencent.tauth.authactivity")) {
            return 14;
        }
        if (low.equals("com.tencent.connect.common.assistactivity")) {
            return 13;
        }
        if (low.contains("open.agent.authorityactivity")) {
            return 11;
        }
        int s = 0;
        if (low.contains("auth")) {
            s += 6;
        }
        if (low.contains("assist")) {
            s += 5;
        }
        if (low.contains("msdk")) {
            s += 5;
        }
        if (low.contains("itop") || low.contains("gcloud")) {
            s += 5;
        }
        if (low.contains("open.agent") || low.contains("tencent.open")) {
            s += 4;
        }
        if (low.contains("login") || low.contains("logina")) {
            s += 4;
        }
        if (low.contains("qq")) {
            s += 3;
        }
        if (low.contains("sso") || low.contains("callback")) {
            s += 3;
        }
        if (low.contains("web")) {
            s += 1;
        }
        return s;
    }

    public static boolean dispatch(Context ctx, String gameName, String authUrl) {
        return dispatch(ctx, gameName, authUrl, "");
    }

    /**
     * 直接派发。appid 用于拼 tencent&lt;appid&gt;://tauth 这种 QQ 回调形态。
     * 返回 true 表示已经尽力交付（游戏被拉起 / token 已在剪贴板）。
     */
    public static boolean dispatch(Context ctx, String gameName, String authUrl, String appid) {
        String pkg = packageOf(gameName);
        if (pkg.isEmpty()) {
            LoginDiag.add("DISPATCH", "未配置「" + gameName + "」的包名，跳过");
            return false;
        }
        if (authUrl == null || authUrl.isEmpty()) {
            LoginDiag.add("DISPATCH", "auth 数据为空");
            return false;
        }
        if (!isInstalled(ctx, pkg)) {
            LoginDiag.add("DISPATCH", "游戏未安装: " + pkg);
            return false;
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
        LoginDiag.add("DISPATCH", "游戏 Activity 共 " + all.size() + " 个: " + join(all, 30));
        dumpActivitiesToFile(ctx, gameName, pkg, all, aid);

        boolean gameHasFilter = hasAuthFilter(ctx, pkg, authUrl);
        LoginDiag.add("DISPATCH", "游戏自己注册 auth://: " + (gameHasFilter ? "是" : "否")
                + " appid=" + (aid.isEmpty() ? "未知" : aid));

        List<String> forms = dataForms(authUrl, aid);
        LoginDiag.add("DISPATCH", "data 形态 " + forms.size() + " 种");

        // 0) 手动指定的组件优先
        String manual = manualComponent(ctx, gameName);
        if (!manual.isEmpty()) {
            if (tryOne(ctx, pkg, manual, authUrl, forms, "0 手动组件")) {
                return true;
            }
        }

        List<String> cands = findAuthActivities(ctx, pkg);
        LoginDiag.add("DISPATCH", "疑似登录组件 " + cands.size() + " 个: " + join(cands, 8));

        int tried = 0;
        for (String cls : cands) {
            if (tried >= MAX_TRIES) {
                LoginDiag.add("DISPATCH", "候选太多，只试了前 " + MAX_TRIES + " 个");
                break;
            }
            tried++;
            if (tryOne(ctx, pkg, cls, authUrl, forms, "A 精确启动")) {
                LoginDiag.add("DISPATCH", "A 命中 " + cls);
                return true;
            }
        }

        String main = mainActivity(ctx, pkg);
        if (main.isEmpty()) {
            LoginDiag.add("DISPATCH", "B 找不到游戏主界面，跳过");
        } else if (tryOne(ctx, pkg, main, authUrl, forms, "B 主界面")) {
            LoginDiag.add("DISPATCH", "B auth 已随 Intent 送进游戏主界面");
            return true;
        }

        String out = runSu("am start -W -a android.intent.action.VIEW -d " + RootUtil.shellQuote(authUrl)
                + " -p " + pkg + " -f 0x10000000");
        LoginDiag.add("DISPATCH", "C 包内 VIEW 解析 -> " + oneLine(out));
        if (okAm(out) && waitForeground(pkg, 2)) {
            LoginDiag.add("DISPATCH", "C 命中");
            return true;
        }

        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
            it.setPackage(pkg);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            LoginDiag.add("DISPATCH", "D 普通 Intent 已发出");
            if (waitForeground(pkg, 2)) {
                return true;
            }
        } catch (Throwable t) {
            LoginDiag.add("DISPATCH", "D 普通 Intent 失败: " + t);
        }

        copyToClipboard(ctx, authUrl);
        boolean front = bringToFront(ctx, pkg);
        LoginDiag.add("DISPATCH", "全部方式失败，auth 已复制到剪贴板，切回游戏=" + front);
        return front;
    }

    /** 对一个组件依次试各种 data 形态，命中即返回。 */
    private static boolean tryOne(Context ctx, String pkg, String cls, String authUrl,
                                  List<String> forms, String tag) {
        for (int i = 0; i < forms.size(); i++) {
            String data = forms.get(i);
            String out = runSu(amStart(pkg, cls, authUrl, data, ctx));
            boolean started = okAm(out);
            LoginDiag.add("DISPATCH", tag + " " + shortName(pkg, cls)
                    + " [" + (i == 0 ? "auth" : "tauth") + "] -> "
                    + (started ? "已接收" : "被拒") + " " + oneLine(out));
            if (started && waitForeground(pkg, 2)) {
                return true;
            }
        }
        return false;
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

    /** 取 access_token 开头那一段查询串。 */
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

    /** 手动指定组件：myPrefs 的 dispatch_class_<游戏名>。支持 类名 或 包名/类名。 */
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

    /** 结果回传通道只看 getCallingActivity，referrer 有值不代表有通道。 */
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
        LoginDiag.add("DISPATCH", "结果回传通道: " + (c == null
                ? "无（对方用 startActivity / NEW_TASK 唤起，setResult 到不了游戏）"
                : c.flattenToShortString() + "（startActivityForResult）"));
    }

    /** 游戏是否自己注册了 auth:// 的 intent-filter。 */
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

    /** 拼 am start 命令。cls 为空时退化成包内 VIEW 解析。 */
    private static String amStart(String pkg, String cls, String authUrl, String data, Context ctx) {
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

    /** am -W 的成功判定：有 Status: ok 或 Activity: 才算成功，出现 Error 一律算失败。 */
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

    /** 轮询等游戏到前台。 */
    private static boolean waitForeground(String pkg, int tries) {
        for (int i = 0; i < tries; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
            if (gameForeground(pkg)) {
                return true;
            }
        }
        return false;
    }

    /** 当前前台是不是这个游戏。 */
    private static boolean gameForeground(String pkg) {
        String dump = runSu("dumpsys window | grep -m 1 -E 'mCurrentFocus|mFocusedApp'");
        String comp = componentOf(dump);
        if (comp.isEmpty()) {
            dump = runSu("dumpsys activity activities | grep -m 1 -E 'topResumedActivity|mResumedActivity'");
            comp = componentOf(dump);
        }
        LoginDiag.add("DISPATCH", "当前前台: " + (comp.isEmpty() ? "未知" : comp));
        return !comp.isEmpty() && comp.startsWith(pkg + "/");
    }

    /** 从 dumpsys 文本里挑出形如 com.pkg/.Act 的组件名。 */
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

    /** 游戏主界面：先用 MAIN/LAUNCHER 查，查不到再按类名猜。 */
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

    /** 只把游戏切回前台，不送 token。 */
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

    /** 把活动清单写到文件，方便下次手动挑组件。 */
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
                sb.append("## 疑似登录组件（优先试这些）").append(LF);
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
            LoginDiag.add("DISPATCH", "组件清单已写入 " + f.getAbsolutePath());
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
            sb.append(list.get(i));
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
        if (t.length() > 300) {
            t = t.substring(0, 300) + "...";
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
