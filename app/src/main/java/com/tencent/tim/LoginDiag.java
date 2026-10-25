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
 * 1) 全文件只有一个时间（头部那行），正文只留 [标签] 内容。
 * 2) 系统 logcat 只留「能说明 app 干了什么」的行（白名单）。
 *    ColorOS 上 PERFHAL / SchedAssist / gameoptHal / lowmemorykiller
 *    每秒几十条带 failed 的无害日志，放宽就会把真正的 START u0 挤出去。
 * 3) 白名单不能空手而归：零命中时退化为最近 30 条去重后的 Tag: 消息。
 *
 * 本次新增（2026-10-25 第八次修订）：
 *
 * 「腾讯 SDK 日志」独立一段。
 *
 * 前面几轮修的都是进程层面的机制：组件选择、黑屏、回传通道、收尾看门狗。
 * 这些现在都对了（日志证明结果交回、游戏回到前台），但游戏内仍未登录。
 * 问题已经下沉到「腾讯 SDK 为什么拒绝这个 token」，而这一点只有 SDK 自己的
 * 日志能回答。系统 logcat 的白名单为了压噪音把 msdk / tauth / gcloud 全滤掉了，
 * 等于把唯一的证据也滤掉了。
 *
 * 所以单独抓一段：不看黑白名单，只认 SDK 相关关键词，
 * 原样保留 60 条，专门用来读 SDK 的报错。
 */
public class LoginDiag {

    public static final String PREF = "diag_pref";
    public static final String KEY_ENABLED = "diag_enabled";

    private static final String FILE_PREFIX = "diag_";
    private static final String FILE_SUFFIX = ".txt";
    private static final int MAX_FILES = 30;
    private static final int MAX_EVENTS = 600;

    /** 系统 logcat 取多少行来筛。 */
    private static final int LOGCAT_RAW = 2000;

    /** 命中白名单时最多保留多少条。 */
    private static final int LOGCAT_UNIQUE = 80;

    /** 白名单零命中时的兜底条数。 */
    private static final int LOGCAT_FALLBACK = 30;

    /** SDK 段取多少行。窗口放大，登录流程可能很长。 */
    private static final int SDK_RAW = 6000;

    /** SDK 段最多保留多少条。 */
    private static final int SDK_UNIQUE = 60;

    private static final List<String> events = new ArrayList<String>();
    private static long startedAt = 0L;
    private static volatile boolean enabled = false;
    private static volatile boolean loaded = false;

