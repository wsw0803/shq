package com.tencent.tim;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.security.MessageDigest;

/**
 * 更新下载用的本地代理核心（只代理本应用的更新流量，不接管全局）。
 *
 * 本次关键修复：
 * 首次使用要下载 31MB 核心，原来只给 10 秒等待，根本下不完，
 * 导致第一次更新必然失败；而核心其实还在后台下载、最终会成功，
 * 表现就是「卡在探测文件，随后失败」。
 * 现在增加了进度回调，界面能看到「下载核心 62%」，并给足时间。
 *
 * 启动成功与否改用探测 127.0.0.1:10808 判断：
 * xray 由 su -c 启动会脱离 Java 进程树，Process 状态不可靠。
 */
public class XrayCore {

    private static final String REPO_RAW =
            "https://raw.githubusercontent.com/wsw0803/shq/main/core/";
    private static final String BIN_NAME = "xray";
    private static final String REMOTE_DIR = "/data/local/tmp/shq";

    public static final int SOCKS_PORT = 10808;

    private static final String PREF = "xray_pref";
    private static final String KEY_READY = "ready";
    private static final String KEY_VER = "ver";

    public static volatile String lastError = "";

    private static volatile Process proc;

    /** 进度回调，用于在界面显示下载百分比。 */
    public interface Progress {
        void onStage(String text);
    }

    public static String buildConfig() {
        return NodeConfig.buildJson(SOCKS_PORT);
    }

    public static Proxy socksProxy() {
        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", SOCKS_PORT));
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean isInstalled(Context c) {
        return prefs(c).getBoolean(KEY_READY, false);
    }

    public static boolean ensureReady(Context c) {
        return ensureReady(c, null);
    }

    /** 确保核心与配置都可用。二进制缺失则下载（带进度）；配置指纹变了只重写 config。 */
    public static boolean ensureReady(Context c, Progress p) {
        if (!isInstalled(c)) {
            if (!downloadAndInstall(c, p)) return false;
        } else if (!NodeConfig.isApplied(c)) {
            if (p != null) p.onStage("更新代理配置...");
            if (!writeConfig(c)) return false;
        }
        return true;
    }

    public static boolean downloadAndInstall(Context c) {
        return downloadAndInstall(c, null);
    }

    /** 从仓库下载核心，校验 SHA-256，再通过 su 安装到 /data/local/tmp/shq/。 */
    public static boolean downloadAndInstall(Context c, Progress p) {
        File work = new File(c.getFilesDir(), "xray_dl");
        try {
            if (!work.exists()) work.mkdirs();
        } catch (Exception ignored) {
        }

        File bin = new File(work, BIN_NAME);
        File shaFile = new File(work, BIN_NAME + ".sha256");

        if (p != null) p.onStage("读取核心校验值...");
        if (!httpToFile(c, REPO_RAW + BIN_NAME + ".sha256", shaFile, null, p)) {
            lastError = "下载校验文件失败";
            return false;
        }
        String expect = readText(shaFile).trim().toLowerCase();
        if (expect.length() < 32) {
            lastError = "校验文件内容异常";
            return false;
        }

        // 本地已有同 SHA 的文件就不重复下载
        boolean reuse = false;
        try {
            if (bin.exists() && bin.length() > 1024 && expect.equals(sha256(bin))) {
                reuse = true;
                if (p != null) p.onStage("核心已存在，跳过下载");
            }
        } catch (Throwable ignored) {
        }

        if (!reuse) {
            if (!httpToFile(c, REPO_RAW + BIN_NAME, bin, "下载核心", p)) {
                lastError = "下载核心失败";
                return false;
            }
            if (bin.length() < 1024) {
                lastError = "核心文件过小";
                return false;
            }
            if (p != null) p.onStage("校验核心...");
            String actual = sha256(bin);
            if (!actual.equals(expect)) {
                bin.delete();
                lastError = "核心校验不匹配";
                return false;
            }
        }

        if (p != null) p.onStage("安装核心...");
        String local = RootUtil.shellQuote(bin.getAbsolutePath());
        String script = "mkdir -p " + REMOTE_DIR + "\n"
                + "cp " + local + " " + REMOTE_DIR + "/" + BIN_NAME + "\n"
                + "chmod 755 " + REMOTE_DIR + "/" + BIN_NAME + "\n";
        if (!runSu(script)) {
            lastError = "安装核心失败（root 不可用或复制失败）";
            return false;
        }

        prefs(c).edit().putBoolean(KEY_READY, true).putString(KEY_VER, expect).apply();
        return writeConfig(c);
    }

    /** 把 config.json 写到应用目录，再复制到可执行目录。 */
    public static boolean writeConfig(Context c) {
        File work = new File(c.getFilesDir(), "xray_dl");
        try {
            if (!work.exists()) work.mkdirs();
        } catch (Exception ignored) {
        }

        File cfg = new File(work, "config.json");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(cfg, false);
            fos.write(buildConfig().getBytes("UTF-8"));
            fos.flush();
        } catch (Exception e) {
            lastError = "写配置失败: " + e;
            return false;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }

        String script = "mkdir -p " + REMOTE_DIR + "\n"
                + "cp " + RootUtil.shellQuote(cfg.getAbsolutePath())
                + " " + REMOTE_DIR + "/config.json\n"
                + "chmod 644 " + REMOTE_DIR + "/config.json\n";
        if (!runSu(script)) {
            lastError = "复制配置失败";
            return false;
        }

        NodeConfig.markApplied(c);
        return true;
    }

