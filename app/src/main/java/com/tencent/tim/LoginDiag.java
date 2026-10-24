package com.tencent.tim;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 登录诊断器。三种模式通用：抓号、挂机、上号。
 *
 * 要点：
 * 1) URL 轨迹：由 BaseLoginWebViewClient 与 AgentActivity 每步写入内存环形缓冲。
 *    系统 logcat 看不到 WebView 导航链，这一路是定位卡点的关键。
 * 2) su logcat：有 root 时抓系统日志里与 WebView / 崩溃 / SSL 相关的行。
 *
 * 导出到：
 *   Android/data/com.tencent.tim/files/diag_login.txt
 *
 * 界面：右上角动态挂「诊断」按钮，点击即导出并复制到剪贴板。
 * 不改布局、不改 Activity 结构，零侵入。
 */
public class LoginDiag {

    private static final int MAX_EVENTS = 400;
    private static final List<String> events = new ArrayList<String>();
    private static long startedAt = 0L;

    /** 用弱标记避免重复挂按钮导致叠层。 */
    private static String attachedKey = null;

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);

    public static synchronized void begin() {
        events.clear();
        startedAt = System.currentTimeMillis();
        add("DIAG", "begin");
    }

    public static synchronized void add(String tag, String detail) {
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

    /**
     * 动态挂诊断悬浮按钮。同一 Activity 只挂一次。
     * 抓号/挂机由 BaseLoginWebViewClient 调用，上号由 AgentActivity 调用。
     */
    public static void attachButton(final Activity act, final String gameName) {
        if (act == null) return;
        final String key = act.getClass().getName() + "@" + System.identityHashCode(act);
        synchronized (LoginDiag.class) {
            if (key.equals(attachedKey)) return;
            attachedKey = key;
        }
        try {
            Button btn = new Button(act);
            btn.setText("诊断");
            btn.setTextSize(11);
            btn.setTextColor(0xFFF5F7FA);
            btn.setAlpha(0.9f);
            btn.setBackground(TokenDialog.glass(act, 12, false));

            int pad = (int) (8 * act.getResources().getDisplayMetrics().density);
            btn.setPadding(pad, pad / 2, pad, pad / 2);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.topMargin = pad;
            lp.rightMargin = pad;

            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    exportAsync(act, gameName);
                }
            });

            ViewGroup root = (ViewGroup) act.getWindow().getDecorView();
            if (root instanceof FrameLayout) {
                root.addView(btn, lp);
            } else {
                FrameLayout wrap = new FrameLayout(act);
                wrap.addView(btn, lp);
                root.addView(wrap, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            add("DIAG", "按钮已挂载");
        } catch (Throwable t) {
            add("ERR", "挂载诊断按钮失败: " + t);
        }
    }

    /** 重置挂载标记，Activity 重建时用。 */
    public static synchronized void resetButtonFlag() {
        attachedKey = null;
    }

    private static void exportAsync(final Activity act, final String gameName) {
        add("DIAG", "用户点击导出");
        final android.widget.Toast tip = android.widget.Toast.makeText(act,
                "正在收集诊断信息...", android.widget.Toast.LENGTH_SHORT);
        tip.show();
        new Thread(new Runnable() {
            public void run() {
                final String path = export(act, gameName);
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (path == null) {
                            android.widget.Toast.makeText(act, "导出失败",
                                    android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) act.getSystemService(
                                            Context.CLIPBOARD_SERVICE);
                            StringBuilder sb = new StringBuilder();
                            for (String s : snapshot()) sb.append(s).append('\n');
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "diag", sb.toString()));
                        } catch (Throwable ignored) {
                        }
                        new android.app.AlertDialog.Builder(act)
                                .setTitle("诊断已导出")
                                .setMessage("文件:\n" + path
                                        + "\n\nURL 轨迹已复制到剪贴板，可直接粘贴发送。")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    /** 导出诊断文件，返回路径，失败返回 null。需在后台线程调用。 */
    public static String export(Context ctx, String gameName) {
        if (ctx == null) return null;
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        File out = new File(dir, "diag_login.txt");

        StringBuilder sb = new StringBuilder();
        sb.append("=== shq 登录诊断 ===\n");
        sb.append("时间: ").append(TS.format(new Date())).append("\n");
        sb.append("游戏: ").append(gameName == null ? "" : gameName).append("\n");
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("机型: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append("\n");
        sb.append("线路: ").append(Net.routeName(ctx)).append("\n");
        sb.append("节点: ").append(NodeConfig.summary()).append("\n");
        sb.append("xray: ").append(XrayCore.isAlive() ? "运行中" : "未运行").append("\n\n");

        sb.append("--- 事件轨迹 ---\n");
        List<String> snap = snapshot();
        if (snap.isEmpty()) {
            sb.append("(空)\n");
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
