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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 诊断日志。
 *
 * 设计：开关控制，关闭时自动落盘，时间戳命名，不进内存累积。
 *
 * logcat 的筛选思路（2026-10-25 第二次修订）：
 *
 * 上一版用「黑名单 + 出现 error/fail 就保留」的宽容规则，结果翻车了：
 * ColorOS 上满屏都是 PERFHAL 的 "Failed to reset optimization"、
 * SchedAssist 的 "ioctl failed"、gameoptHal 的 "set isolate cpus failed"、
 * lowmemorykiller 的 "device has enough memory ... disable killing"。
 * 这些行都带 failed / error，于是全被当成有用信息保留，
 * 而真正该看的 "START u0 {cmp=com.tencent.tmgp.dfm/...}" 反被挤进 120 条上限之外。
 *
 * 现在改成白名单：只有命中 KEEP 里的明确模式才保留，其余一律丢弃。
 * logcat 段落的目标不是「尽量多」，而是「一眼看出 app 到底做了什么」。
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

    /** 合并去重之后最多保留多少条，超了只留最近的。 */
    private static final int LOGCAT_UNIQUE = 80;

    private static final List<String> events = new ArrayList<String>();
    private static long startedAt = 0L;
    private static volatile boolean enabled = false;
    private static volatile boolean loaded = false;

    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
    private static final SimpleDateFormat NAME_TS = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA);
    private static final SimpleDateFormat FULL_TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    /** logcat 行首："10-25 16:51:32.620  4358  5210 D " */
    private static final Pattern LOGCAT_HEAD = Pattern.compile(
            "^\\d{2}-\\d{2}\\s+(\\d{2}:\\d{2}:\\d{2})\\.\\d{3}\\s+\\d+\\s+\\d+\\s+[VDIWEFA]\\s+");

    /**
     * 白名单：命中任意一条才保留。
     *
     * 选的都是「能说明 app 干了什么」的行：组件启动、结果交付、权限拒绝、
     * 崩溃、WebView 与 xray 的异常。系统埋点、功耗统计、调频日志一概不要。
     */
    private static final String[] KEEP = {
            "START u0",              // 组件启动（含 am start / startActivity）
            "Displayed ",           // 首次显示耗时，能确认游戏真的起来了
            "Force finishing",
            "Force stopping",
            "ActivityNotFoundException",
            "does not exist",        // am start -n 打到不存在的组件
            "Unable to resolve",
            "Unable to start",
            "Permission Denial",
            "not exported",
            "ANR in",
            "FATAL EXCEPTION",
            "AndroidRuntime",
            "am_kill",
            "Process ",              // Process xxx has died / Start proc
            "WebView",
            "chromium",
            "xray",
            "Tencent",               // 腾讯 SDK 自身的日志
            "tauth",
            "auth://",
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
            Collections.sort(out, new Comparator<File>() {
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

    /**
     * 生成日志文件。文件名只到分钟，同一分钟内可能撞名，
     * 这里补 _2、_3 后缀，不覆盖已有文件。
     */
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
        sb.append("\n--- su logcat（白名单筛选，重复行合并）---\n");
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
                    (android.content.ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE);
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
     * 取 logcat 并按白名单压缩。
     *
     * 三步：白名单筛选 → 只留 HH:mm:ss → 相同内容合并并标次数。
     * 条目超上限时保留最近的若干条，出问题总在最后。
     */
    private static String suLogcat(int maxLines) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "logcat -d -t " + maxLines});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));

            Map<String, Bucket> merged = new LinkedHashMap<String, Bucket>();
            int raw = 0;
            int dropped = 0;
            String ln;
            while ((ln = br.readLine()) != null) {
                raw++;
                String key = compact(ln);
                if (key == null) {
                    dropped++;
                    continue;
                }
                String time = timeOf(ln);
                Bucket b = merged.get(key);
                if (b == null) {
                    merged.put(key, new Bucket(time, key));
                } else {
                    b.count++;
                }
            }
            br.close();
            try {
                p.waitFor();
            } catch (Exception ignored) {
            }

            List<Bucket> list = new ArrayList<Bucket>(merged.values());
            boolean trimmed = false;
            if (list.size() > LOGCAT_UNIQUE) {
                list = new ArrayList<Bucket>(list.subList(list.size() - LOGCAT_UNIQUE, list.size()));
                trimmed = true;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("读取 ").append(raw).append(" 行，筛掉 ").append(dropped)
                    .append(" 行，保留 ").append(merged.size()).append(" 条");
            if (trimmed) {
                sb.append("，只显示最近 ").append(LOGCAT_UNIQUE).append(" 条");
            }
            sb.append('\n');
            if (list.isEmpty()) {
                sb.append("(这段时间内没有组件启动、崩溃或权限相关记录)\n");
                return sb.toString();
            }
            for (Bucket b : list) {
                sb.append(b.time).append(' ').append(b.text);
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

    /** 取 "10-25 16:51:32.620" 里的 16:51:32；取不到返回 --:--:-- 。 */
    private static String timeOf(String line) {
        try {
            Matcher m = LOGCAT_HEAD.matcher(line);
            if (m.find()) return m.group(1);
            int i = line.indexOf(' ');
            if (i > 0 && line.length() > i + 9) {
                String s = line.substring(i + 1, i + 9);
                if (s.length() == 8 && s.charAt(2) == ':' && s.charAt(5) == ':') return s;
            }
        } catch (Throwable ignored) {
        }
        return "--:--:--";
    }

    /**
     * 归一化成 "Tag: msg"。返回 null 表示这行不要。
     *
     * 只做白名单判断：命中 KEEP 任意一条才留下。
     * 上一版那条「包含 error / fail 就保留」的规则必须删掉 ——
     * ColorOS 上大量无害的 failed 会顺着它全爬进来。
     */
    private static String compact(String line) {
        if (line == null) return null;
        String s = line;
        try {
            Matcher m = LOGCAT_HEAD.matcher(s);
            if (m.find()) {
                s = s.substring(m.end());
            } else {
                int sp = s.indexOf(' ');
                if (sp > 0 && sp < 4) s = s.substring(sp + 1);
            }
        } catch (Throwable ignored) {
        }
        s = s.trim();
        if (s.isEmpty()) return null;

        boolean keep = false;
        for (String k : KEEP) {
            if (s.contains(k)) {
                keep = true;
                break;
            }
        }
        if (!keep) return null;

        // "Process" 太宽，只留死亡与启动两类，避免普通业务日志混进来
        if (s.contains("Process ") && !s.contains("has died") && !s.contains("Start proc")
                && !s.contains("was killed")) {
            boolean other = false;
            for (String k : KEEP) {
                if (!"Process ".equals(k) && s.contains(k)) {
                    other = true;
                    break;
                }
            }
            if (!other) return null;
        }

        if (s.length() > 240) s = s.substring(0, 240) + "...";
        return s;
    }

    private static class Bucket {
        final String time;
        final String text;
        int count = 1;

        Bucket(String t, String x) {
            time = t;
            text = x;
        }
    }
}
