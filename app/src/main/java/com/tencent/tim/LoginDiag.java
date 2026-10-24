package com.tencent.tim;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 诊断日志。
 *
 * 改为设置项常驻开关，不再有悬浮球：
 *   设置 → 诊断日志 → 开/关
 * 开启后持续记录 抓号 / 上号 / 数据写入 / 导出 等全流程，
 * 关闭后所有 add() 直接丢弃，零开销。
 *
 * 记录内容：
 * - 模式、游戏、已选 appid
 * - WebView 导航链（系统 logcat 看不到，必须自己记）
 * - Cookie 状态（区分「ck 被拦」与「弹窗被吞」）
 * - token 各字段长度（不记明文）
 * - token 内 appid 与已选 appid 是否一致
 * - 数据写入（addEntry / saveBatch / exportAll）结果
 * - 导出动作
 *
 * 文件落在：Android/data/com.tencent.tim/files/diag_login.txt
 * 设置页可点「导出诊断日志」直接拿到文件路径并复制到剪贴板。
 */
public class LoginDiag {

    public static final String PREF = "diag_pref";
    public static final String KEY_ENABLED = "diag_enabled";

    private static final int MAX_EVENTS = 600;
    private static final List<String> events = new ArrayList<String>();
    private static long startedAt = 0L;

    /** 缓存开关状态，避免每条日志都读 SharedPreferences。 */
    private static volatile boolean enabled = false;
    private static volatile boolean loaded = false;

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 应用启动时调一次，把开关状态读进内存。 */
    public static void init(Context c) {
        if (c == null) return;
        enabled = prefs(c).getBoolean(KEY_ENABLED, false);
        loaded = true;
    }

    public static boolean isEnabled(Context c) {
        if (!loaded) init(c);
        return enabled;
    }

    public static void setEnabled(Context c, boolean on) {
        if (c == null) return;
        prefs(c).edit().putBoolean(KEY_ENABLED, on).apply();
        enabled = on;
        loaded = true;
        if (on) {
            begin();
            add("DIAG", "已开启诊断日志");
        } else {
            synchronized (LoginDiag.class) {
                events.clear();
            }
        }
    }

    public static synchronized void begin() {
        events.clear();
        startedAt = System.currentTimeMillis();
    }

    public static synchronized void add(String tag, String detail) {
        if (!enabled) return;
        if (events.size() >= MAX_EVENTS) {
            events.remove(0);
        }
        String t = TS.format(new Date());
        events.add(t + " [" + tag + "] " + LogScrubber.scrub(detail));
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<String>(events);
    }

    public static synchronized long startedAt() {
        return startedAt;
    }

    public static synchronized void clear() {
        events.clear();
        startedAt = System.currentTimeMillis();
    }

    /**
     * 导出诊断文件，返回路径，失败返回 null。
     * 需在后台线程调用（su logcat 会阻塞）。
     */
    public static String export(Context ctx, String gameName) {
        if (ctx == null) return null;
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        File out = new File(dir, "diag_login.txt");

        StringBuilder sb = new StringBuilder();
        sb.append("=== shq 诊断日志 ===\n");
        sb.append("时间: ").append(TS.format(new Date())).append("\n");
        sb.append("游戏: ").append(gameName == null ? "" : gameName).append("\n");
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("机型: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append("\n");
        sb.append("线路: ").append(Net.routeName(ctx)).append("\n");
        sb.append("节点: ").append(NodeConfig.summary()).append("\n");
        sb.append("xray: ").append(XrayCore.isAlive() ? "运行中" : "未运行").append("\n");
        sb.append("诊断开关: ").append(enabled ? "开" : "关").append("\n\n");

        sb.append("--- 事件轨迹 ---\n");
        List<String> snap = snapshot();
        if (snap.isEmpty()) {
            sb.append("(空，请先在设置里开启诊断日志)\n");
        } else {
            for (String line : snap) sb.append(line).append('\n');
        }

        sb.append("\n--- su logcat（已过滤）---\n");
        String log = suLogcat(800);
        sb.append(log.isEmpty() ? "(无 root 或抓取失败)\n" : log);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(out, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    /** 把当前轨迹复制到剪贴板，便于直接粘贴反馈。 */
    public static void copyToClipboard(Context c) {
        if (c == null) return;
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            StringBuilder sb = new StringBuilder();
            for (String s : snapshot()) sb.append(s).append('\n');
            cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", sb.toString()));
        } catch (Throwable ignored) {
        }
    }

    /** 用 su 抓 logcat 并过滤。Android 高版本对普通应用读 logcat 有限制，走 su 更稳。 */
    private static String suLogcat(int maxLines) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "logcat -d -t " + maxLines});
            java.io.BufferedReader br =
                    new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String ln;
            int kept = 0;
            while ((ln = br.readLine()) != null) {
                String low = ln.toLowerCase();
                if (low.contains("webview") || low.contains("chromium")
                        || low.contains("crash") || low.contains("ssl")
                        || low.contains("com.tencent.tim")
                        || low.contains("agentactivity") || low.contains("androidruntime")) {
                    sb.append(LogScrubber.scrub(ln)).append('\n');
                    kept++;
                    if (kept >= maxLines) break;
                }
            }
            br.close();
            try { p.waitFor(); } catch (Exception ignored) {}
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }
}
