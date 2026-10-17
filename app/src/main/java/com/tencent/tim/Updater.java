package com.tencent.tim;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class Updater {

    private static final String RAW = "https://gh-proxy.com/https://raw.githubusercontent.com/wsw0803/shq/main/release/latest.apk";

    private static ProgressBar bar;
    private static TextView tip;

    public static void start(final Activity act, final String fallback) {
        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout((int) (act.getResources().getDisplayMetrics().widthPixels * 0.8f), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(TokenDialog.glass(act, 22, true));
        int p = (int) (16 * act.getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(act);
        title.setText("正在下载更新");
        title.setTextColor(0xFFF5F7FA);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        tip = new TextView(act);
        tip.setText("0%");
        tip.setTextColor(0xFF9CA3AF);
        tip.setTextSize(13);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, (int) (8 * act.getResources().getDisplayMetrics().density), 0, (int) (10 * act.getResources().getDisplayMetrics().density));
        root.addView(tip);

        bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (6 * act.getResources().getDisplayMetrics().density)));

        dlg.setContentView(root);
        dlg.setCancelable(false);
        dlg.show();

        new Thread(new Runnable() {
            public void run() {
                File out = new File(act.getExternalFilesDir(null), "update.apk");
                boolean ok = grab(act, RAW, out);
                if (!ok && fallback != null && fallback.length() > 0) {
                    ok = grab(act, "https://gh-proxy.com/" + fallback, out);
                }
                final boolean done = ok;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        dlg.dismiss();
                        if (done) {
                            install(act, out);
                        } else {
                            android.widget.Toast.makeText(act, "下载失败，请稍后重试", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    private static boolean grab(final Activity act, String urlStr, File out) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() != 200) return false;
            int total = conn.getContentLength();
            in = conn.getInputStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            long got = 0;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                got += n;
                if (total > 0) {
                    final int pct = (int) (got * 100 / total);
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            if (bar != null) bar.setProgress(pct);
                            if (tip != null) tip.setText(pct + "%");
                        }
                    });
                }
            }
            fos.flush();
            return out.length() > 100000;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception e1) {}
            try { if (in != null) in.close(); } catch (Exception e2) {}
            if (conn != null) conn.disconnect();
        }
    }

    private static void install(Activity act, File f) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                uri = FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", f);
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(f);
            }
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(it);
        } catch (Exception e) {
            android.widget.Toast.makeText(act, "安装失败: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
