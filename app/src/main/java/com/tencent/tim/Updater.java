package com.tencent.tim;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
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
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多线程分片下载 + 断点续传。
 *
 * 相比原实现的改动：
 *
 * 1) 所有下载状态从 static 改为实例字段（第 13 项）
 *    原来是 static 的 total/got/segDone/aborted，同时触发两次更新、
 *    或 Activity 重建后再次点击，会互相覆盖状态，续传文件被写坏。
 *    现在每次 start() 新建一个 Job，状态隔离。
 *
 * 2) 移除第三方 GitHub 代理（第 14 项）
 *    原来依次尝试 gh-proxy.com / ghfast.top / ghproxy.net，
 *    这些代理可以替换 APK 内容，而代码没有任何校验，
 *    等于把安装包的完整性交给了不受控的第三方。
 *    现在只从 GitHub 官方域名下载。
 *
 * 3) 安装前校验签名（配合签名外置）
 *    下载完成后比对 APK 的签名证书是否与当前应用一致，不一致就拒绝安装，
 *    避免任何渠道（DNS 劫持、镜像站）塞入伪造更新包。
 */
public class Updater {

    private static final int THREADS = 8;
    private static final long MIN_SIZE = 500000L;

    /** 一次下载任务的全部状态，随 start() 创建，避免并发互相污染。 */
    private static final class Job {
        final AtomicLong total = new AtomicLong(0);
        final AtomicLong got = new AtomicLong(0);
        final AtomicBoolean aborted = new AtomicBoolean(false);
        final long[] segStart = new long[THREADS];
        final long[] segEnd = new long[THREADS];
        final long[] segDone = new long[THREADS];
        final Object lock = new Object();

        volatile long lastUi = 0;
        volatile long lastT = 0;
        volatile long lastG = 0;
        volatile double speed = 0;
        volatile String curHost = "";
        volatile String pickUrl = "";
        volatile boolean rangeOk = false;
    }

    public static void start(final Activity act, final String url) {
        start(act, url, 0, null);
    }

    public static void start(final Activity act, final String url, final Runnable onFail) {
        start(act, url, 0, onFail);
    }

