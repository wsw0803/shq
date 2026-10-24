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
 * 二进制落地位置：
 * Android 10 起应用私有目录是 noexec，直接 exec 会被拒。
 * 本应用有 root，用 su 安装到 /data/local/tmp/shq/，该目录可执行、升级后保留。
 *
 * 关键修复：
 * 1) 启动是否成功，改为探测 127.0.0.1:10808 能否连上。
 *    原来靠 Process.exitValue() 抛异常来判断「还活着」，
 *    但 xray 由 su -c 启动会脱离 Java 进程树，这个判断既可能误判成功
 *    也可能误判失败（App 重启后 proc 为 null）。
 * 2) 启动失败时把 xray 的 stderr 记进 lastError，便于定位配置错误。
 * 3) 下载核心时若已存在同 SHA 的文件则跳过下载。
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

    /** 最近一次失败原因，供诊断显示。 */
    public static volatile String lastError = "";

    private static volatile Process proc;

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

    /** 确保核心与配置都可用。二进制缺失则下载；配置指纹变了只重写 config。 */
    public static boolean ensureReady(Context c) {
        if (!isInstalled(c)) {
            if (!downloadAndInstall(c)) return false;
        } else if (!NodeConfig.isApplied(c)) {
            if (!writeConfig(c)) return false;
        }
        return true;
    }

    /** 从仓库下载核心，校验 SHA-256，再通过 su 安装到 /data/local/tmp/shq/。 */
    public static boolean downloadAndInstall(Context c) {
        File work = new File(c.getFilesDir(), "xray_dl");
        try {
            if (!work.exists()) work.mkdirs();
        } catch (Exception ignored) {
        }

        File bin = new File(work, BIN_NAME);
        File shaFile = new File(work, BIN_NAME + ".sha256");

        // 核心下载时 xray 通常还没起来，走「手动代理 → 直连」，避免自我依赖。
        if (!httpToFile(c, REPO_RAW + BIN_NAME + ".sha256", shaFile)) {
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
            }
        } catch (Throwable ignored) {
        }

        if (!reuse) {
            if (!httpToFile(c, REPO_RAW + BIN_NAME, bin)) {
                lastError = "下载核心失败";
                return false;
            }
            if (bin.length() < 1024) {
                lastError = "核心文件过小";
                return false;
            }
            String actual = sha256(bin);
            if (!actual.equals(expect)) {
                bin.delete();
                lastError = "核心校验不匹配";
                return false;
            }
        }

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

    /**
     * 启动核心。
     *
     * 成功判定改为探测端口：xray 由 su -c 启动会脱离 Java 进程树，
     * Process 对象的状态不能代表它是否在工作。
     */
    public static boolean start(Context c) {
        // 已经在监听就直接复用
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

        // 最多等 3 秒，每 200ms 探一次端口
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

    /** 探测 SOCKS 端口是否可连。 */
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

    /** 仅供诊断显示。端口探测才是可靠依据。 */
    public static boolean isAlive() {
        return probePort();
    }

    public static void stop() {
        Process p = proc;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) {}
            proc = null;
        }
        // 兜底清理，避免端口占用导致下次起不来
        try {
            runSu("pkill -f " + REMOTE_DIR + "/" + BIN_NAME + "\n");
        } catch (Throwable ignored) {
        }
        Net.invalidateCoreProbe();
    }

    /** 彻底卸载：删二进制、配置与状态，下次会重新下载。 */
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

    /** 下载文件，走「手动代理 → 直连」。 */
    private static boolean httpToFile(Context c, String url, File dst) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            Proxy manual = Net.manualProxyOrNull(c);
            conn = (manual != null)
                    ? (HttpURLConnection) new java.net.URL(url).openConnection(manual)
                    : (HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() != 200) return false;
            in = conn.getInputStream();
            out = new FileOutputStream(dst, false);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
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
