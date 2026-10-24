package com.tencent.tim;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * 代理节点配置。
 *
 * 把节点参数与 XrayCore 分开，方便换节点：
 * 改这里之后不需要重新下载 31MB 的二进制，
 * 只要调一次 applyConfig() 把新配置写到 /data/local/tmp/shq/config.json 即可。
 *
 * 当前节点对应分享链接：
 *   vless://9c82c018-70ec-4ede-a886-de6bd76f4eba@23.149.28.61:22123
 *     ?security=reality&encryption=none
 *     &pbk=ZlsbyvqvvvEHAXvwabXrHsgm0TMCFDG8cRYUhIbSTFg
 *     &fp=chrome&type=tcp&flow=xtls-rprx-vision
 *     &sni=www.yahoo.com&sid=6f2949cd
 */
public class NodeConfig {

    /** 服务端地址。本例是 IP，Reality 的伪装域名由 serverName 指定。 */
    public static final String HOST = "23.149.28.61";
    public static final int PORT = 22123;
    public static final String UUID = "9c82c018-70ec-4ede-a886-de6bd76f4eba";
    /** Reality 伪装的目标域名，必须与服务端一致。 */
    public static final String SNI = "www.yahoo.com";
    public static final String PUBLIC_KEY = "ZlsbyvqvvvEHAXvwabXrHsgm0TMCFDG8cRYUhIbSTFg";
    public static final String SHORT_ID = "6f2949cd";
    public static final String FINGERPRINT = "chrome";
    public static final String SPIDER_X = "/";
    public static final String FLOW = "xtls-rprx-vision";

    /**
     * 配置指纹。参数一变，ensureConfig() 就会自动重写 config.json，
     * 避免「换了节点但 APP 还在用旧配置」。
     */
    public static String fingerprint() {
        return HOST + "|" + PORT + "|" + UUID + "|" + SNI + "|"
                + PUBLIC_KEY + "|" + SHORT_ID + "|" + FINGERPRINT + "|" + FLOW;
    }

    private static final String PREF = "node_pref";
    private static final String KEY_FP = "cfg_fp";

    /** xray 的 config.json 内容：单 SOCKS 入站 + VLESS Reality 出站。 */
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
                + "        \"address\": \"" + HOST + "\",\n"
                + "        \"port\": " + PORT + ",\n"
                + "        \"users\": [{\n"
                + "          \"id\": \"" + UUID + "\",\n"
                + "          \"encryption\": \"none\",\n"
                + "          \"flow\": \"" + FLOW + "\"\n"
                + "        }]\n"
                + "      }]\n"
                + "    },\n"
                + "    \"streamSettings\": {\n"
                + "      \"network\": \"tcp\",\n"
                + "      \"security\": \"reality\",\n"
                + "      \"realitySettings\": {\n"
                + "        \"serverName\": \"" + SNI + "\",\n"
                + "        \"fingerprint\": \"" + FINGERPRINT + "\",\n"
                + "        \"publicKey\": \"" + PUBLIC_KEY + "\",\n"
                + "        \"shortId\": \"" + SHORT_ID + "\",\n"
                + "        \"spiderX\": \"" + SPIDER_X + "\"\n"
                + "      }\n"
                + "    }\n"
                + "  }]\n"
                + "}\n";
    }

    /** 已写入的配置指纹是否与当前参数一致。 */
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

    /** 供日志/界面显示的节点摘要。 */
    public static String summary() {
        return HOST + ":" + PORT + " · " + SNI;
    }

    /** 配置文件在设备上的落点。 */
    public static File localConfigFile(Context c) {
        return new File(new File(c.getFilesDir(), "xray_dl"), "config.json");
    }
}
