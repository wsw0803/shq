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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class Updater {

    private static final int THREADS = 6;
    private static final String REL = "release/latest.apk";
    private static final String[] BASE = new String[]{
            "https://gh-proxy.com/https://raw.githubusercontent.com/wsw0803/shq/main/",
            "https://ghfast.top/https://raw.githubusercontent.com/wsw0803/shq/main/",
            "https://ghproxy.net/https://raw.githubusercontent.com/wsw0803/shq/main/",
            "https://raw.githubusercontent.com/wsw0803/shq/main/",
            "https://cdn.jsdelivr.net/gh/wsw0803/shq@main/"
    };

    private static ProgressBar bar;
    private static TextView tip;
    private static volatile long total = 0;
    private static volatile long got = 0;
    private static volatile long lastUi = 0;
    private static volatile long lastSave = 0;
    private static volatile long lastT = 0;
    private static volatile long lastG = 0;
    private static volatile double speed = 0;
    private static volatile String curHost = "mirror";
    private static volatile boolean rangeOk = false;
    private static volatile boolean aborted = false;
    private static volatile Runnable failHook = null;
    private static final long[] segStart = new long[THREADS];
    private static final long[] segEnd = new long[THREADS];
    private static final long[] segDone = new long[THREADS];
    private static final Object lock = new Object();

    public static void start(final Activity act, final String fallback) {
        start(act, fallback, null);
    }

    public static void start(final Activity act, final String fallback, final Runnable onFail) {
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
        tip.setText("正在连接镜像...");
        tip.setTextColor(0xFF9CA3AF);
        tip.setTextSize(13);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, (int) (8 * act.getResources().getDisplayMetrics().density), 0, (int) (10 * act.getResources().getDisplayMetrics().density));
        root.addView(tip);

        bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (6 * act.getResources().getDisplayMetrics().density)));

        dlg.setContentView(root);
        dlg.setCancelable(false);
        dlg.show();

        new Thread(new Runnable() {
            public void run() {
                final File dir = act.getExternalFilesDir(null);
                final File out = new File(dir, "update.apk");
                final File part = new File(dir, "update.apk.part");
                final File state = new File(dir, "update.apk.state");
                failHook = onFail;
                boolean ok = fetch(act, buildUrls(fallback), part, state, out);
                final boolean done = ok;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        dlg.dismiss();
                        if (done) {
                            install(act, out);
                        } else {
                            android.widget.Toast.makeText(act, "下载失败，请稍后重试", android.widget.Toast.LENGTH_SHORT).show();
                            if (failHook != null) failHook.run();
                        }
                    }
                });
            }
        }).start();
    }

    private static List<String> buildUrls(String fallback) {
        List<String> list = new ArrayList<String>();
        if (fallback != null && fallback.startsWith("http")) list.add(fallback);
        for (int i = 0; i < BASE.length; i++) list.add(BASE[i] + REL);
        if (fallback != null && fallback.length() > 0 && !fallback.startsWith("http")) {
            for (int i = 0; i < BASE.length; i++) list.add(BASE[i] + fallback);
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
        try { return new URL(url).getHost(); } catch (Exception e) { return "mirror"; }
    }

    private static long probe(List<String> urls) {
        long best = -1;
        String bestHost = "mirror";
        for (int i = 0; i < urls.size(); i++) {
            HttpURLConnection c = null;
            try {
                c = open(urls.get(i), 0, 0, 2000, 3000);
                int code = c.getResponseCode();
                long t = parseTotal(c);
                if (code == 206 && t > 0) {
                    rangeOk = true;
                    curHost = hostOf(urls.get(i));
                    return t;
                }
                if (code == 200) {
                    long len = c.getContentLength();
                    if (len > 0 && best < 0) { best = len; bestHost = hostOf(urls.get(i)); }
                }
            } catch (Exception e) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        curHost = bestHost;
        return best;
    }

    private static long loadState(File state, long t) {
        try {
            if (!state.exists()) return -1;
            byte[] b = new byte[256];
            FileInputStream in = new FileInputStream(state);
            int n = in.read(b);
            in.close();
            if (n <= 0) return -1;
            String[] ps = new String(b, 0, n).trim().split("\\s+");
            if (ps.length < 1 + THREADS) return -1;
            if (Long.parseLong(ps[0]) != t) return -1;
            long sum = 0;
            for (int i = 0; i < THREADS; i++) {
                long v = Long.parseLong(ps[i + 1]);
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

    private static void saveState(File state, long t, boolean force) {
        if (!force && System.currentTimeMillis() - lastSave < 1500) return;
        synchronized (lock) {
            if (!force && System.currentTimeMillis() - lastSave < 1500) return;
            lastSave = System.currentTimeMillis();
            File tmp = new File(state.getAbsolutePath() + ".tmp");
            try {
                StringBuilder sb = new StringBuilder();
                sb.append(t);
                for (int i = 0; i < THREADS; i++) sb.append(" ").append(segDone[i]);
                sb.append("\n");
                FileOutputStream fos = new FileOutputStream(tmp);
                fos.write(sb.toString().getBytes("UTF-8"));
                fos.flush();
                fos.close();
                if (state.exists() && !state.delete()) return;
                tmp.renameTo(state);
            } catch (Exception e) {
                try { tmp.delete(); } catch (Exception e1) {}
            }
        }
    }

    private static void ui(final Activity act, final boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastUi < 300) return;
        lastUi = now;
        if (lastT > 0) {
            long dt = now - lastT;
            if (dt >= 500) {
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

    private static boolean fetch(final Activity act, List<String> urls, final File part, final File state, File out) {
        total = probe(urls);
        if (total <= 100000) return false;
        if (out.exists() && out.length() == total) { got = total; return true; }
        got = 0;
        lastT = 0;
        for (int i = 0; i < THREADS; i++) {
            segStart[i] = total * i / THREADS;
            segEnd[i] = total * (i + 1) / THREADS - 1;
            segDone[i] = 0;
        }
        if (!rangeOk) return single(act, urls, part, state, out);

        if (part.exists() && state.exists()) {
            long sum = loadState(state, total);
            if (sum < 0) {
                try { part.delete(); } catch (Exception e) {}
                try { state.delete(); } catch (Exception e) {}
            } else {
                got = sum;
            }
        }
        try {
            RandomAccessFile raf = new RandomAccessFile(part, "rw");
            if (raf.length() != total) raf.setLength(total);
            raf.close();
        } catch (Exception e) {
            return false;
        }
        saveState(state, total, true);
        ui(act, true);

        final List<String> u = urls;
        final Activity a = act;
        final File st = state;
        final CountDownLatch latch = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        worker(a, u, part, st, idx);
                    } catch (Throwable e) {
                    } finally {
                        latch.countDown();
                    }
                }
            }).start();
        }
        try {
            latch.await();
        } catch (Exception e) {
            return false;
        }
        if (aborted || got < total) return false;
        try {
            if (out.exists() && !out.delete()) return false;
            if (!part.renameTo(out)) return false;
            if (out.length() != total) return false;
            state.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void worker(Activity act, List<String> urls, File part, File state, int idx) {
        long len = segEnd[idx] - segStart[idx] + 1;
        int tries = 0;
        while (segDone[idx] < len && !aborted) {
            HttpURLConnection c = null;
            InputStream in = null;
            RandomAccessFile raf = null;
            try {
                String url = urls.get(Math.abs(tries) % urls.size());
                long pos = segStart[idx] + segDone[idx];
                c = open(url, pos, segEnd[idx], 12000, 20000);
                int code = c.getResponseCode();
                if (code != 206 && code != 200) throw new Exception("http " + code);
                curHost = hostOf(url);
                in = c.getInputStream();
                raf = new RandomAccessFile(part, "rw");
                raf.seek(pos);
                byte[] buf = new byte[65536];
                while (segDone[idx] < len && !aborted) {
                    int n = in.read(buf);
                    if (n <= 0) break;
                    long room = len - segDone[idx];
                    if (n > room) n = (int) room;
                    raf.write(buf, 0, n);
                    segDone[idx] += n;
                    synchronized (lock) { got += n; }
                    saveState(state, total, false);
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
            if (tries > urls.size() * 4) break;
            try { Thread.sleep(Math.min(400L * tries, 3000L)); } catch (Exception e) {}
        }
        saveState(state, total, true);
    }

    private static boolean single(Activity act, List<String> urls, File part, File state, File out) {
        for (int i = 0; i < urls.size(); i++) {
            HttpURLConnection c = null;
            InputStream in = null;
            FileOutputStream fos = null;
            try {
                c = open(urls.get(i), -1, -1, 12000, 25000);
                if (c.getResponseCode() != 200) continue;
                curHost = hostOf(urls.get(i));
                long len = c.getContentLength();
                in = c.getInputStream();
                fos = new FileOutputStream(part);
                byte[] buf = new byte[65536];
                int n;
                long g = 0;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    g += n;
                    got = g;
                    ui(act, false);
                }
                fos.flush();
                if (g <= 100000) continue;
                if (len > 0 && g != len) continue;
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
