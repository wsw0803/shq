package com.tencent.tim;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 把 token 直接派发给目标游戏。
 *
 * 为什么需要这个：
 * 上号模式如果是从本应用主界面进入的，setResult 只会把结果交回 MainActivity，
 * 目标游戏根本收不到 token —— 表现就是「提示登录成功但游戏没反应」。
 *
 * 破解办法：腾讯游戏 APK 内部都打包了 QQ SDK 的
 * com.tencent.open.agent.AgentActivity，有 root 就能直接启动
 * 游戏自己的那个 Activity 并带上 auth 数据，不依赖谁启动了 shq。
 *
 *   am start -n <游戏包名>/com.tencent.open.agent.AgentActivity -d "auth://..."
 *
 * 走 su 是因为高版本 Android 对非导出组件有跨应用启动限制。
 */
public class GameLaunch {

    /** 已知游戏包名。没有的会记录日志并跳过，不会乱启动。 */
    private static final Map<String, String> PACKAGES = new HashMap<String, String>();

    static {
        PACKAGES.put("和平", "com.tencent.tmgp.pubgmhd");
        PACKAGES.put("王者", "com.tencent.tmgp.sgame");
        PACKAGES.put("三角洲", "com.tencent.tmgp.dfm");
        PACKAGES.put("暗区突围", "com.tencent.mf.uam");
        PACKAGES.put("无畏契约", "com.tencent.tmgp.codev");
    }

    /** SDK 里的登录回调 Activity，每个游戏 APK 自带一份。 */
    private static final String AGENT_CLASS = "com.tencent.open.agent.AgentActivity";

    public static String packageOf(String gameName) {
        if (gameName == null) return "";
        String p = PACKAGES.get(gameName.trim());
        return p == null ? "" : p;
    }

    public static boolean hasPackage(String gameName) {
        return !packageOf(gameName).isEmpty();
    }

    /**
     * 直接派发。返回是否成功启动。
     * 每一步都写入诊断日志，便于判断卡在哪个环节。
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

        LoginDiag.add("DISPATCH", "目标包名=" + pkg + " 组件=" + AGENT_CLASS);

        // 方式一：su + am start，能跨过非导出组件的限制
        String cmd = "am start -n " + pkg + "/" + AGENT_CLASS
                + " -d " + RootUtil.shellQuote(authUrl)
                + " --activity-new-task";
        String out = runSu(cmd);
        LoginDiag.add("DISPATCH", "su am start 输出: " + (out.isEmpty() ? "(空)" : out));

        boolean ok = !out.toLowerCase().contains("error")
                && !out.toLowerCase().contains("exception")
                && !out.toLowerCase().contains("does not exist");
        if (ok) {
            LoginDiag.add("DISPATCH", "已交给游戏处理");
            return true;
        }

        // 方式二：走普通 Intent，交给系统解析
        LoginDiag.add("DISPATCH", "su 方式失败，改用普通 Intent 再试");
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
            it.setClassName(pkg, AGENT_CLASS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            LoginDiag.add("DISPATCH", "普通 Intent 已发出");
            return true;
        } catch (Throwable t) {
            LoginDiag.add("DISPATCH", "普通 Intent 失败: " + t);
            return false;
        }
    }

    /** 执行 su 命令，返回合并后的输出。 */
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
            while ((ln = br.readLine()) != null) sb.append(ln).append(' ');
            br.close();
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while ((ln = er.readLine()) != null) sb.append(ln).append(' ');
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
