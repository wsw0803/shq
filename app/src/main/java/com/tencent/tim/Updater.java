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

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多线程分片下载 + 断点续传 + 安装。
 *
 * 本次修复的几处卡住问题：
 *
 * 1) 代理准备最多等 10 秒，超时按当前线路继续，不阻塞。
 * 2) 探测阶段失败时明确提示原因（原来只是静默返回失败，
 *    界面文字停在「探测文件」，看起来就是卡死）。
 * 3) 所有阶段都会更新提示文字，任何一步都能看出走到哪里。
 */
public class Updater {

    private static final int THREADS = 8;
    private static final long MIN_SIZE = 500000L;
    private static final long CORE_WAIT_MS = 10000L;
    private static final long PROBE_WAIT_MS = 12000L;

    private static final int OK = 0;
    private static final int FAIL_DOWNLOAD = 1;

    private static final class Job {
        final AtomicLong total = new AtomicLong(0);
        final AtomicLong got = new AtomicLong(0);
        final AtomicBoolean aborted = new AtomicBoolean(false);
        final AtomicInteger lastCode = new AtomicInteger(0);
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
        volatile String failReason = "";
        volatile Activity act = null;
    }

    public static void start(final Activity act, final String url) {
        start(act, url, 0, null);
    }

    public static void start(final Activity act, final String url, final Runnable onFail) {
        start(act, url, 0, onFail);
    }