    /** 启动核心。成功判定改为探测端口，最多等 3 秒。 */
    public static boolean start(Context c) {
        if (probePort()) {
            Net.invalidateCoreProbe();
            return true;
        }
        if (!isInstalled(c)) {
            lastError = "核心未安装";
            return false;
        }

        // 先清掉可能残留的实例，避免端口占用
        stop();

        try {
            proc = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", REMOTE_DIR + "/" + BIN_NAME + " run -c " + REMOTE_DIR + "/config.json"
            });
        } catch (Exception e) {
            proc = null;
            lastError = "启动失败: " + e;
            return false;
        }

        for (int i = 0; i < 15; i++) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            if (probePort()) {
                Net.invalidateCoreProbe();
                lastError = "";
                return true;
            }
        }

        // 没起来：把 stderr 读出来，多半是配置错误
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader er = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
            String ln;
            int n = 0;
            while ((ln = er.readLine()) != null && n < 20) {
                sb.append(ln).append(' ');
                n++;
            }
            er.close();
            lastError = "核心未监听 " + SOCKS_PORT + (sb.length() > 0 ? (": " + sb) : "");
        } catch (Throwable t) {
            lastError = "核心未监听 " + SOCKS_PORT;
        }

        stop();
        return false;
    }

    private static boolean probePort() {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", SOCKS_PORT), 300);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignored) {}
        }
    }

    public static boolean restart(Context c) {
        stop();
        return start(c);
    }

    public static boolean isAlive() {
        return probePort();
    }

    public static void stop() {
        Process p = proc;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) {}
            proc = null;
        }
        try {
            runSu("pkill -f " + REMOTE_DIR + "/" + BIN_NAME + "\n");
        } catch (Throwable ignored) {
        }
        Net.invalidateCoreProbe();
    }

    public static void uninstall(Context c) {
        stop();
        runSu("rm -rf " + REMOTE_DIR + "\n");
        prefs(c).edit().remove(KEY_READY).remove(KEY_VER).apply();
        NodeConfig.clearApplied(c);
        try {
            File work = new File(c.getFilesDir(), "xray_dl");
            File[] fs = work.listFiles();
            if (fs != null) {
                for (File f : fs) f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean runSu(String script) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.write(script.getBytes("UTF-8"));
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /**
     * 下载文件，走「手动代理 → 直连」。
     * label 非空时会把进度回调出去，让界面显示「下载核心 62%」。
     */
    private static boolean httpToFile(Context c, String url, File dst, String label, Progress p) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            Proxy manual = Net.manualProxyOrNull(c);
            conn = (manual != null)
                    ? (HttpURLConnection) new java.net.URL(url).openConnection(manual)
                    : (HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(90000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() != 200) return false;

            long total = conn.getContentLength();
            in = conn.getInputStream();
            out = new FileOutputStream(dst, false);
            byte[] buf = new byte[65536];
            int n;
            long got = 0;
            long lastReport = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                // 每 1MB 报一次进度，避免过于频繁刷新界面
                if (p != null && label != null && got - lastReport > 1048576L) {
                    lastReport = got;
                    if (total > 0) {
                        p.onStage(label + " " + (got * 100 / total) + "% "
                                + (got / 1048576L) + "/" + (total / 1048576L) + "MB");
                    } else {
                        p.onStage(label + " " + (got / 1048576L) + "MB");
                    }
                }
            }
            out.flush();
            return dst.length() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private static String readText(File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (br != null) {
                try { br.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static String sha256(File f) {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] out = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
    }
}
