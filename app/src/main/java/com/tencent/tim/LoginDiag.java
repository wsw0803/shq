package com.tencent.tim;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 诊断日志。
 *
 * 设计：开关控制，关闭时自动落盘，时间戳命名，不进内存累积。
 *
 * 本次新增：
 * - 支持删除单个日志文件
 * - 支持一键清空全部历史日志
 * - 保留最近 30 份的自动清理
 */
public class LoginDiag {

    public static final String PREF = "diag_pref";
    public static final String KEY_ENABLED = "diag_enabled";

    private static final String FILE_PREFIX = "diag_";
    private static final String FILE_SUFFIX = ".txt";
    private static final int MAX_FILES = 30;

    private static final int MAX_EVENTS = 600;
    private static final List<String> events = new ArrayList<String>();
    private static long startedAt = 0L;

    private static volatile boolean enabled = false;
    private static volatile boolean loaded = false;

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
    private static final SimpleDateFormat NAME_TS =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);
    private static final SimpleDateFormat FULL_TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void init(Context c) {
        if (c == null) return;
        enabled = prefs(c).getBoolean(KEY_ENABLED, false);
        loaded = true;
    }

    public static boolean isEnabled(Context c) {
        if (!loaded) init(c);
        return enabled;
    }

    /** 开启：清空缓冲开始记录。关闭：先取快照再清空，后台落盘。 */
    public static void setEnabled(final Context c, boolean on) {
        if (c == null) return;
        prefs(c).edit().putBoolean(KEY_ENABLED, on).apply();
        enabled = on;
        loaded = true;

        if (on) {
            begin();
            add("DIAG", "开启诊断日志");
            return;
        }

        final Context app = c.getApplicationContext();
        final List<String> snap;
        final long started;
        synchronized (LoginDiag.class) {
            snap = new ArrayList<String>(events);
            started = startedAt;
            events.clear();
            startedAt = 0L;
        }
        if (snap.isEmpty()) return;

        new Thread(new Runnable() {
            public void run() {
                saveSnapshot(app, snap, started);
            }
        }).start();
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

    public static File logDir(Context c) {
        File d = c.getExternalFilesDir(null);
        return d != null ? d : c.getFilesDir();
    }

    /** 列出已有日志，按时间从新到旧。 */
    public static List<File> listLogs(Context c) {
        List<File> out = new ArrayList<File>();
        try {
            File dir = logDir(c);
            File[] fs = dir.listFiles();
            if (fs == null) return out;
            for (File f : fs) {
                String n = f.getName();
                if (n != null && n.startsWith(FILE_PREFIX) && n.endsWith(FILE_SUFFIX)) {
                    out.add(f);
                }
            }
            java.util.Collections.sort(out, new Comparator<File>() {
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 删除单个日志文件。 */
    public static boolean deleteLog(File f) {
        if (f == null || !f.exists()) return false;
        try {
            return f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 删除全部历史日志，返回删除数量。 */
    public static int deleteAllLogs(Context c) {
        int n = 0;
        for (File f : listLogs(c)) {
            if (deleteLog(f)) n++;
        }
        return n;
    }

    private static File newLogFile(Context c, long stampMs) {
        File dir = logDir(c);
        try {
            if (!dir.exists()) dir.mkdirs();
        } catch (Throwable ignored) {
        }
        String base = FILE_PREFIX + NAME_TS.format(new Date(stampMs > 0 ? stampMs : System.currentTimeMillis()));
        File f = new File(dir, base + FILE_SUFFIX);
        int i = 2;
        while (f.exists() && i < 100) {
            f = new File(dir, base + "_" + i + FILE_SUFFIX);
            i++;
        }
        return f;
    }

    private static void prune(Context c) {
        try {
            List<File> all = listLogs(c);
            for (int i = MAX_FILES; i < all.size(); i++) {
                all.get(i).delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String saveSnapshot(Context c, List<String> snap, long started) {
        if (c == null || snap == null || snap.isEmpty()) return null;
        File out = newLogFile(c, started);

        StringBuilder sb = new StringBuilder();
        sb.append("=== shq 诊断日志 ===\n");
        sb.append("开始时间: ").append(FULL_TS.format(new Date(started > 0 ? started : System.currentTimeMillis()))).append("\n");
        sb.append("结束时间: ").append(FULL_TS.format(new Date())).append("\n");
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("机型: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append("\n");
        sb.append("线路: ").append(Net.routeName(c)).append("\n");
        sb.append("节点: ").append(NodeConfig.summary()).append("\n");
        sb.append("xray: ").append(XrayCore.isAlive() ? "运行中" : "未运行").append("\n");
        sb.append("事件数: ").append(snap.size()).append("\n\n");

        sb.append("--- 事件轨迹 ---\n");
        for (String line : snap) sb.append(line).append('\n');

        sb.append("\n--- su logcat（已过滤）---\n");
        String log = suLogcat(800);
        sb.append(log.isEmpty() ? "(无 root 或抓取失败)\n" : log);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(out, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
            prune(c);
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }
    }

    /** 手动导出一份当前日志。没有正在记录的内容时返回最近一份文件。 */
    public static String export(Context ctx, String gameName) {
        List<String> snap = snapshot();
        if (snap.isEmpty()) {
            List<File> all = listLogs(ctx);
            if (!all.isEmpty()) return all.get(0).getAbsolutePath();
            return null;
        }
        return saveSnapshot(ctx, snap, startedAt);
    }

    public static String copyToClipboard(Context c) {
        if (c == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : snapshot()) sb.append(s).append('\n');
        String text = sb.toString();
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", text));
        } catch (Throwable ignored) {
        }
        return text;
    }

    public static String readFile(File f) {
        if (f == null || !f.exists()) return "";
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (br != null) {
                try { br.close(); } catch (Exception ignored) {}
            }
        }
    }

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