    public static void start(final Activity act, final String url, final long expectSize, final Runnable onFail) {
        final Job job = new Job();

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

        final TextView tip = new TextView(act);
        tip.setText("正在选择最快线路...");
        tip.setTextColor(0xFF9CA3AF);
        tip.setTextSize(13);
        tip.setGravity(Gravity.CENTER);
        root.addView(tip);

        final ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
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

                boolean ok = fetch(act, job, bar, tip, buildUrls(url), expectSize, part, state, out);

                // 下载成功还要校验签名，防止代理/劫持替换安装包
                if (ok && !signatureMatches(act, out)) {
                    ok = false;
                    try { out.delete(); } catch (Throwable ignored) {}
                }

                final boolean done = ok;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        dlg.dismiss();
                        if (done) {
                            install(act, out);
                        } else {
                            android.widget.Toast.makeText(act, "下载失败或安装包校验未通过，请稍后重试", android.widget.Toast.LENGTH_SHORT).show();
                            if (onFail != null) onFail.run();
                        }
                    }
                });
            }
        }).start();
    }

    /** 只使用 GitHub 官方地址，不再经由第三方代理。 */
    private static java.util.List<String> buildUrls(String url) {
        java.util.List<String> list = new java.util.ArrayList<String>();
        if (url == null || url.length() == 0) return list;
        list.add(url);
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

    private static boolean probe(final Job job, final java.util.List<String> urls, final long expectSize) {
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
                            job.total.set(t);
                            job.rangeOk = rg;
                            job.pickUrl = u;
                            job.curHost = hostOf(u);
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

    private static long loadState(Job job, File state, long t) {
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
            if (Long.parseLong(ps[1]) != job.total.get()) return -1;
            long sum = 0;
            for (int i = 0; i < THREADS; i++) {
                long v = Long.parseLong(ps[i + 2]);
                long len = job.segEnd[i] - job.segStart[i] + 1;
                if (v < 0) v = 0;
                if (v > len) v = len;
                job.segDone[i] = v;
                sum += v;
            }
            return sum;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void saveState(Job job, File state, long t) {
        synchronized (job.lock) {
            File tmp = new File(state.getAbsolutePath() + ".tmp");
            try {
                StringBuilder sb = new StringBuilder();
                sb.append(t).append((char) 32).append(job.total.get());
                for (int i = 0; i < THREADS; i++) sb.append((char) 32).append(job.segDone[i]);
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

    private static void ui(final Activity act, final Job job, final ProgressBar bar, final TextView tip, final boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - job.lastUi < 200) return;
        job.lastUi = now;
        if (job.lastT > 0) {
            long dt = now - job.lastT;
            if (dt >= 400) {
                long dg = job.got.get() - job.lastG;
                if (dg >= 0) job.speed = dg * 1000.0 / dt;
                job.lastT = now;
                job.lastG = job.got.get();
            }
        } else {
            job.lastT = now;
            job.lastG = job.got.get();
        }
        final long t = job.total.get();
        final long g = job.got.get();
        final int pct = t > 0 ? (int) (g * 1000 / t) : 0;
        final String sp = job.speed > 0 ? String.format("%.1f MB/s", job.speed / 1048576.0) : "0 MB/s";
        final String host = job.curHost;
        act.runOnUiThread(new Runnable() {
            public void run() {
                if (bar != null) bar.setProgress(pct);
                if (tip != null) tip.setText((pct / 10) + "." + (pct % 10) + "% · " + sp + " · " + host);
            }
        });
    }

    private static boolean fetch(Activity act, Job job, ProgressBar bar, TextView tip,
                                 java.util.List<String> urls, long expectSize,
                                 File part, File state, File out) {
        if (urls == null || urls.isEmpty()) return false;
        if (!probe(job, urls, expectSize)) return false;

        final String src = job.pickUrl;
        final long total = job.total.get();

        if (out.exists() && out.length() == total && total > MIN_SIZE) {
            job.got.set(total);
            return true;
        }

        if (!job.rangeOk) {
            if (!single(act, job, bar, tip, src, expectSize, part, out)) return false;
        } else {
            job.got.set(0);
            job.lastT = 0;
            for (int i = 0; i < THREADS; i++) {
                job.segStart[i] = total * i / THREADS;
                job.segEnd[i] = total * (i + 1) / THREADS - 1;
                job.segDone[i] = 0;
            }
            if (part.exists() && state.exists()) {
                long sum = loadState(job, state, 0);
                if (sum < 0) {
                    part.delete();
                    state.delete();
                } else {
                    job.got.set(sum);
                }
            } else {
                if (part.exists()) part.delete();
                if (state.exists()) state.delete();
            }
            try {
                RandomAccessFile raf = new RandomAccessFile(part, "rw");
                if (raf.length() != total) raf.setLength(total);
                raf.close();
            } catch (Exception e) {
                return false;
            }
            ui(act, job, bar, tip, true);

            final CountDownLatch latch = new CountDownLatch(THREADS);
            for (int i = 0; i < THREADS; i++) {
                final int idx = i;
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            worker(act, job, bar, tip, src, part, state, idx);
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
            saveState(job, state, 0);
            if (job.aborted.get() || job.got.get() < total) return false;
        }

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

    private static void worker(Activity act, Job job, ProgressBar bar, TextView tip,
                               String src, File part, File state, int idx) {
        long len = job.segEnd[idx] - job.segStart[idx] + 1;
        int tries = 0;
        while (job.segDone[idx] < len && !job.aborted.get()) {
            HttpURLConnection c = null;
            InputStream in = null;
            RandomAccessFile raf = null;
            try {
                long pos = job.segStart[idx] + job.segDone[idx];
                c = open(src, pos, job.segEnd[idx], 8000, 15000);
                int code = c.getResponseCode();
                if (code != 206 && code != 200) throw new Exception("http " + code);
                in = c.getInputStream();
                raf = new RandomAccessFile(part, "rw");
                raf.seek(pos);
                byte[] buf = new byte[131072];
                while (job.segDone[idx] < len && !job.aborted.get()) {
                    int n = in.read(buf);
                    if (n <= 0) break;
                    long room = len - job.segDone[idx];
                    if (n > room) n = (int) room;
                    raf.write(buf, 0, n);
                    job.segDone[idx] += n;
                    job.got.addAndGet(n);
                    ui(act, job, bar, tip, false);
                }
            } catch (Exception e) {
            } finally {
                try { if (raf != null) raf.close(); } catch (Exception e1) {}
                try { if (in != null) in.close(); } catch (Exception e2) {}
                if (c != null) c.disconnect();
            }
            if (job.segDone[idx] >= len) break;
            tries++;
            if (tries > 30) break;
            try {
                Thread.sleep(Math.min(250L * tries, 2000L));
            } catch (Exception e) {}
        }
    }

    private static boolean single(Activity act, Job job, ProgressBar bar, TextView tip,
                                  String src, long expectSize, File part, File out) {
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            c = open(src, -1, -1, 9000, 20000);
            if (c.getResponseCode() != 200) return false;
            long len = c.getContentLength();
            in = c.getInputStream();
            fos = new FileOutputStream(part);
            byte[] buf = new byte[131072];
            int n;
            long g = 0;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                g += n;
                job.got.set(g);
                ui(act, job, bar, tip, false);
            }
            fos.flush();
            if (g <= MIN_SIZE) return false;
            if (expectSize > 0 && g != expectSize) return false;
            if (len > 0 && g != len) return false;
            job.total.set(g);
            if (out.exists()) out.delete();
            return part.renameTo(out);
        } catch (Exception e) {
            return false;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception e1) {}
            try { if (in != null) in.close(); } catch (Exception e2) {}
            if (c != null) c.disconnect();
        }
    }

    /**
     * 校验下载到的 APK 是否由与本应用相同的证书签名。
     *
     * 这一步是必要的：即使只走 GitHub 官方域名，仍可能遭遇 DNS 劫持或镜像污染。
     * 签名一致才允许安装，能挡住绝大多数伪造更新包。
     */
    private static boolean signatureMatches(Activity act, File apk) {
        try {
            PackageManager pm = act.getPackageManager();
            PackageInfo self = pm.getPackageInfo(act.getPackageName(), PackageManager.GET_SIGNATURES);
            Signature[] mine = self.signatures;
            if (mine == null || mine.length == 0) return false;

            PackageInfo target = pm.getPackageArchiveInfo(
                    apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            if (target == null) return false;
            Signature[] theirs = target.signatures;
            if (theirs == null || theirs.length == 0) return false;

            // 逐个比对证书指纹
            for (Signature a : mine) {
                String fa = digest(a.toByteArray());
                for (Signature b : theirs) {
                    if (fa.equals(digest(b.toByteArray()))) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            // 校验过程本身异常时按失败处理，宁可让用户重试，也不装未知包
            return false;
        }
    }

    private static String digest(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
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
