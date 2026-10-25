package com.tencent.tim;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 诊断日志。
 *
 * 三个原则：
 *
 * 1) 全文件只有一个时间。
 *    原来是每行前挂 HH:mm:ss.SSS，logcat 段里再挂一次 HH:mm:ss，
 *    满屏都是时间，真正要看的内容反而看不清。
 *    现在时间只出现在头部一行，正文只保留 [标签] 内容，靠行序表达先后。
 *
 * 2) logcat 只留「能说明 app 干了什么」的行。
 *    ColorOS 上 PERFHAL / SchedAssist / gameoptHal / lowmemorykiller
 *    每秒钟能刷几十条带 failed 的无害日志，一旦放宽就会把真正的
 *    START u0 挤出去。所以用白名单，只认组件启动、崩溃、权限这几类。
 *
 * 3) 白名单不能空手而归。
 *    上一版出现过「保留 0 条」—— 白名单一个都没命中，用户什么都看不到。
 *    现在保留一份兜底：没有任何命中时，退化为最近 30 条去重后的 Tag: 消息。
 *    宁可粗糙，也不能让日志变空。
 */
public class LoginDiag {

    public static final String PREF = "diag_pref";
    public static final String KEY_ENABLED = "diag_enabled";

    private static final String FILE_PREFIX = "diag_";
    private static final String FILE_SUFFIX = ".txt";
    private static final int MAX_FILES = 30;
    private static final int MAX_EVENTS = 600;

    /** logcat 取多少行原始日志来筛。 */
    private static final int LOGCAT_RAW = 2000;

    /** 命中白名单时最多保留多少条。 */
    private static final int LOGCAT_UNIQUE = 80;

    /** 白名单零命中时的兜底条数。 */
    private static final int LOGCAT_FALLBACK = 30;

    private static final List<String> events = new ArrayList<String>();
    private static long startedAt = 0L;
    private static volatile boolean enabled = false;
    private static volatile boolean loaded = false;

