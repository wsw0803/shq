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
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;

public class Updater {

    private static final int THREADS = 8;
    private static final long MIN_SIZE = 500000L;

    private static ProgressBar bar;
    private static TextView tip;
    private static volatile long total = 0;
    private static volatile long got = 0;
    private static volatile long lastUi = 0;
    private static volatile long lastT = 0;
    private static volatile long lastG = 0;
    private static volatile double speed = 0;
    private static volatile String curHost = "";
    private static volatile String pickUrl = "";
    private static volatile boolean rangeOk = false;
    private static volatile boolean aborted = false;
    private static final long[] segStart = new long[THREADS];
    private static final long[] segEnd = new long[THREADS];
    private static final long[] segDone = new long[THREADS];
    private static final Object lock = new Object();

    public static void start(final Activity act, final String url) {
        start(act, url, 0, null);
    }

    public static void start(final Activity act, final String url, final Runnable onFail) {
        start(act, url, 0, onFail);
    }

    public static void start(final Activity act, final String url, final long expectSize, final Runnable onFail) {
        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout((int) (act.getResources().getDisplayMetrics().widthPixels * 0.82f), WindowManager.LayoutParams.WRAP_CONTENT);
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
        tip.setText("正在选择最快线路...");
        tip.setTextColor(0xFF9CA3AF);
        tip.setTextSize(13);
        tip.setGravity(Gravity.CENTER);
        root.addView(tip);
        bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        root.addView(bar);
        dlg.setContentView(root);
        dlg.setCancelable(false);
        dlg.show();
        new Thread(new Runnable() {
            public void run() {
                File dir = act.getExternalFilesDir(null);
                final File out = new File(dir, "update.apk");
                final File part = new File(dir, "update.apk.part");
                final File state = new File(dir, "update.apk.state");
                boolean ok = fetch(act, buildUrls(url), expectSize, part, state, out);
                final boolean done = ok;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        dlg.dismiss();
                        if (done) install(act, out);
                        else {
                            android.widget.Toast.makeText(act, "下载失败，请稍后重试", android.widget.Toast.LENGTH_SHORT).show();
                            if (onFail != null) onFail.run();
                        }
                    }
                });
            }
        }).start();
    }

    private static java.util.List<String> buildUrls(String url) {
        java.util.List<String> list = new java.util.ArrayList<String>();
        if (url == null || url.length() == 0) return list;
        list.add(url);
        if (url.startsWith("https://github.com/") || url.startsWith("https://objects.githubusercontent.com/")) {
            list.add("https://gh-proxy.com/" + url);
            list.add("https://ghfast.top/" + url);
            list.add("https://ghproxy.net/" + url);
        }
        return list;
    }

    private static HttpURLConnection open(String url, long from, long to, int ct, int rt) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(ct);
        c.setReadTimeout(rt);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Cache-Control", "no-cache");
        if (from >= 0) c.setRequestProperty("Range", "bytes=" + from + "-" + (to >= 0 ? String.valueOf(to) : ""));
        return c;
    }

    private static long parseTotal(HttpURLConnection c) {
        String cr = c.getHeaderField("Content-Range");
        if (cr != null) {
            int i = cr.lastIndexOf(47);
            if (i > 0) {
                try { return Long.parseLong(cr.substring(i + 1).trim()); } catch (Exception e) {}
            }
        }
        return -1;
    }

    private static String hostOf(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return ""; }
    }

    private static boolean probe(final java.util.List<String> urls, final long expectSize) {
        final Object foundLock = new Object();
        final boolean[] found = new boolean[1];
        for (int i = 0; i < urls.size(); i++) {
            final String u = urls.get(i);
            new Thread(new Runnable() {
                public void run() {
                    HttpURLConnection c = null;
                    try {
                        c = open(u, 0, 0, 3500, 4500);
                        int code = c.getResponseCode();
                        long t = -1;
                        boolean rg = false;
                        if (code == 206) {
                            t = parseTotal(c);
                            rg = t > 0;
                        } else if (code == 200) {
                            t = c.getContentLength();
                        }
                        if (t <= MIN_SIZE) return;
                        if (expectSize > 0 && t != expectSize) return;
                        synchronized (foundLock) {
                            if (found[0]) return;
                            found[0] = true;
                            total = t;
                            rangeOk = rg;
                            pickUrl = u;
                            curHost = hostOf(u);
                        }
                    } catch (Exception e) {
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
            }).start();
        }
        long deadline = System.currentTimeMillis() + 12000L;
        while (System.currentTimeMillis() < deadline) {
            synchronized (foundLock) { if (found[0]) return true; }
            try { Thread.sleep(120L); } catch (Exception e) {}
        }
        synchronized (foundLock) { return found[0]; }
    }

    private static long loadState(File state, long t) {
        try {
            if (!state.exists()) return -1;
            byte[] b = new byte[512];
            java.io.FileInputStream in = new java.io.FileInputStream(state);
            int n = in.read(b);
            in.close();
            if (n <= 0) return -1;
            String[] ps = new String(b, 0, n, "UTF-8").trim().split("\\s+");
            if (ps.length < 2 + THREADS) return -1;
            if (Long.parseLong(ps[0]) != t) return -1;
            if (Long.parseLong(ps[1]) != total) return -1;
            long sum = 0;
            for (int i = 0; i < THREADS; i++) {
                long v = Long.parseLong(ps[i + 2]);
                long len = segEnd[i] - segStart[i] + 1;
                if (v < 0) v = 0;
                if (v > len) v = len;
                segDone[i] = v;
                sum += v;
            }
            return sum;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void saveState(File state, long t) {
        synchronized (lock) {
            File tmp = new File(state.getAbsolutePath() + ".tmp");
            try {
                StringBuilder sb = new StringBuilder();
                sb.append(t).append((char) 32).append(total);
                for (int i = 0; i < THREADS; i++) sb.append((char) 32).append(segDone[i]);
                sb.append((char) 10);
                FileOutputStream fos = new FileOutputStream(tmp);
                fos.write(sb.toString().getBytes("UTF-8"));
                fos.flush();
                fos.close();
                if (state.exists()) state.delete();
                tmp.renameTo(state);
            } catch (Exception e) {
                try { tmp.delete(); } catch (Exception e1) {}
            }
        }
    }

    private static void ui(final Activity act, final boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastUi < 200) return;
        lastUi = now;
        if (lastT > 0) {
            long dt = now - lastT;
            if (dt >= 400) {
                long dg = got - lastG;
                if (dg >= 0) speed = dg * 1000.0 / dt;
                lastT = now;
                lastG = got;
            }
        } else {
            lastT = now;
            lastG = got;
        }
        final long t = total;
        final long g = got;
        final int pct = t > 0 ? (int) (g * 1000 / t) : 0;
        final String sp = speed > 0 ? String.format("%.1f MB/s", speed / 1048576.0) : "0 MB/s";
        act.runOnUiThread(new Runnable() {
            public void run() {
                if (bar != null) bar.setProgress(pct);
                if (tip != null) tip.setText((pct / 10) + "." + (pct % 10) + "% · " + sp + " · " + curHost);
            }
        });
    }

    private static boolean fetch(Activity act, java.util.List<String> urls, long expectSize, File part, File state, File out) {
        if (urls == null || urls.isEmpty()) return false;
        if (!probe(urls, expectSize)) return false;
        final String src = pickUrl;
        String bare = src;
        if (bare.startsWith("https://gh-proxy.com/")) bare = bare.substring(20);
        else if (bare.startsWith("https://ghfast.top/")) bare = bare.substring(19);
        else if (bare.startsWith("https://ghproxy.net/")) bare = bare.substring(20);
        final String bareSrc = bare;
        if (out.exists() && out.length() == total && total > MIN_SIZE) { got = total; return true; }
        if (!rangeOk) {
            if (!single(act, src, bareSrc, expectSize, part, out)) return false;
        } else {
            got = 0;
            lastT = 0;
            for (int i = 0; i < THREADS; i++) {
                segStart[i] = total * i / THREADS;
                segEnd[i] = total * (i + 1) / THREADS - 1;
                segDone[i] = 0;
            }
            if (part.exists() && state.exists()) {
                long sum = loadState(state, 0);
                if (sum < 0) { part.delete(); state.delete(); } else got = sum;
            } else {
                if (part.exists()) part.delete();
                if (state.exists()) state.delete();
            }
            try {
                RandomAccessFile raf = new RandomAccessFile(part, "rw");
                if (raf.length() != total) raf.setLength(total);
                raf.close();
            } catch (Exception e) { return false; }
            ui(act, true);
            final CountDownLatch latch = new CountDownLatch(THREADS);
            for (int i = 0; i < THREADS; i++) {
                final int idx = i;
                new Thread(new Runnable() {
                    public void run() {
                        try { worker(act, src, bareSrc, part, state, idx); } catch (Throwable e) {} finally { latch.countDown(); }
                    }
                }).start();
            }
            try { latch.await(); } catch (Exception e) { return false; }
            saveState(state, 0);
            if (aborted || got < total) return false;
        }
        try {
            if (out.exists() && !out.delete()) return false;
            if (!part.renameTo(out)) return false;
            if (out.length() != total) return false;
            state.delete();
            return true;
        } catch (Exception e) { return false; }
    }

    private static void worker(Activity act, String src, String bare, File part, File state, int idx) {
        long len = segEnd[idx] - segStart[idx] + 1;
        int tries = 0;
        while (segDone[idx] < len && !aborted) {
            HttpURLConnection c = null;
            InputStream in = null;
            RandomAccessFile raf = null;
            try {
                long pos = segStart[idx] + segDone[idx];
                c = open(src, pos, segEnd[idx], 8000, 15000);
                int code = c.getResponseCode();
                if (code == 403 || code == 404 || code == 416) {
                    c.disconnect();
                    c = open(bare, pos, segEnd[idx], 8000, 15000);
                    code = c.getResponseCode();
                }
                if (code != 206 && code != 200) throw new Exception("http " + code);
                in = c.getInputStream();
                raf = new RandomAccessFile(part, "rw");
                raf.seek(pos);
                byte[] buf = new byte[131072];
                while (segDone[idx] < len && !aborted) {
                    int n = in.read(buf);
                    if (n <= 0) break;
                    long room = len - segDone[idx];
                    if (n > room) n = (int) room;
                    raf.write(buf, 0, n);
                    segDone[idx] += n;
                    synchronized (lock) { got += n; }
                    ui(act, false);
                }
            } catch (Exception e) {
            } finally {
                try { if (raf != null) raf.close(); } catch (Exception e1) {}
                try { if (in != null) in.close(); } catch (Exception e2) {}
                if (c != null) c.disconnect();
            }
            if (segDone[idx] >= len) break;
            tries++;
            if (tries > 30) break;
            try { Thread.sleep(Math.min(250L * tries, 2000L)); } catch (Exception e) {}
        }
    }

    private static boolean single(Activity act, String src, String bare, long expectSize, File part, File out) {
        String[] cand = new String[]{ src, bare };
        for (int i = 0; i < cand.length; i++) {
            HttpURLConnection c = null;
            InputStream in = null;
            FileOutputStream fos = null;
            try {
                c = open(cand[i], -1, -1, 9000, 20000);
                if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                long len = c.getContentLength();
                in = c.getInputStream();
                fos = new FileOutputStream(part);
                byte[] buf = new byte[131072];
                int n;
                long g = 0;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    g += n;
                    got = g;
                    ui(act, false);
                }
                fos.flush();
                if (g <= MIN_SIZE) continue;
                if (expectSize > 0 && g != expectSize) continue;
                if (len > 0 && g != len) continue;
                total = g;
                if (out.exists()) out.delete();
                return part.renameTo(out);
            } catch (Exception e) {
            } finally {
                try { if (fos != null) fos.close(); } catch (Exception e1) {}
                try { if (in != null) in.close(); } catch (Exception e2) {}
                if (c != null) c.disconnect();
            }
        }
        return false;
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
