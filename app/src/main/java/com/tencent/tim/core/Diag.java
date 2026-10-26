package com.tencent.tim.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 跨进程诊断日志。
 *
 * 为什么不用原来的 LoginDiag：
 *
 *   LoginDiag.events 是进程内静态 ArrayList，而本项目现在有两个进程在写日志：
 *     - App 进程（com.tencent.tim）
 *     - 游戏进程（XposedEntry 被注入到 com.tencent.tmgp.dfm 等）
 *   静态字段互不可见，游戏进程写的东西 App 永远读不到。
 *   之前只能退回 XposedBridge.log + 导出整个 logcat，
 *   一次 4MB、绝大部分是系统噪音，还被 LSPosed 的日志级别卡住。
 *
 * 做法：两边都往同一个文件追加，各写各的，不共享内存。
 *
 *   路径：/sdcard/Android/data/<当前包名>/files/diag.log
 *   谁写：用 ActivityThread.currentApplication() 拿 Context，
 *         所以不需要调用方传 Context，XposedEntry 里也能直接用。
 *   并发：单个 write 调用，行级原子性够用；崩溃最多丢最后一行。
 *
 * 刻意不做的事：
 *
 *   - 不读 SharedPreferences 开关。游戏进程里读不到 App 的设置，
 *     而且诊断日志本来就是排障时才开的，全局常开更好定位。
 *   - 不缓冲。每次直接 flush，进程被杀也能留下已发生的步骤。
 *   - 不做日志级别。只有一条路径，减少判断。
 */
public final class Diag {

    /** 单文件上限，超过就截断重开，避免无限增长。 */
    private static final long MAX_BYTES = 512 * 1024L;

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA);

    /** 缓存写入目标，避免每行都解析一次 Context。 */
    private static File cached;

    private Diag() {
    }

    /**
     * 取日志文件。用反射拿 Context，避免编译期依赖 android.content。
     * 拿不到就返回 null，调用方静默跳过。
     */
    private static synchronized File file() {
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app == null) {
                return null;
            }
            Object dir = app.getClass().getMethod("getExternalFilesDir", String.class)
                    .invoke(app, (Object) null);
            if (!(dir instanceof File)) {
                dir = app.getClass().getMethod("getFilesDir").invoke(app);
            }
            if (!(dir instanceof File)) {
                return null;
            }
            cached = new File((File) dir, "diag.log");
            return cached;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写一行。tag 用来筛，detail 不带换行。 */
    public static void log(String tag, String detail) {
        File f = file();
        if (f == null) {
            return;
        }
        String line = TS.format(new Date()) + " [" + tag + "] "
                + (detail == null ? "" : detail.replace('\n', ' ')) + "\n";
        try {
            if (f.length() > MAX_BYTES) {
                // 截断而不是删除：保留最近的部分
                File old = new File(f.getParentFile(), "diag.old.log");
                if (old.exists()) {
                    old.delete();
                }
                f.renameTo(old);
            }
            FileOutputStream fos = new FileOutputStream(f, true);
            try {
                Writer w = new OutputStreamWriter(fos, "UTF-8");
                w.write(line);
                w.flush();
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 读回最近 n 行，供界面展示或导出。 */
    public static List<String> tail(int n) {
        List<String> out = new ArrayList<String>();
        File f = file();
        if (f == null || !f.exists()) {
            return out;
        }
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String ln;
                while ((ln = r.readLine()) != null) {
                    out.add(ln);
                    if (out.size() > n) {
                        out.remove(0);
                    }
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 清空。 */
    public static void clear() {
        File f = file();
        if (f != null && f.exists()) {
            f.delete();
        }
    }

    public static File currentFile() {
        return file();
    }
}
