package com.tencent.tim;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * 代理节点配置。
 *
 * 参数以「逆序片段」存放，不在源码里出现明文，
 * 避免直接 grep / strings 就能拿到节点。
 * 这只是混淆，不是加密——密钥必须随 APK 分发，拦不住真正的逆向分析。
 *
 * 换节点：改下面几组片段 + PORT，App 会在下次 ensureReady() 时
 * 自动重写 config.json（靠 fingerprint 判断变化），不必重下 31MB 二进制。
 */
public class NodeConfig {

    private static final String[] HOST_P = {".61", ".28", ".149", "23"};
    private static final String[] UUID_P = {
            "-de6bd76f4eba", "-a886", "-4ede", "-70ec", "9c82c018"
    };
    private static final String[] SNI_P = {".com", ".yahoo", "www"};
    private static final String[] PBK_P = {
            "TFg", "CFDG8cRYUhIbS", "vwabXrHsgm0TM", "ZlsbyvqvvvEHAX"
    };
    private static final String[] SID_P = {"cd", "49", "6f29"};

    public static final int PORT = 22123;

    private static final String FINGERPRINT_ALG = "chrome";
    private static final String SPIDER_X = "/";
    private static final String FLOW = "xtls-rprx-vision";

    public static String host() {
        return Sec.restore(HOST_P);
    }

    public static String uuid() {
        return Sec.restore(UUID_P);
    }

    public static String sni() {
        return Sec.restore(SNI_P);
    }

    public static String publicKey() {
        return Sec.restore(PBK_P);
    }

    public static String shortId() {
        return Sec.restore(SID_P);
    }

    /**
     * 配置指纹。参数一变，ensureConfig() 就重写 config.json，
     * 避免「换了节点但 APP 还在用旧配置」。
     */
    public static String fingerprint() {
        return host() + "|" + PORT + "|" + uuid() + "|" + sni() + "|"
                + publicKey() + "|" + shortId() + "|" + FINGERPRINT_ALG + "|" + FLOW;
    }

    private static final String PREF = "node_pref";
    private static final String KEY_FP = "cfg_fp";

    /** xray 配置：单 SOCKS 入站 + VLESS Reality 出站。 */
    public static String buildJson(int socksPort) {
        return "{\n"
                + "  \"log\": { \"loglevel\": \"warning\" },\n"
                + "  \"inbounds\": [{\n"
                + "    \"listen\": \"127.0.0.1\",\n"
                + "    \"port\": " + socksPort + ",\n"
                + "    \"protocol\": \"socks\",\n"
                + "    \"settings\": { \"auth\": \"noauth\", \"udp\": false }\n"
                + "  }],\n"
                + "  \"outbounds\": [{\n"
                + "    \"protocol\": \"vless\",\n"
                + "    \"settings\": {\n"
                + "      \"vnext\": [{\n"
                + "        \"address\": \"" + host() + "\",\n"
                + "        \"port\": " + PORT + ",\n"
                + "        \"users\": [{\n"
                + "          \"id\": \"" + uuid() + "\",\n"
                + "          \"encryption\": \"none\",\n"
                + "          \"flow\": \"" + FLOW + "\"\n"
                + "        }]\n"
                + "      }]\n"
                + "    },\n"
                + "    \"streamSettings\": {\n"
                + "      \"network\": \"tcp\",\n"
                + "      \"security\": \"reality\",\n"
                + "      \"realitySettings\": {\n"
                + "        \"serverName\": \"" + sni() + "\",\n"
                + "        \"fingerprint\": \"" + FINGERPRINT_ALG + "\",\n"
                + "        \"publicKey\": \"" + publicKey() + "\",\n"
                + "        \"shortId\": \"" + shortId() + "\",\n"
                + "        \"spiderX\": \"" + SPIDER_X + "\"\n"
                + "      }\n"
                + "    }\n"
                + "  }]\n"
                + "}\n";
    }

    public static boolean isApplied(Context c) {
        if (c == null) return false;
        String saved = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_FP, "");
        return fingerprint().equals(saved);
    }

    public static void markApplied(Context c) {
        if (c == null) return;
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_FP, fingerprint()).apply();
    }

    public static void clearApplied(Context c) {
        if (c == null) return;
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove(KEY_FP).apply();
    }

    /**
     * 对外展示的摘要，已脱敏。
     * 只显示 IP 的前后各一段与端口，中间打码；SNI 同理。
     * 界面和日志都用这个，避免把完整节点信息暴露在截图或日志里。
     */
    public static String summary() {
        return maskHost(host()) + ":" + PORT + " · " + maskDomain(sni());
    }

    /** 23.149.28.61 → 23.***.***.61 */
    public static String maskHost(String h) {
        if (h == null || h.isEmpty()) return "***";
        String[] p = h.split("\\.");
        if (p.length == 4) {
            return p[0] + ".***.***." + p[3];
        }
        return maskDomain(h);
    }

    /** www.yahoo.com → w***.y***.com */
    public static String maskDomain(String d) {
        if (d == null || d.isEmpty()) return "***";
        String[] p = d.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length; i++) {
            if (sb.length() > 0) sb.append('.');
            String x = p[i];
            if (i == p.length - 1 && p.length > 1) {
                sb.append(x);
            } else if (x.length() <= 1) {
                sb.append('*');
            } else {
                sb.append(x.charAt(0)).append("***");
            }
        }
        return sb.toString();
    }

    public static File localConfigFile(Context c) {
        return new File(new File(c.getFilesDir(), "xray_dl"), "config.json");
    }
}