    public static void start(final Activity act, final String url, final long expectSize, final Runnable onFail) {
        final Job job = new Job();
        job.act = act;

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
        tip.setText("准备网络...");
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
                // 阶段一：并行准备代理核心，最多等 10 秒
                stage(tip, "准备网络（最多等 10 秒）...");
                Thread coreThread = new Thread(new Runnable() {
                    public void run() {
                        try {
                            if (XrayCore.ensureReady(act)) {
                                boolean ok = XrayCore.start(act);
                                if (!ok) {
                                    LoginDiag.add("PROXY", "核心启动失败: " + XrayCore.lastError);
                                }
                            } else {
                                LoginDiag.add("PROXY", "核心准备失败: " + XrayCore.lastError);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                coreThread.start();
                try {
                    coreThread.join(CORE_WAIT_MS);
                } catch (InterruptedException ignored) {
                }

                // 阶段二：探测
                stage(tip, "探测文件...· " + Net.routeName(act));

                File dir = act.getExternalFilesDir(null);
                final File out = new File(dir, "update.apk");
                final File part = new File(dir, "update.apk.part");
                final File state = new File(dir, "update.apk.state");

                int result = fetch(act, job, bar, tip, buildUrls(url), expectSize, part, state, out);

                boolean signOk = false;
                if (result == OK) {
                    stage(tip, "校验安装包...");
                    try {
                        signOk = signatureMatches(act, out);
                    } catch (Throwable ignored) {
                    }
                }

                final int r = result;
                final boolean okSign = signOk;
                final String reason = job.failReason;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        dlg.dismiss();
                        if (r == OK) {
                            install(act, out, okSign);
                        } else {
                            String msg = "下载失败";
                            if (reason != null && reason.length() > 0) msg = msg + ": " + reason;
                            msg = msg + "（" + Net.routeName(act) + "）";
                            android.widget.Toast.makeText(act, msg,
                                    android.widget.Toast.LENGTH_LONG).show();
                            if (onFail != null) onFail.run();
                        }
                    }
                });
            }
        }).start();
    }

    private static void stage(final TextView tip, final String text) {
        if (tip == null) return;
        tip.post(new Runnable() {
            public void run() {
                tip.setText(text);
            }
        });
    }

    private static java.util.List<String> buildUrls(String url) {
        java.util.List<String> list = new java.util.ArrayList<String>();
        if (url == null || url.length() == 0) return list;
        list.add(url);
        return list;
    }

    private static HttpURLConnection open(Job job, String url, long from, long to, int ct, int rt) throws Exception {
        HttpURLConnection c = Net.open(job.act, url, ct, rt);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (from >= 0) {
            c.setRequestProperty("Range", "bytes=" + from + "-" + (to >= 0 ? String.valueOf(to) : ""));
        }
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
        try { return new java.net.URL(url).getHost(); } catch (Exception e) { return ""; }
    }

    /**
     * 探测文件大小。
     * 失败时把原因写进 job.failReason，避免静默失败让界面看起来卡住。
     */
    private static boolean probe(final Job job, final java.util.List<String> urls, final long expectSize) {
        final Object foundLock = new Object();
        final boolean[] found = new boolean[1];
        for (int i = 0; i < urls.size(); i++) {
            final String u = urls.get(i);
            new Thread(new Runnable() {
                public void run() {
                    HttpURLConnection c = null;
                    try {
                        c = open(job, u, 0, 0, 8000, 12000);
                        int code = c.getResponseCode();
                        job.lastCode.set(code);
                        long t = -1;
                        boolean rg = false;
                        if (code == 206) {
                            t = parseTotal(c);
                            rg = t > 0;
                        } else if (code == 200) {
                            t = c.getContentLength();
                        }
                        if (t <= MIN_SIZE) {
                            job.failReason = "文件大小异常(" + t + ")";
                            return;
                        }
                        if (expectSize > 0 && t != expectSize) {
                            job.failReason = "大小与预期不符(实际" + t + " 预期" + expectSize + ")";
                            return;
                        }
                        synchronized (foundLock) {
                            if (found[0]) return;
                            found[0] = true;
                            job.total.set(t);
                            job.rangeOk = rg;
                            job.pickUrl = u;
                            job.curHost = hostOf(u);
                        }
                    } catch (Exception e) {
                        String m = String.valueOf(e.getMessage());
                        job.failReason = "连接失败: " + m;
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
            }).start();
        }
        long deadline = System.currentTimeMillis() + PROBE_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            synchronized (foundLock) { if (found[0]) return true; }
            try { Thread.sleep(120L); } catch (Exception e) {}
        }
        synchronized (foundLock) {
            if (found[0]) return true;
        }
        if (job.failReason == null || job.failReason.length() == 0) {
            job.failReason = "探测超时（" + PROBE_WAIT_MS / 1000 + "秒）";
        }
        return false;
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
        final String route = Net.routeName(act);
        act.runOnUiThread(new Runnable() {
            public void run() {
                if (bar != null) bar.setProgress(pct);
                if (tip != null) {
                    tip.setText((pct / 10) + "." + (pct % 10) + "% · " + sp
                            + " · " + route + " · " + host);
                }
            }
        });
    }

    private static int fetch(Activity act, Job job, ProgressBar bar, TextView tip,
                             java.util.List<String> urls, long expectSize,
                             File part, File state, File out) {
        if (urls == null || urls.isEmpty()) {
            job.failReason = "下载地址为空";
            return FAIL_DOWNLOAD;
        }
        if (!probe(job, urls, expectSize)) return FAIL_DOWNLOAD;

        final String src = job.pickUrl;
        final long total = job.total.get();

        if (out.exists() && out.length() == total && total > MIN_SIZE) {
            job.got.set(total);
            return OK;
        }

        if (!job.rangeOk) {
            // single() 成功时已完成 part -> out，必须直接返回
            return single(act, job, bar, tip, src, expectSize, part, out) ? OK : FAIL_DOWNLOAD;
        }

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
            job.failReason = "创建临时文件失败: " + e.getMessage();
            return FAIL_DOWNLOAD;
        }
        ui(act, job, bar, tip, true);

        final CountDownLatch latch = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        worker(act, job, bar, tip, src, part, idx);
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
            job.failReason = "下载线程被中断";
            return FAIL_DOWNLOAD;
        }
        saveState(job, state, 0);
        if (job.aborted.get()) {
            job.failReason = "下载已取消";
            return FAIL_DOWNLOAD;
        }
        if (job.got.get() < total) {
            long done = job.got.get();
            job.failReason = "未下完(" + (done * 100 / (total > 0 ? total : 1)) + "%)";
            return FAIL_DOWNLOAD;
        }

        try {
            if (out.exists() && !out.delete()) {
                job.failReason = "删除旧文件失败";
                return FAIL_DOWNLOAD;
            }
            if (!part.renameTo(out)) {
                job.failReason = "重命名失败";
                return FAIL_DOWNLOAD;
            }
            if (out.length() != total) {
                job.failReason = "大小校验失败";
                return FAIL_DOWNLOAD;
            }
            state.delete();
            return OK;
        } catch (Exception e) {
            job.failReason = "收尾失败: " + e.getMessage();
            return FAIL_DOWNLOAD;
        }
    }

    private static void worker(Activity act, Job job, ProgressBar bar, TextView tip,
                               String src, File part, int idx) {
        long len = job.segEnd[idx] - job.segStart[idx] + 1;
        int tries = 0;
        while (job.segDone[idx] < len && !job.aborted.get()) {
            HttpURLConnection c = null;
            InputStream in = null;
            RandomAccessFile raf = null;
            try {
                long pos = job.segStart[idx] + job.segDone[idx];
                c = open(job, src, pos, job.segEnd[idx], 12000, 25000);
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
            c = open(job, src, -1, -1, 12000, 30000);
            if (c.getResponseCode() != 200) {
                job.failReason = "单线程下载返回非 200";
                return false;
            }
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
            if (g <= MIN_SIZE) {
                job.failReason = "文件过小(" + g + ")";
                return false;
            }
            if (expectSize > 0 && g != expectSize) {
                job.failReason = "大小不符(实际" + g + " 预期" + expectSize + ")";
                return false;
            }
            if (len > 0 && g != len) {
                job.failReason = "长度不符(实际" + g + " 声明" + len + ")";
                return false;
            }
            job.total.set(g);
            if (out.exists()) out.delete();
            return part.renameTo(out);
        } catch (Exception e) {
            job.failReason = "下载异常: " + e.getMessage();
            return false;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception e1) {}
            try { if (in != null) in.close(); } catch (Exception e2) {}
            if (c != null) c.disconnect();
        }
    }

    private static boolean signatureMatches(Activity act, File apk) {
        try {
            PackageManager pm = act.getPackageManager();
            Signature[] mine = readSignatures(pm, act.getPackageName(), null);
            if (mine == null || mine.length == 0) return false;
            Signature[] theirs = readSignatures(pm, null, apk.getAbsolutePath());
            if (theirs == null || theirs.length == 0) return false;
            for (Signature a : mine) {
                String fa = digest(a.toByteArray());
                for (Signature b : theirs) {
                    if (fa.equals(digest(b.toByteArray()))) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static Signature[] readSignatures(PackageManager pm, String pkg, String apkPath) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = (apkPath == null)
                        ? pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                        : pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi != null && pi.signingInfo != null) {
                    return pi.signingInfo.hasMultipleSigners()
                            ? pi.signingInfo.getApkContentsSigners()
                            : pi.signingInfo.getSigningCertificateHistory();
                }
            }
            PackageInfo pi = (apkPath == null)
                    ? pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                    : pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES);
            return pi == null ? null : pi.signatures;
        } catch (Throwable t) {
            return null;
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

    private static void install(Activity act, File apk, boolean signOk) {
        if (rootInstall(apk)) {
            android.widget.Toast.makeText(act, "更新完成", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!signOk) {
            android.widget.Toast.makeText(act,
                    "root 安装未成功，转交系统安装器（签名与当前版本不同）",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        intentInstall(act, apk);
    }

    private static boolean rootInstall(File apk) {
        if (apk == null || !apk.exists()) return false;
        Process p = null;
        try {
            final String tmp = "/data/local/tmp/shq_update.apk";
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("cp " + RootUtil.shellQuote(apk.getAbsolutePath()) + " " + tmp + "\n");
            os.writeBytes("chmod 644 " + tmp + "\n");
            os.writeBytes("pm install -r " + tmp + "\n");
            os.writeBytes("rm -f " + tmp + "\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();

            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln).append('\n');
            br.close();
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while ((ln = er.readLine()) != null) sb.append(ln).append('\n');
            er.close();

            int code = p.waitFor();
            String out = sb.toString();
            if (out.contains("Success")) return true;
            return code == 0 && !out.contains("Failure");
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static void intentInstall(Activity act, File f) {
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
            android.widget.Toast.makeText(act, "安装失败: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
