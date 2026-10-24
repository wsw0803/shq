package com.tencent.tim;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 token 直接派发给目标游戏。
 *
 * 为什么必须这么做：
 * 有的游戏用 startActivity + NEW_TASK 唤起本应用（referrer 有值），
 * 而不是 startActivityForResult，结果根本没有回传通道，
 * setResult 无论怎么改都到不了游戏。三角洲就是这种情况。
 *
 * 所以只能用 root 主动把 auth 交给游戏。派发策略按可靠性排序：
 *
 * A) 用 dumpsys 列出游戏里真实存在的组件，挑出像登录回调的那个，
 *    用 am start -n 精确启动。这条路最稳，因为不靠猜类名。
 * B) 用 am start -a android.intent.action.VIEW -d auth -p <包名>，
 *    让系统在游戏内部解析，适合游戏自己注册了 auth:// 的情况。
 * C) 普通 Intent + setPackage，作为无 root 时的最后手段。
 *
 * 每一步都写诊断，方便判断卡在哪。
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

    public static String packageOf(String gameName) {
        if (gameName == null) return "";
        String p = PACKAGES.get(gameName.trim());
        return p == null ? "" : p;
    }

    public static boolean hasPackage(String gameName) {
        return !packageOf(gameName).isEmpty();
    }

    /** 游戏是否已安装。 */
    public static boolean isInstalled(Context c, String pkg) {
        if (c == null || pkg == null || pkg.isEmpty()) return false;
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 从 dumpsys package 里捞出该游戏所有 Activity 组件，
     * 再按关键词筛出可能是登录回调的那些。
     *
     * 关键词覆盖腾讯系常见命名：open.agent / auth / login / msdk / assist。
     */
    public static List<String> findAuthComponents(String pkg) {
        List<String> out = new ArrayList<String>();
        if (pkg == null || pkg.isEmpty()) return out;

        String dump = runSu("dumpsys package " + pkg);
        if (dump.isEmpty()) return out;

        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        String[] lines = dump.split("\n");
        for (String raw : lines) {
            String ln = raw.trim();
            // 典型行：com.xxx.yyy/ClassName filter ...
            int slash = ln.indexOf('/');
            if (slash <= 0) continue;
            String left = ln.substring(0, slash).trim();
            if (!left.equals(pkg)) continue;
            String right = ln.substring(slash + 1).trim();
            int sp = right.indexOf(' ');
            if (sp > 0) right = right.substring(0, sp);
            if (right.isEmpty() || right.contains("{")) continue;

            String full = right.startsWith(".") ? (pkg + right) : right;
            String low = full.toLowerCase();
            if (low.contains("open.agent") || low.contains("authority")
                    || low.contains(".auth") || low.contains("logina")
                    || low.contains("msdk") || low.contains("assist")
                    || low.contains("qqauth") || low.contains("tencent.open")) {
                set.add(full);
            }
        }
        out.addAll(set);
        return out;
    }

    /**
     * 直接派发。返回是否成功交出去。
     * 依次尝试：精确组件 → 包内 VIEW 解析 → 普通 Intent。
     */
    public static boolean dispatch(Context ctx, String gameName, String authUrl) {
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

        // A) 精确组件
        List<String> cands = findAuthComponents(pkg);
        if (cands.isEmpty()) {
            LoginDiag.add("DISPATCH", "未在 " + pkg + " 中找到疑似登录组件，转用包内 VIEW 解析");
        } else {
            LoginDiag.add("DISPATCH", "找到候选组件 " + cands.size() + " 个: " + join(cands));
            for (String cls : cands) {
                String out = runSu("am start -n " + pkg + "/" + cls
                        + " -d " + RootUtil.shellQuote(authUrl)
                        + " -f 0x10000000");
                LoginDiag.add("DISPATCH", "精确启动 " + cls + " → " + oneLine(out));
                if (ok(out)) {
                    LoginDiag.add("DISPATCH", "已通过精确组件交给游戏");
                    return true;
                }
            }
        }

        // B) 包内 VIEW 解析：不指定类名，只限定包名，让系统自己在游戏里找
        String cmd = "am start -a android.intent.action.VIEW"
                + " -d " + RootUtil.shellQuote(authUrl)
                + " -p " + pkg
                + " -f 0x10000000";
        String out = runSu(cmd);
        LoginDiag.add("DISPATCH", "包内 VIEW 解析 → " + oneLine(out));
        if (ok(out)) {
            LoginDiag.add("DISPATCH", "已通过包内 VIEW 交给游戏");
            return true;
        }

        // C) 普通 Intent
        LoginDiag.add("DISPATCH", "su 方式都失败，改用普通 Intent");
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
            it.setPackage(pkg);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            LoginDiag.add("DISPATCH", "普通 Intent 已发出");
            return true;
        } catch (Throwable t) {
            LoginDiag.add("DISPATCH", "普通 Intent 失败: " + t);
            return false;
        }
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < 6; i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        if (list.size() > 6) sb.append(" ...");
        return sb.toString();
    }

    /** 判断 am 输出像不像成功。 */
    private static boolean ok(String out) {
        if (out == null || out.isEmpty()) return true; // 多数 ROM 成功时无输出
        String low = out.toLowerCase();
        if (low.contains("error") || low.contains("exception")
                || low.contains("does not exist") || low.contains("unable to resolve")
                || low.contains("permission denial") || low.contains("not exported")) {
            return false;
        }
        return true;
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() > 260) t = t.substring(0, 260) + "...";
        return t.isEmpty() ? "(无输出，通常表示成功)" : t;
    }

    private static String runSu(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();

            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln).append('\n');
            br.close();
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while ((ln = er.readLine()) != null) sb.append(ln).append('\n');
            er.close();
            try { p.waitFor(); } catch (Exception ignored) {}
            return sb.toString().trim();
        } catch (Exception e) {
            return "执行异常: " + e;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