    /** 文件名精确到分钟；正文里不再出现任何逐行时间。 */
    private static final SimpleDateFormat NAME_TS = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA);
    private static final SimpleDateFormat FULL_TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    /** logcat 行首："10-25 16:51:32.620  4358  5210 D " */
    private static final Pattern LOGCAT_HEAD = Pattern.compile(
            "^\\d{2}-\\d{2}\\s+(\\d{2}:\\d{2}:\\d{2})\\.\\d{3}\\s+\\d+\\s+\\d+\\s+[VDIWEFA]\\s+");

    /**
     * 白名单：命中任意一条才进「重点」区。
     *
     * 全部围绕「app 到底启动了什么、被什么拒了、有没有崩」。
     */
    private static final String[] KEEP = {
            "START u0",
            "LaunchState",
            "Displayed ",
            "Force finishing",
            "Force stopping",
            "ActivityNotFoundException",
            "does not exist",
            "Unable to resolve",
            "Unable to start",
            "Permission Denial",
            "not exported",
            "ANR in",
            "FATAL EXCEPTION",
            "AndroidRuntime",
            "am_kill",
            "has died",
            "Start proc",
            "was killed",
            "WebView",
            "chromium",
            "xray",
            "tauth",
            "auth://",
            "MSDK",
            "msdk",
    };

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

    /** 事件行不再带时间，只留 [标签] 内容。 */
    public static synchronized void add(String tag, String detail) {
        if (!enabled) return;
        if (events.size() >= MAX_EVENTS) {
            events.remove(0);
        }
        events.add("[" + tag + "] " + LogScrubber.scrub(detail));
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
            Collections.sort(out, new Comparator<File>() {
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static boolean deleteLog(File f) {
        if (f == null || !f.exists()) return false;
        try {
            return f.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    public static int deleteAllLogs(Context c) {
        int n = 0;
        for (File f : listLogs(c)) {
            if (deleteLog(f)) n++;
        }
        return n;
    }

    /** 文件名只到分钟，同分钟撞名时补 _2、_3，不覆盖已有文件。 */
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
        // 全文件唯一的一个时间点
        sb.append("时间: ").append(FULL_TS.format(
                new Date(started > 0 ? started : System.currentTimeMillis()))).append("\n");
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
        sb.append("\n--- su logcat ---\n");
        String log = suLogcat(LOGCAT_RAW);
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
            try {
                if (fos != null) fos.close();
            } catch (Exception ignored) {
            }
        }
    }

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
        BufferedReader br = null;
        try {
            br = new BufferedReader(
                    new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 取 logcat 并压缩：白名单筛选 → 去掉行首时间/pid → 相同内容合并。
     *
     * 白名单一条都没命中时，退化为最近 LOGCAT_FALLBACK 条去重后的 Tag: 消息，
     * 保证不会出现「保留 0 条」这种什么都看不到的结果。
     */
    private static String suLogcat(int maxLines) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "logcat -d -v threadtime -t " + maxLines});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));

            Map<String, Bucket> kept = new LinkedHashMap<String, Bucket>();
            LinkedHashSet<String> others = new LinkedHashSet<String>();
            int raw = 0;
            String ln;
            while ((ln = br.readLine()) != null) {
                raw++;
                String body = bodyOf(ln);
                if (body == null || body.isEmpty()) continue;
                String line = trim(body);
                if (line.isEmpty()) continue;
                if (isKeep(line)) {
                    Bucket b = kept.get(line);
                    if (b == null) kept.put(line, new Bucket(line));
                    else b.count++;
                } else {
                    others.add(line);
                    if (others.size() > 400) {
                        // 只留尾部，前面的老日志没意义
                        List<String> tmp = new ArrayList<String>(others);
                        others.clear();
                        for (int i = tmp.size() - 300; i < tmp.size(); i++) others.add(tmp.get(i));
                    }
                }
            }
            br.close();
            try {
                p.waitFor();
            } catch (Exception ignored) {
            }

            StringBuilder sb = new StringBuilder();
            List<Bucket> list = new ArrayList<Bucket>(kept.values());
            if (list.isEmpty()) {
                sb.append("读取 ").append(raw).append(" 行，无关键行，显示最近 ")
                        .append(LOGCAT_FALLBACK).append(" 条原始日志\n");
                List<String> tail = new ArrayList<String>(others);
                int from = Math.max(0, tail.size() - LOGCAT_FALLBACK);
                for (int i = from; i < tail.size(); i++) {
                    sb.append(tail.get(i)).append('\n');
                }
                if (tail.isEmpty()) sb.append("(这段时间没有可读的 logcat)\n");
                return sb.toString();
            }
            boolean trimmed = false;
            if (list.size() > LOGCAT_UNIQUE) {
                list = new ArrayList<Bucket>(list.subList(list.size() - LOGCAT_UNIQUE, list.size()));
                trimmed = true;
            }
            sb.append("读取 ").append(raw).append(" 行，命中关键行 ")
                    .append(kept.size()).append(" 条");
            if (trimmed) sb.append("，只显示最近 ").append(LOGCAT_UNIQUE).append(" 条");
            sb.append('\n');
            for (Bucket b : list) {
                sb.append(b.text);
                if (b.count > 1) sb.append("  (").append(b.count - 1).append(" 次重复已合并)");
                sb.append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** 去掉 "10-25 16:51:32.620  4358  5210 D " 前缀，只留 "Tag: msg"。 */
    private static String bodyOf(String line) {
        if (line == null) return null;
        String s = line;
        try {
            Matcher m = LOGCAT_HEAD.matcher(s);
            if (m.find()) {
                s = s.substring(m.end());
            } else {
                // 兼容没有毫秒字段或格式略有差异的行
                int sp = s.indexOf(' ');
                if (sp > 0 && sp <= 6) s = s.substring(sp + 1);
                int first = s.indexOf(' ');
                if (first > 0) s = s.substring(first + 1);
            }
        } catch (Throwable ignored) {
        }
        return s.trim();
    }

    /** 去掉行内可能存在的第二处时间，保证一行最多一个时间（正文里一个都不留）。 */
    private static String trim(String s) {
        String r = s;
        try {
            r = r.replaceAll("^\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}\\s+", "");
            r = r.replaceAll("^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}\\s+", "");
        } catch (Throwable ignored) {
        }
        r = r.trim();
        if (r.length() > 240) r = r.substring(0, 240) + "...";
        return r;
    }

    private static boolean isKeep(String s) {
        for (String k : KEEP) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private static class Bucket {
        final String text;
        int count = 1;

        Bucket(String x) {
            text = x;
        }
    }
}
