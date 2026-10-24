package com.tencent.tim;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;

/**
 * 统一 HTTP 入口。
 *
 * 代理选择顺序：
 *   1) xray 本地 SOCKS 真的在监听 → 用它
 *   2) 用户手动配置的代理
 *   3) 直连
 *
 * 关键修复：
 * 原来用 XrayCore.isAlive() 判断，但那只反映 Java 侧的 Process 对象状态。
 * xray 由 su -c 启动，会脱离 Java 进程树；App 重启后 proc 为 null，
 * 即使核心其实正常运行，也会被判成「未运行」而退回直连。
 * 现在直接探测 127.0.0.1:10808 是否可连，这才是判断核心是否可用的可靠依据。
 */
public class Net {

    public static final String PREF = "net_pref";
    /** 手动代理，格式 host:port，为空表示不用。 */
    public static final String KEY_MANUAL_PROXY = "manual_proxy";

    private static volatile Proxy manualProxy = null;
    private static volatile boolean manualLoaded = false;

    /** 端口探测结果的短缓存，避免每个连接都探一次。 */
    private static volatile boolean coreUp = false;
    private static volatile long coreCheckedAt = 0L;
    private static final long CORE_PROBE_TTL = 5000L;

    public static void reload(Context c) {
        manualLoaded = false;
        manualProxy = null;
        parseManual(c);
        manualLoaded = true;
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
        // 先同步读回确认值，避免 apply() 异步写盘导致 reload 读到旧值
        String saved = hostPort == null ? "" : hostPort.trim();
        e.putString(KEY_MANUAL_PROXY, saved);
        e.apply();

        // 直接按新值重建，不依赖从磁盘重读
        synchronized (Net.class) {
            manualProxy = null;
            try {
                if (!saved.isEmpty()) {
                    int i = saved.lastIndexOf(':');
                    if (i > 0) {
                        String host = saved.substring(0, i).trim();
                        int port = Integer.parseInt(saved.substring(i + 1).trim());
                        if (host.length() > 0 && port > 0 && port < 65536) {
                            manualProxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            manualLoaded = true;
        }
    }

    public static String getManualProxy(Context c) {
        if (c == null) return "";
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_MANUAL_PROXY, "");
    }

    /**
     * 探测 xray 的 SOCKS 端口是否真的能连上。
     * 这才是「核心是否可用」的可靠依据，比看 Process 对象准。
     */
    public static boolean isCoreUp() {
        long now = System.currentTimeMillis();
        if (now - coreCheckedAt < CORE_PROBE_TTL) return coreUp;

        boolean up = false;
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", XrayCore.SOCKS_PORT), 400);
            up = true;
        } catch (Throwable ignored) {
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignored) {}
        }
        coreUp = up;
        coreCheckedAt = now;
        return up;
    }

    /** 强制下一次重新探测。 */
    public static void invalidateCoreProbe() {
        coreCheckedAt = 0L;
    }

    /** 打开连接。优先 xray（端口探测），其次手动代理，最后直连。 */
    public static HttpURLConnection open(Context c, String url, int connectTimeout, int readTimeout)
            throws Exception {
        HttpURLConnection conn;
        if (isCoreUp()) {
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
        if (isCoreUp()) return "代理 xray";
        if (manualProxyOrNull(c) != null) return "代理 手动";
        return "直连";
    }
}