    private static final SimpleDateFormat NAME_TS = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA);
    private static final SimpleDateFormat FULL_TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    /** logcat 行首："10-25 16:51:32.620  4358  5210 D " */
    private static final Pattern LOGCAT_HEAD = Pattern.compile(
            "^\\d{2}-\\d{2}\\s+(\\d{2}:\\d{2}:\\d{2})\\.\\d{3}\\s+\\d+\\s+\\d+\\s+[VDIWEFA]\\s+");

    /** 系统日志白名单：只认「启动了什么、被什么拒了、有没有崩」。 */
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
    };

    /**
     * 强关键词：只认登录管线本身。
     *
     * 上一版把 "gcloud" / "login" / "openid" 这种大词当成命中条件，
     * 结果 6268 行里捞出来的全是 GCloudVoice::Pause、CameraUnitLog、
     * OplusHansManager 之类的系统噪音，真正能回答「SDK 为什么拒绝这个 token」
     * 的那几行（tauth / key_response / ret=）一条都没有。
     *
     * 所以拆成两层：
     *   STRONG  —— 命中了就直接放到最前面，这是证据；
     *   SDK_KEYS —— 只当补充上下文，且必须先过 NOISE 噪声表。
     */
    private static final String[] STRONG = {
            "key_response",
            "ret=",
            "errcode",
            "tauth",
            "authagent",
            "assistactivity",
            "authactivity",
            "authorityactivity",
            "onactivityresult",
            "authorize",
            "loginmanager",
            "iuilistener",
            "pfkey",
            "pay_token",
            "usercallback",
            "msdk_login",
            "msdksso",
            "checktoken",
            "refresh_token",
            "appid error",
            "appid不匹配",
            "signature",
    };

    /**
     * 噪声标签：命中即整行丢弃。
     *
     * 这些是 ColorOS / GCloud / 相机 HAL 的自嗨日志，跟登录一毛钱关系没有，
     * 但它们量极大，会把真正的证据挤出保留窗口。
     */
    private static final String[] NOISE = {
            "gcloudvoice",
            "gcloudcore",
            "cameraunitlog",
            "oplus",
            "remoteanim",
            "aps core",
            "aps interface",
            "locationfreezeproc",
            "ostatsmanager",
            "appsenseclient",
            "hansmanager",
            "sdhms",
            "persist.sys.assert",
            "windowmanager:",
            "surfaceflinger",
            "chatty",
            "choreographer",
            "freeze uid",
    };

    /**
     * SDK 关键词（补充层）。命中即收录，但必须先过 NOISE。
     */
    private static final String[] SDK_KEYS = {
            "msdk",
            "tauth",
            "authagent",
            "assistactivity",
            "authactivity",
            "opensdk",
            "qqauth",
            "itop",
            "com.tencent.tauth",
            "com.tencent.connect",
            "com.tencent.open",
            "key_response",
            "access_token",
            "openid",
            "pay_token",
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

    /** 事件行不带时间，只留 [标签] 内容。 */
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

        sb.append("\n--- 腾讯 SDK 日志（本进程，未按白名单过滤）---\n");
        String sdk = sdkLog(SDK_RAW);
        sb.append(sdk.isEmpty() ? "(无 root 或抓取失败)\n" : sdk);

        sb.append("\n--- 目标游戏进程日志（关键：SDK 在这里下判决）---\n");
        String gl = gameLog(c, 4000);
        sb.append(gl.isEmpty() ? "(无 root 或抓取失败)\n" : gl);

        sb.append("\n--- su logcat（白名单筛选）---\n");
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
     * 腾讯 SDK 专用：强关键词优先，噪声整行丢弃。
     *
     * 这一段的意义是「让 SDK 自己开口」。游戏内登录失败，原因通常写在这里：
     * appid 不匹配、token 无效、签名校验失败、服务端 ret 非 0 ……
     *
     * 输出分两段：
     *   ① 证据行 —— 命中 STRONG，按原顺序保留，这是能直接下判断的；
     *   ② 上下文 —— 命中 SDK_KEYS 且不是噪音，用来提供时间线。
     * 命中数量、被丢掉的噪音条数都写进头部，避免再次出现「看着像空的」。
     */
    private static String sdkLog(int maxLines) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "logcat -d -v threadtime -t " + maxLines});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            Map<String, Bucket> strong = new LinkedHashMap<String, Bucket>();
            Map<String, Bucket> ctx = new LinkedHashMap<String, Bucket>();
            int raw = 0;
            int dropped = 0;
            String ln;
            while ((ln = br.readLine()) != null) {
                raw++;
                String body = bodyOf(ln);
                if (body == null || body.isEmpty()) continue;
                String line = trim(body);
                if (line.isEmpty()) continue;
                boolean isStrong = isStrong(line);
                if (!isStrong && isNoise(line)) {
                    dropped++;
                    continue;
                }
                if (!isStrong && !looksLikeSdk(line)) continue;
                Map<String, Bucket> into = isStrong ? strong : ctx;
                Bucket b = into.get(line);
                if (b == null) into.put(line, new Bucket(line));
                else b.count++;
            }
            br.close();
            try {
                p.waitFor();
            } catch (Exception ignored) {
            }

            StringBuilder sb = new StringBuilder();
            sb.append("读取 ").append(raw).append(" 行，证据 ").append(strong.size())
                    .append(" 条，上下文 ").append(ctx.size()).append(" 条");
            if (dropped > 0) sb.append("，已丢弃噪声 ").append(dropped).append(" 行");
            sb.append('\n');

            sb.append("\n[证据行 · 登录管线]\n");
            if (strong.isEmpty()) {
                sb.append("(没有命中。说明 SDK 没有走到回调处理 —— ")
                        .append("token 很可能交给了错误的组件，或 setResult 的 caller 不是 SDK 本身)\n");
            } else {
                appendBuckets(sb, new ArrayList<Bucket>(strong.values()), SDK_UNIQUE);
            }

            sb.append("\n[上下文 · SDK 周边]\n");
            if (ctx.isEmpty()) {
                sb.append("(无)\n");
            } else {
                appendBuckets(sb, new ArrayList<Bucket>(ctx.values()), SDK_UNIQUE);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static void appendBuckets(StringBuilder sb, List<Bucket> list, int cap) {
        boolean trimmed = false;
        if (list.size() > cap) {
            list = new ArrayList<Bucket>(list.subList(list.size() - cap, list.size()));
            trimmed = true;
        }
        if (trimmed) sb.append("(只显示最近 ").append(cap).append(" 条)\n");
        for (Bucket b : list) {
            sb.append(b.text);
            if (b.count > 1) sb.append("  (").append(b.count - 1).append(" 次重复已合并)");
            sb.append('\n');
        }
    }

    private static boolean isStrong(String line) {
        String low = line.toLowerCase(Locale.ROOT);
        for (String k : STRONG) {
            if (low.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** 游戏侧关键词：SDK 的判决写在游戏进程里，不在本进程。 */
    private static final String[] GAME_KEYS = {
            "msdk", "itop", "gcloud", "tmgp", "login", "auth", "token",
            "openid", "appid", "ret =", "ret=", "errorcode", "errcode",
    };

    /**
     * 目标游戏进程的日志。
     *
     * 这是上一版最大的盲区：token 交回游戏之后，被不被接受、为什么被拒绝，
     * 全部记录在 com.tencent.tmgp.xxx 自己的进程里。我们一直在读自己的进程，
     * 所以永远只能看到「已交回」，看不到「交回之后发生了什么」。
     *
     * 这里按 pid 直读游戏进程，命中强关键词的视为判决行优先输出。
     */
    private static String gameLog(Context c, int maxLines) {
        String pkg = "";
        try {
            String game = c.getSharedPreferences("myPrefs", Context.MODE_PRIVATE)
                    .getString("selected_game_name", "");
            pkg = GameLaunch.packageOf(game);
        } catch (Throwable ignored) {
        }
        if (pkg == null || pkg.isEmpty()) {
            return "(没有选中游戏，跳过)\n";
        }
        String pids = runSuCmd("pidof " + pkg).trim();
        if (pids.isEmpty()) {
            return "游戏进程不在（" + pkg + "），没有判决可读\n";
        }
        String pidArg = pids.replaceAll("\\s+", ",");
        String dump = runSuCmd("logcat -d -v threadtime -b main,system,crash -t "
                + maxLines + " --pid=" + pidArg);
        if (dump == null || dump.isEmpty()) {
            dump = runSuCmd("logcat -d -v threadtime -t " + maxLines
                    + " --pid=" + pidArg.split(",")[0]);
        }
        Map<String, Bucket> strong = new LinkedHashMap<String, Bucket>();
        Map<String, Bucket> ctx = new LinkedHashMap<String, Bucket>();
        int raw = 0;
        int dropped = 0;
        if (dump != null) {
            for (String rawLine : dump.split("\n")) {
                raw++;
                String body = bodyOf(rawLine);
                if (body == null || body.isEmpty()) continue;
                String line = trim(body);
                if (line.isEmpty()) continue;
                boolean s = isStrong(line);
                if (!s && isNoise(line)) {
                    dropped++;
                    continue;
                }
                if (!s && !looksLikeGame(line)) continue;
                Map<String, Bucket> into = s ? strong : ctx;
                Bucket b = into.get(line);
                if (b == null) into.put(line, new Bucket(line));
                else b.count++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("包名 ").append(pkg).append(" pid=").append(pidArg)
                .append("，读取 ").append(raw).append(" 行，证据 ").append(strong.size())
                .append(" 条，上下文 ").append(ctx.size()).append(" 条");
        if (dropped > 0) sb.append("，丢弃噪声 ").append(dropped).append(" 行");
        sb.append('\n');

        sb.append("\n[证据行 · SDK 判决]\n");
        if (strong.isEmpty()) {
            sb.append("(没有判决行。说明游戏进程根本没回调 SDK 的登录结果 —— ")
                    .append("token 大概率不是被 AssistActivity 消费，而是被系统丢了)\n");
        } else {
            appendBuckets(sb, new ArrayList<Bucket>(strong.values()), 80);
        }
        sb.append("\n[上下文 · 游戏侧]\n");
        if (ctx.isEmpty()) {
            sb.append("(无)\n");
        } else {
            appendBuckets(sb, new ArrayList<Bucket>(ctx.values()), 60);
        }
        return sb.toString();
    }

    private static boolean looksLikeGame(String line) {
        String low = line.toLowerCase(Locale.ROOT);
        for (String k : GAME_KEYS) {
            if (low.contains(k)) return true;
        }
        return false;
    }

    private static String runSuCmd(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) {
                sb.append(ln).append('\n');
            }
            br.close();
            try {
                p.waitFor();
            } catch (InterruptedException ignored) {
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static boolean isNoise(String line) {
        String low = line.toLowerCase(Locale.ROOT);
        for (String k : NOISE) {
            if (low.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean looksLikeSdk(String line) {
        if (line == null) return false;
        String low = line.toLowerCase(Locale.ROOT);
        for (String k : SDK_KEYS) {
            if (low.contains(k)) return true;
        }
        return false;
    }

    /**
     * 系统日志白名单压缩：白名单筛选 → 去掉行首时间/pid → 相同内容合并。
     * 零命中时退化为最近 LOGCAT_FALLBACK 条，避免出现「保留 0 条」。
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
                List<String> all = new ArrayList<String>(others);
                int from = Math.max(0, all.size() - LOGCAT_FALLBACK);
                for (int i = from; i < all.size(); i++) sb.append(all.get(i)).append('\n');
                if (all.isEmpty()) sb.append("(这段时间没有可读的 logcat)\n");
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
                int sp = s.indexOf(' ');
                if (sp > 0 && sp <= 6) s = s.substring(sp + 1);
                int first = s.indexOf(' ');
                if (first > 0) s = s.substring(first + 1);
            }
        } catch (Throwable ignored) {
        }
        return s.trim();
    }

    /** 去掉行内可能残留的第二处时间。 */
    private static String trim(String s) {
        String r = s;
        try {
            r = r.replaceAll("^\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}\\s+", "");
            r = r.replaceAll("^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}\\s+", "");
        } catch (Throwable ignored) {
        }
        r = r.trim();
        if (r.length() > 260) r = r.substring(0, 260) + "...";
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
