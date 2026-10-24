package com.tencent.tim;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 把 token 派发给目标游戏。
 *
 * 两条路：
 * A) 正常路径：游戏通过 auth:// 唤起本应用时，setResult 会把 token 交回游戏。
 *    这条路的成败取决于 Manifest 里 AgentActivity 不能用 singleTask
 *    （singleTask 会让结果被系统丢弃）。已在 Manifest 修正。
 *
 * B) 兜底路径：直接启动游戏内自带的登录回调组件。
 *    不同游戏组件名不同，甚至可能不导出，所以这里先探测再启动，
 *    探测不到就明确报错，不盲猜。
 *
 * 本次修复的两个具体 bug：
 * 1) am start 的 --activity-new-task 在 Android 12+ 不认识，
 *    会抛 IllegalArgumentException: Unknown option，
 *    改用 -f 0x10000000（FLAG_ACTIVITY_NEW_TASK）。
 * 2) 原来直接把类名拼成 com.tencent.open.agent.AgentActivity，
 *    但游戏里该组件未必存在或未必叫这个名字，
 *    报 ActivityNotFoundException。现在先探测候选组件。
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

    /**
     * 常见的 QQ 登录回调组件名。
     * 不同游戏/SDK 版本不一样，逐个探测，存在才用。
     */
    private static final String[] AGENT_CLASSES = {
            "com.tencent.open.agent.AgentActivity",
            "com.tencent.open.agent.AuthorityActivity",
            "com.tencent.connect.common.AssistActivity",
            "com.tencent.tauth.AuthActivity"
    };

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
     * 探测游戏内可用的登录回调组件。返回组件全名，找不到返回空。
     */
    public static String findAgentComponent(Context c, String pkg) {
        if (c == null || pkg == null || pkg.isEmpty()) return "";
        PackageManager pm = c.getPackageManager();
        for (String cls : AGENT_CLASSES) {
            try {
                Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("auth://tauth.qq.com/"));
                probe.setClassName(pkg, cls);
                ComponentName cn = probe.resolveActivity(pm);
                if (cn != null && pkg.equals(cn.getPackageName())) {
                    return cls;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    /**
     * 尝试直接派发。返回是否成功启动。
     * 每一步都写入诊断日志。
     */
    public static boolean dispatch(Context ctx, String gameName, String authUrl) {
        String pkg = packageOf(gameName);
        if (pkg.isEmpty()) {
            LoginDiag.add("DISPATCH", "未配置「" + gameName + "」的包名，跳过直接派发");
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

        String cls = findAgentComponent(ctx, pkg);
        if (cls.isEmpty()) {
            LoginDiag.add("DISPATCH", "游戏内未找到可用的登录回调组件（试过 "
                    + AGENT_CLASSES.length + " 个候选名），只能依赖 setResult");
            return false;
        }

        LoginDiag.add("DISPATCH", "目标包名=" + pkg + " 组件=" + cls);

        // 用 -f 0x10000000 表示 FLAG_ACTIVITY_NEW_TASK。
        // 不能用 --activity-new-task，Android 12+ 会报 Unknown option。
        String cmd = "am start -n " + pkg + "/" + cls
                + " -d " + RootUtil.shellQuote(authUrl)
                + " -f 0x10000000";
        String out = runSu(cmd);
        LoginDiag.add("DISPATCH", "su am start 输出: " + (out.isEmpty() ? "(空)" : oneLine(out)));

        String low = out.toLowerCase();
        boolean ok = !low.contains("error")
                && !low.contains("exception")
                && !low.contains("does not exist")
                && !low.contains("unable to resolve");
        if (ok) {
            LoginDiag.add("DISPATCH", "已交给游戏处理");
            return true;
        }

        // 退回普通 Intent
        LoginDiag.add("DISPATCH", "su 方式失败，改用普通 Intent 再试");
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
            it.setClassName(pkg, cls);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            LoginDiag.add("DISPATCH", "普通 Intent 已发出");
            return true;
        } catch (Throwable t) {
            LoginDiag.add("DISPATCH", "普通 Intent 失败: " + t);
            return false;
        }
    }

    /** 把多行输出压成一行，避免日志被刷屏。 */
    private static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() > 300) t = t.substring(0, 300) + "...";
        return t;
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
