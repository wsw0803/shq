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
import java.net.URL;
import java.security.MessageDigest;

/**
 * 更新下载用的本地代理（仅代理更新流量，不接管全局）。
 *
 * 为什么只开本地 SOCKS，不做 VpnService：
 * 抓号/上号等业务流量完全不受影响，也不需要申请 VPN 权限，
 * 改动范围最小。只有 Updater 的下载连接会走这里。
 *
 * 关于二进制落地位置：
 * Android 10 起应用私有目录是 noexec，下载来的可执行文件直接 exec 会被拒。
 * 本应用有 root，所以用 su 安装到 /data/local/tmp/shq/ 再执行：
 * 该目录可执行，且在 APK 升级后依然存在。
 *
 * 核心从仓库获取，可独立更新，不必重装 APK：
 *   core/xray          二进制
 *   core/xray.sha256   其 SHA-256（用于完整性校验）
 */
public class XrayCore {

    private static final String REPO_RAW = "https://raw.githubusercontent.com/wsw0803/shq/main/core/";
    private static final String BIN_NAME = "xray";
    private static final String REMOTE_DIR = "/data/local/tmp/shq";

    public static final int SOCKS_PORT = 10808;

    private static final String PREF = "xray_pref";
    private static final String KEY_READY = "ready";
    private static final String KEY_VER = "ver";

    /** 该节点的 VLESS 配置。改动这里即可换节点。 */
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

    private static Process proc;

    /** 生成 xray 的 config.json。只开一个本地 SOCKS 入站。 */
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

    /** 核心是否已安装到可执行目录。 */
    public static boolean isInstalled(Context c) {
        return prefs(c).getBoolean(KEY_READY, false);
    }

    /**
     * 确保核心可用：已安装则直接返回，否则从仓库下载并安装。
     * 需要在后台线程调用。
     */
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

        // 1) 下载校验文件
        if (!httpToFile(REPO_RAW + BIN_NAME + ".sha256", shaFile)) return false;
        String expect = readText(shaFile).trim().toLowerCase();
        if (expect.length() < 32) return false;

        // 2) 下载二进制
        if (!httpToFile(REPO_RAW + BIN_NAME, bin)) return false;
        if (bin.length() < 1024) return false;

        // 3) 校验完整性
        String actual = sha256(bin);
        if (!actual.equals(expect)) {
            bin.delete();
            return false;
        }

        // 4) 通过 root 安装到可执行目录
        String local = RootUtil.shellQuote(bin.getAbsolutePath());
        String script = "mkdir -p " + REMOTE_DIR + "\n"
                + "cp " + local + " " + REMOTE_DIR + "/" + BIN_NAME + "\n"
                + "chmod 755 " + REMOTE_DIR + "/" + BIN_NAME + "\n";
        if (!runSu(script)) return false;

        // 5) 写入配置
        File cfg = new File(work, "config.json");
        try {
            FileOutputStream fos = new FileOutputStream(cfg, false);
            fos.write(buildConfig().getBytes("UTF-8"));
            fos.flush();
            fos.close();
            String cfgScript = "cp " + RootUtil.shellQuote(cfg.getAbsolutePath())
                    + " " + REMOTE_DIR + "/config.json\n"
                    + "chmod 644 " + REMOTE_DIR + "/config.json\n";
            if (!runSu(cfgScript)) return false;
        } catch (Exception e) {
            return false;
        }

        prefs(c).edit().putBoolean(KEY_READY, true).putString(KEY_VER, expect).apply();
        return true;
    }

    /** 启动核心。已在运行则直接返回 true。 */
    public static boolean start(Context c) {
        if (proc != null) return true;
        if (!isInstalled(c)) return false;
        try {
            proc = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", REMOTE_DIR + "/" + BIN_NAME + " run -c " + REMOTE_DIR + "/config.json"
            });
            // 给核心一点启动时间再判断端口
            Thread.sleep(1200);
            return isAlive();
        } catch (Exception e) {
            proc = null;
            return false;
        }
    }

    public static boolean isAlive() {
        if (proc == null) return false;
        try {
            proc.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void stop() {
        if (proc != null) {
            try {
                proc.destroy();
            } catch (Throwable ignored) {
            }
            proc = null;
        }
        // 兜底：杀掉残留进程，避免端口占用导致下次起不来
        try {
            runSu("pkill -f " + REMOTE_DIR + "/" + BIN_NAME + "\n");
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

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

    private static boolean httpToFile(String url, File dst) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
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
