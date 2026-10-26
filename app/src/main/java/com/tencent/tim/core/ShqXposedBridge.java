package com.tencent.tim.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 查询目标进程里的 Xposed 接管状态。
 *
 * 为什么需要跨进程：
 *
 *   XposedEntry 的 hook 计数产生在「游戏进程」里，
 *   而提示用户发生在「本 App 进程」里。两个进程的静态变量互不可见，
 *   直接在 App 里读 XposedEntry 的静态字段永远是 0。
 *
 * 做法：
 *
 *   XposedEntry 在目标进程把计数写进该游戏自己的
 *   /data/data/<pkg>/files/shq_xposed.status，
 *   本 App 通过 root 读这一个文件。
 *
 *   之所以写到游戏自己的目录而不是共享目录：
 *   被注入的代码以游戏的 uid 运行，无权写 com.tencent.tim 的数据目录；
 *   而 root 可以读任意目录，所以「游戏写、root 读」是唯一稳的组合。
 *
 * 拿不到时返回 UNKNOWN，不假装成功 —— 界面据此显示「无法确认」，
 * 而不是错误地告诉用户已经接好了。
 */
public final class ShqXposedBridge {

    /** 无法确认：没 root、文件不存在、或内容不可解析。 */
    public static final int UNKNOWN = -1;

    private ShqXposedBridge() {
    }

    public static int hookedMethodCount(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return UNKNOWN;
        }
        String out = runSu("cat /data/data/" + pkg + "/files/shq_xposed.status 2>/dev/null");
        if (out == null) {
            return UNKNOWN;
        }
        String s = out.trim();
        int eq = s.indexOf('=');
        if (eq < 0) {
            return UNKNOWN;
        }
        String num = s.substring(eq + 1).trim();
        int sp = num.indexOf(' ');
        if (sp > 0) {
            num = num.substring(0, sp);
        }
        try {
            return Integer.parseInt(num);
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }


    /**
     * 读游戏进程写的诊断日志。
     *
     * XposedEntry 的 Diag 写到 /sdcard/Android/data/<游戏包名>/files/diag.log，
     * 但那是另一个 App 的外部目录 —— Android 11+ 的分区存储下，
     * 本 App 没有权限直接读。
     *
     * 用 root 读是唯一稳的路：su 可以跨应用目录。
     * 没 root 就返回空，界面显示「无法读取」，不谎报。
     *
     * @param lines 最多返回多少行（取尾部）
     */
    public static java.util.List<String> readInprocLog(String pkg, int lines) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (pkg == null || pkg.isEmpty()) {
            return out;
        }
        // 两个可能的路径：外部 files 优先（Diag 写的），退回内部 files
        String[] paths = {
                "/sdcard/Android/data/" + pkg + "/files/diag.log",
                "/storage/emulated/0/Android/data/" + pkg + "/files/diag.log",
                "/data/data/" + pkg + "/files/diag.log",
        };
        for (String path : paths) {
            String body = runSu("tail -n " + Math.max(1, lines) + " " + path + " 2>/dev/null");
            if (body != null && !body.trim().isEmpty()) {
                for (String ln : body.split("\n")) {
                    if (!ln.trim().isEmpty()) {
                        out.add(ln);
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            }
        }
        return out;
    }

    /** 清空游戏进程的日志。 */
    public static boolean clearInprocLog(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        runSu("rm -f /sdcard/Android/data/" + pkg + "/files/diag.log "
                + "/storage/emulated/0/Android/data/" + pkg + "/files/diag.log "
                + "/data/data/" + pkg + "/files/diag.log 2>/dev/null");
        return true;
    }

    /** 有 root 就执行，没有就返回 null。绝不抛。 */
    private static String runSu(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
