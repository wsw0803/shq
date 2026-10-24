package com.tencent.tim;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

/**
 * 统一 HTTP 入口。
 *
 * 之前 UpdateChecker / XrayCore / Updater 各自 new URL().openConnection()，
 * 只有 Updater 挂了代理，另外两处直连。于是出现：
 *   - 查更新、查提交直连，网络受阻时静默失败，看起来像「没有新版本」
 *   - 核心下载直连失败 → 代理起不来 → APK 下载也退化成直连 → 卡住
 *
 * 现在所有请求都从这里走，代理选择顺序：
 *   1) XrayCore 已启动 → 用它的本地 SOCKS
 *   2) 用户手动配置的代理（应对首次引导时核心还没下来的情况）
 *   3) 直连
 *
 * 手动代理就是为了打破「核心也要下载」的死锁：
 * 用户若已有可用的本地代理（如 Clash 的 7890 端口），填进来即可完成引导。
 */
public class Net {

    public static final String PREF = "net_pref";
    /** 手动代理，格式 host:port，为空表示不用。 */
    public static final String KEY_MANUAL_PROXY = "manual_proxy";

    private static volatile Proxy manualProxy = null;
    private static volatile boolean manualLoaded = false;

    public static void reload(Context c) {
        manualLoaded = false;
        manualProxy = null;
        parseManual(c);
    }

    private static void parseManual(Context c) {
        if (c == null) return;
        try {
            String s = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY_MANUAL_PROXY, "");
            if (s == null) return;
            s = s.trim();
            if (s.isEmpty()) return;
            int i = s.lastIndexOf(':');
            if (i <= 0) return;
            String host = s.substring(0, i).trim();
            int port = Integer.parseInt(s.substring(i + 1).trim());
            if (host.length() > 0 && port > 0 && port < 65536) {
                manualProxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 手动代理；没有则返回 null。供 XrayCore 下载核心时使用。 */
    public static Proxy manualProxyOrNull(Context c) {
        if (!manualLoaded) {
            synchronized (Net.class) {
                if (!manualLoaded) {
                    parseManual(c);
                    manualLoaded = true;
                }
            }
        }
        return manualProxy;
    }

    public static void setManualProxy(Context c, String hostPort) {
        if (c == null) return;
        SharedPreferences.Editor e = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        if (hostPort == null || hostPort.trim().isEmpty()) {
            e.remove(KEY_MANUAL_PROXY);
        } else {
            e.putString(KEY_MANUAL_PROXY, hostPort.trim());
        }
        e.apply();
        reload(c);
    }

    public static String getManualProxy(Context c) {
        if (c == null) return "";
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_MANUAL_PROXY, "");
    }

    /** 打开连接。优先 xray，其次手动代理，最后直连。 */
    public static HttpURLConnection open(Context c, String url, int connectTimeout, int readTimeout)
            throws Exception {
        HttpURLConnection conn;
        if (XrayCore.isAlive()) {
            conn = (HttpURLConnection) new URL(url).openConnection(XrayCore.socksProxy());
        } else {
            Proxy m = manualProxyOrNull(c);
            conn = (m != null)
                    ? (HttpURLConnection) new URL(url).openConnection(m)
                    : (HttpURLConnection) new URL(url).openConnection();
        }
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Cache-Control", "no-cache");
        return conn;
    }

    /** 当前实际会走哪条路，用于界面提示。 */
    public static String routeName(Context c) {
        if (XrayCore.isAlive()) return "代理 xray";
        if (manualProxyOrNull(c) != null) return "代理 手动";
        return "直连";
    }
}
