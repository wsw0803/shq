package com.tencent.tim;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 诊断日志。
 *
 * 目标：真机上「哪一步失败、失败在哪个方法、上下文是什么」都能事后回看，
 * 而不是靠用户口述 + 反复猜。
 *
 * 约定：
 *   - 只在「失败 / 关键状态变化」时写，不写流水账，日志才读得完；
 *   - 每条都带方法名（method 参数），一眼能定位到 Java 的哪个方法；
 *   - 异常一律带完整堆栈；
 *   - 落盘到 files/WEI/logs/diag-*.log，超过 1.5MB 自动滚动，只留最近 5 个；
 *   - 全局未捕获异常也走这里，崩溃原因不会丢。
 *
 * 用法：
 *   Diag.step("Login", "loadLoginPage", "url=" + url);
 *   Diag.fail("Login", "onReceivedError", "code=" + code + " desc=" + desc, t);
 */
public final class Diag {

    private static final String TAG = "Diag";
    private static final String DIR_NAME = "logs";
    private static final long MAX_BYTES = 1536 * 1024L;
    private static final int KEEP = 5;

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static Context appCtx;
    private static volatile boolean inited;
    private static String sessionId = "";

    private Diag() {
    }

    /** 在 Application.onCreate 里调用一次即可；重复调用无副作用。 */
    public static void init(Context c) {
        if (c == null) return;
        synchronized (LOCK) {
            if (inited) return;
            appCtx = c.getApplicationContext();
            sessionId = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            inited = true;
            installCrashHandler();
            writeHeader();
        }
    }

    private static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    crash(t, e);
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private static void writeHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== WEI 诊断日志开始 ").append(TS.format(new Date())).append(" =====\n");
        sb.append("session=").append(sessionId).append("\n");
        sb.append("app=").append(versionName()).append(" (code ").append(versionCode()).append(")\n");
        sb.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | android=").append(Build.VERSION.RELEASE)
                .append(" (api ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("webview=");
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                Class<?> wu = Class.forName("android.webkit.WebViewUpdateService");
                java.lang.reflect.Method m = null;
                try {
                    m = wu.getMethod("getCurrentWebViewPackageName");
                } catch (Throwable ignored) {
                }
                if (m != null && appCtx != null) {
                    Object pkg = m.invoke(null);
                    sb.append(String.valueOf(pkg));
                }
            }
        } catch (Throwable ignored) {
        }
        sb.append("\n=====\n");
        raw(sb.toString());
    }

    public static String versionName() {
        try {
            if (appCtx == null) return "?";
            return appCtx.getPackageManager()
                    .getPackageInfo(appCtx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    public static int versionCode() {
        try {
            if (appCtx == null) return -1;
            android.content.pm.PackageInfo pi = appCtx.getPackageManager()
                    .getPackageInfo(appCtx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 关键步骤（成功也算），低频。 */
    public static void step(String tag, String method, String msg) {
        line("STEP", tag, method, msg, null);
    }

    public static void info(String tag, String method, String msg) {
        line("INFO", tag, method, msg, null);
    }

    public static void warn(String tag, String method, String msg) {
        line("WARN", tag, method, msg, null);
    }

    /** 失败但没异常：比如「四层捕获都没命中」「返回码非 0」。 */
    public static void fail(String tag, String method, String msg) {
        line("FAIL", tag, method, msg, null);
    }

    /** 失败且带异常：完整堆栈。 */
    public static void fail(String tag, String method, String msg, Throwable t) {
        line("FAIL", tag, method, msg, t);
    }

    public static void crash(Thread thread, Throwable t) {
        String th = thread == null ? "?" : (thread.getName() + "#" + thread.getId());
        StringWriter sw = new StringWriter();
        if (t != null) t.printStackTrace(new PrintWriter(sw));
        raw("===== CRASH thread=" + th + " " + TS.format(new Date()) + " =====\n"
                + sw.toString() + "===== CRASH END =====\n");
    }

    /** 一次性把内容落盘（不带格式），内部用。 */
    private static void raw(String block) {
        if (block == null || block.isEmpty()) return;
        try {
            File f = currentFile();
            if (f == null) return;
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            rotateIfNeeded(f);
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(block.getBytes("UTF-8"));
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "write failed: " + t);
        }
    }

    private static void line(String level, String tag, String method, String msg, Throwable t) {
        try {
            StringBuilder sb = new StringBuilder(160);
            sb.append(TS.format(new Date())).append(" | ")
                    .append(Thread.currentThread().getName()).append(" | ")
                    .append(level).append(" | ")
                    .append(tag == null ? "-" : tag).append('/')
                    .append(method == null ? "-" : method)
                    .append(" | ").append(msg == null ? "" : msg).append('\n');
            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                String[] parts = sw.toString().split("\n");
                int max = Math.min(parts.length, 40);
                for (int i = 0; i < max; i++) {
                    sb.append("    at ").append(parts[i].trim().replace("at ", "")).append('\n');
                }
                if (parts.length > max) sb.append("    ... 省略 ").append(parts.length - max).append(" 行\n");
            }
            raw(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 当前写入的日志文件；按天分文件，避免单文件无限增长。 */
    public static File currentFile() {
        try {
            File dir = logDir();
            if (dir == null) return null;
            String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            return new File(dir, "diag-" + day + ".log");
        } catch (Throwable t) {
            return null;
        }
    }

    public static File logDir() {
        try {
            File root = TokenStore.rootDir();
            if (root == null) return null;
            File dir = new File(root, DIR_NAME);
            if (!dir.exists()) dir.mkdirs();
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void rotateIfNeeded(File f) {
        try {
            if (!f.exists() || f.length() < MAX_BYTES) return;
            File rolled = new File(f.getParentFile(), f.getName() + ".1");
            if (rolled.exists()) rolled.delete();
            f.renameTo(rolled);
            prune(f.getParentFile());
        } catch (Throwable ignored) {
        }
    }

    private static void prune(File dir) {
        try {
            File[] all = dir.listFiles();
            if (all == null || all.length <= KEEP) return;
            java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < all.length - KEEP; i++) all[i].delete();
        } catch (Throwable ignored) {
        }
    }

    /** 读最近 lines 行，用于设置页里的「诊断日志」弹窗。 */
    public static String tail(int lines) {
        File f = currentFile();
        if (f == null || !f.exists()) return "（还没有日志）";
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - 96 * 1024L);
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            String all = new String(buf, "UTF-8");
            String[] arr = all.split("\n");
            int from = Math.max(0, arr.length - Math.max(1, lines));
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < arr.length; i++) sb.append(arr[i]).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "（读取日志失败: " + t + "）";
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 清空日志（设置页里的按钮用）。 */
    public static void clear() {
        try {
            File dir = logDir();
            if (dir == null) return;
            File[] all = dir.listFiles();
            if (all == null) return;
            for (File f : all) f.delete();
            writeHeader();
        } catch (Throwable ignored) {
        }
    }
}
