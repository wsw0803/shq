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
import java.security.MessageDigest;

/**
 * 更新下载用的本地代理核心（只代理本应用的更新流量，不接管全局）。
 *
 * 节点参数在 NodeConfig 里。换节点不需要重新下载二进制，
 * 只要配置指纹变了，ensureConfig() 会自动重写 config.json。
 *
 * 二进制落地位置：
 * Android 10 起应用私有目录是 noexec，直接 exec 会被拒。
 * 本应用有 root，所以用 su 安装到 /data/local/tmp/shq/，
 * 该目录可执行，且 APK 升级后依然存在。
 *
 * 核心从仓库 core/xray 获取，可独立更新，不必重装 APK。
 * 首次引导时 xray 还没起来，下载核心走「手动代理 → 直连」。
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

    /**
     * 确保核心与配置都可用。
     * 二进制缺失则下载；配置指纹变了则重写 config.json。
     * 需在后台线程调用。
     */
    public static boolean ensureReady(Context c) {
        if (!isInstalled(c)) {
            if (!downloadAndInstall(c)) return false;
        } else if (!NodeConfig.isApplied(c)) {
            // 换了节点：只重写配置，不重新下载 31MB 二进制
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

        // 核心下载时 xray 通常还没起来，所以这里不用它自己的代理，
        // 走「手动代理 → 直连」两条路，避免自我依赖。
        if (!httpToFile(c, REPO_RAW + BIN_NAME + ".sha256", shaFile)) return false;
        String expect = readText(shaFile).trim().toLowerCase();
        if (expect.length() < 32) return false;

        if (!httpToFile(c, REPO_RAW + BIN_NAME, bin)) return false;
        if (bin.length() < 1024) return false;

        String actual = sha256(bin);
        if (!actual.equals(expect)) {
            bin.delete();
            return false;
        }

        String local = RootUtil.shellQuote(bin.getAbsolutePath());
        String script = "mkdir -p " + REMOTE_DIR + "\n"
                + "cp " + local + " " + REMOTE_DIR + "/" + BIN_NAME + "\n"
                + "chmod 755 " + REMOTE_DIR + "/" + BIN_NAME + "\n";
        if (!runSu(script)) return false;

        prefs(c).edit().putBoolean(KEY_READY, true).putString(KEY_VER, expect).apply();

        // 二进制就绪后立刻写配置
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
            return false;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        }

        String script = "mkdir -p " + REMOTE_DIR + "\n"
                + "cp " + RootUtil.shellQuote(cfg.getAbsolutePath())
                + " " + REMOTE_DIR + "/config.json\n"
                + "chmod 644 " + REMOTE_DIR + "/config.json\n";
        if (!runSu(script)) return false;

        NodeConfig.markApplied(c);
        return true;
    }

    /** 启动核心。已在运行直接返回 true。 */
    public static boolean start(Context c) {
        if (isAlive()) return true;
        if (!isInstalled(c)) return false;
        try {
            proc = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", REMOTE_DIR + "/" + BIN_NAME + " run -c " + REMOTE_DIR + "/config.json"
            });
            // 给核心一点启动时间，再判断是否存活
            Thread.sleep(1500);
            return isAlive();
        } catch (Exception e) {
            proc = null;
            return false;
        }
    }

    /** 停止后重新启动，用于换节点后让新配置生效。 */
    public static boolean restart(Context c) {
        stop();
        try {
            Thread.sleep(300);
        } catch (Exception ignored) {
        }
        return start(c);
    }

    public static boolean isAlive() {
        Process p = proc;
        if (p == null) return false;
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        } catch (Throwable t) {
            return false;
        }
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
