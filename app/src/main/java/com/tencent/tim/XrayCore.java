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
 * 二进制落地位置：
 * Android 10 起应用私有目录是 noexec，直接 exec 会被拒。
 * 本应用有 root，所以用 su 安装到 /data/local/tmp/shq/，
 * 该目录可执行，且 APK 升级后依然存在。
 *
 * 核心从仓库 core/xray 获取，可独立更新，不必重装 APK。
 *
 * 关于「核心自己怎么下载」这个先有鸡还是先有蛋的问题：
 * 首次使用时 xray 还没起来，没法用它的代理。这里按顺序尝试：
 *   1) 用户手动配置的代理（Net 里那个，通常是 Clash 之类）
 *   2) 直连
 * 只要有一条通就能把核心拉下来，之后一切走 xray。
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

    /** 该节点的 VLESS 配置。换节点只改这几个常量。 */
    public static final String NODE_HOST =
            "6abba132-1847-4cb5-b01d-7abc79f1657c.a01.interface.routely.moe";
    public static final int NODE_PORT = 11001;
    public static final String NODE_UUID = "6abba132-1847-4cb5-b01d-7abc79f1657c";
    public static final String NODE_SNI = "k0g1h.biliimg.com";
    public static final String NODE_PBK = "igngKHlQ9A2lqYZfuAkvi2rWIlun9zMLbEOrGfiqZQo";
    public static final String NODE_SID = "cc88c857dcd3bbe4";
    public static final String NODE_FP = "chrome";
    public static final String NODE_SPX = "/";
    public static final String NODE_FLOW = "xtls-rprx-vision";

    private static volatile Process proc;

    public static String buildConfig() {
        return "{\n"
                + "  \"log\": { \"loglevel\": \"warning\" },\n"
                + "  \"inbounds\": [{\n"
                + "    \"listen\": \"127.0.0.1\",\n"
                + "    \"port\": " + SOCKS_PORT + ",\n"
                + "    \"protocol\": \"socks\",\n"
                + "    \"settings\": { \"auth\": \"noauth\", \"udp\": false }\n"
                + "  }],\n"
                + "  \"outbounds\": [{\n"
                + "    \"protocol\": \"vless\",\n"
                + "    \"settings\": {\n"
                + "      \"vnext\": [{\n"
                + "        \"address\": \"" + NODE_HOST + "\",\n"
                + "        \"port\": " + NODE_PORT + ",\n"
                + "        \"users\": [{\n"
                + "          \"id\": \"" + NODE_UUID + "\",\n"
                + "          \"encryption\": \"none\",\n"
                + "          \"flow\": \"" + NODE_FLOW + "\"\n"
                + "        }]\n"
                + "      }]\n"
                + "    },\n"
                + "    \"streamSettings\": {\n"
                + "      \"network\": \"tcp\",\n"
                + "      \"security\": \"reality\",\n"
                + "      \"realitySettings\": {\n"
                + "        \"serverName\": \"" + NODE_SNI + "\",\n"
                + "        \"fingerprint\": \"" + NODE_FP + "\",\n"
                + "        \"publicKey\": \"" + NODE_PBK + "\",\n"
                + "        \"shortId\": \"" + NODE_SID + "\",\n"
                + "        \"spiderX\": \"" + NODE_SPX + "\"\n"
                + "      }\n"
                + "    }\n"
                + "  }]\n"
                + "}\n";
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

    /** 确保核心可用：已安装直接返回，否则下载并安装。需在后台线程调用。 */
    public static boolean ensureReady(Context c) {
        if (isInstalled(c)) return true;
        return downloadAndInstall(c);
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

        // 注意：核心下载时 xray 通常还没起来，
        // 所以这里显式不用 XrayCore 自己的代理，避免自我依赖。
        // 走「手动代理 → 直连」两条路。
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

        String cfgScript = "cp " + RootUtil.shellQuote(cfg.getAbsolutePath())
                + " " + REMOTE_DIR + "/config.json\n"
                + "chmod 644 " + REMOTE_DIR + "/config.json\n";
        if (!runSu(cfgScript)) return false;

        prefs(c).edit().putBoolean(KEY_READY, true).putString(KEY_VER, expect).apply();
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
     * 下载文件。不走 XrayCore.socksProxy()（此刻它多半还没启动），
     * 而是「手动代理 → 直连」。
     */
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
