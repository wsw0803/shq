package com.tencent.tim;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.tencent.open.agent.AgentActivity;

/**
 * 登录模块通用基类（抓号 / 挂机共用）。
 *
 * 修复 Y700 四代 + 一加(氧OS/ColorOS)移植包「登录后卡住」的问题。
 * 这类移植包自带较新的 Chromium WebView，与源码原假设有三处差异，
 * 任意一处都会让界面停在登录页或白屏：
 *
 * 1) QQ 登录完成后是 302 到 auth://tauth.qq.com/?#access_token=xxx，
 *    新版 WebView 会静默丢弃非 http(s) 的导航，
 *    shouldOverrideUrlLoading 根本不会被调用。
 * 2) 登录页用 window.open 弹小窗，未设置 setSupportMultipleWindows(true)
 *    且没有 WebChromeClient.onCreateWindow 时，弹窗直接被忽略，
 *    表现就是点“登录”没反应。
 * 3) 移植 ROM 上第三方 Cookie 默认被拦，QQ 登录态写不进去，
 *    登录成功后又被踢回登录页（看起来也是“卡住”）。
 *
 * 对应做了多层兜底，任意一层命中都会把 token 交给 AgentActivity.performLogin：
 * 1) shouldOverrideUrlLoading(WebResourceRequest / String)  常规跳转拦截
 * 2) onPageStarted / onPageFinished  页面 URL 已带 token
 * 3) doUpdateVisitedHistory  部分 ROM 只回调历史变化
 * 4) JS 桥 + 500ms 轮询 location.href  从页面侧发现跳转
 * 另加 90 秒看门狗：疑似卡死时自动重载一次，避免无限等待。
 */
public abstract class BaseLoginWebViewClient extends WebViewClient {

    private static final long WATCHDOG_MS = 90000L;

    protected final AgentActivity activity;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String lastTokenUrl = "";
    private long lastTokenAt = 0L;
    private boolean configured = false;
    private boolean bridgeReady = false;
    private boolean tokenGot = false;
    private boolean errorToastShown = false;
    private boolean watchdogFired = false;
    private boolean appidErrorNotified = false;
    private boolean qrSwitchedNotified = false;

    /**
     * 页面健康检查脚本。返回纯 ASCII 标记，避免 evaluateJavascript 的 JSON 转义踩坑。
     * APPID_ERROR：QQ 登录页已经画出「无法获取appid」之类的错误，此时页面会永远转圈，
     * 必须立刻停止等待并提示用户，而不是让 90 秒看门狗再去盲目 reload 一次。
     */
    private static final String HEALTH_JS =
            "(function(){try{"
            + "if(location.href.indexOf('access_token=')>=0)return 'TOKEN:'+location.href;"
            + "var t=(document.body?document.body.innerText:'');"
            + "if(t.indexOf('无法获取appid')>=0||t.indexOf('无法获取APPID')>=0"
            + "||t.indexOf('无法获取AppID')>=0||t.indexOf('appid参数错误')>=0"
            + "||t.indexOf('请从游戏内登录')>=0)return 'APPID_ERROR';"
            + "return 'OK';}catch(e){return 'JS_ERROR';}})()";

    /**
     * 扫码面板守卫脚本。QQ 登录页里「账号/密码」(#pwdlogin) 与「扫码」(#qrlogin) 两个面板
     * 默认都是 display:none，由页面脚本按 pt_3rd_aid / force_qr 决定显示哪个：
     * 只要地址里少了 force_qr=1 或 pt_3rd_aid，页面就按移动端渲染成「输入账号密码」。
     * 这里在页面加载后把扫码面板重新显示出来，但仅当二维码图片已经有地址时才切，
     * 避免把用户钉在一个永远空白的面板上。
     */
    private static final String QR_GUARD_JS =
            "(function(){try{"
            + "var q=document.getElementById('qrlogin');"
            + "if(!q)return 'NOQR';"
            + "var p=document.getElementById('pwdlogin');"
            + "var on=function(e){if(!e)return false;"
            + "var st=window.getComputedStyle?window.getComputedStyle(e):null;"
            + "if(st&&(st.display=='none'||st.visibility=='hidden'))return false;"
            + "return (e.offsetHeight||0)>40;};"
            + "if(on(q))return 'QR_ON';"
            + "var img=document.getElementById('qrimg');"
            + "var src=img?(img.getAttribute('src')||''):'';"
            + "if(!src)return 'QR_NOSRC';"
            + "q.style.display='block';q.style.visibility='visible';"
            + "if(p){p.style.display='none';}"
            + "return 'QR_SWITCHED';}catch(e){return 'JS_ERROR';}})()";

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (tokenGot || watchdogFired) return;
            watchdogFired = true;
            com.tencent.tim.Diag.fail("Login", "watchdog.90s", "90 秒未捕获 token，将重载一次；url=" + lastUrlForDiag());
            toast("登录页 90 秒内没有返回结果，已自动重载一次");
            try {
                WebView v = lastView;
                if (v != null) {
                    CookieManager.getInstance().flush();
                    v.reload();
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private WebView lastView;

    /** 诊断日志用：拿当前页 URL，任何异常都退化成 "-"。 */
    private String lastUrlForDiag() {
        try {
            WebView v = lastView;
            if (v != null) {
                String u = v.getUrl();
                if (u != null && !u.isEmpty()) return u;
            }
        } catch (Throwable ignored) {
        }
        return "-";
    }

    /** 登录弹窗的 chrome 客户端，拿到 token 后把弹窗收起来。 */
    private LoginChromeClient chrome;

    protected BaseLoginWebViewClient(AgentActivity activity) {
        this.activity = activity;
    }

    public boolean isTokenUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        return url.toLowerCase().contains("access_token=");
    }

    protected boolean hasToken() {
        return tokenGot;
    }

    /**
     * 统一的 token 投递口。返回 true 表示这个 URL 已被当作登录结果处理过。
     */
    protected final boolean deliver(WebView view, String url) {
        if (!isTokenUrl(url)) return false;
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (url.equals(lastTokenUrl) && (now - lastTokenAt) < 8000L) return true;
            lastTokenUrl = url;
            lastTokenAt = now;
            tokenGot = true;
            try {
                int at = url == null ? -1 : url.indexOf("access_token=");
                String tail = "";
                if (url != null) {
                    int end = url.indexOf('&', at);
                    String tk = at < 0 ? url : url.substring(at + 13, end < 0 ? url.length() : end);
                    tail = "len=" + tk.length();
                }
                com.tencent.tim.Diag.step("Login", "deliver", "捕获到 token " + tail);
            } catch (Throwable ignored) {
            }
            if (chrome != null) { try { chrome.closePopup(); } catch (Throwable ignored) { } }
        }
        try {
            handler.removeCallbacks(watchdog);
        } catch (Throwable ignored) {
        }
        try {
            final String token = url;
            if (view != null) {
                view.stopLoading();
                view.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            activity.performLogin(token);
                        } catch (Throwable t) {
                            com.tencent.tim.Diag.fail("Login", "performLogin", "登录处理异常", t);
                            toast("登录处理异常: " + t);
                        }
                    }
                });
            } else {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            activity.performLogin(token);
                        } catch (Throwable t) {
                            com.tencent.tim.Diag.fail("Login", "performLogin", "登录处理异常", t);
                            toast("登录处理异常: " + t);
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        onTokenDelivered(view);
        return true;
    }

    /** 子类可覆写：拿到 token 之后的额外动作（例如挂机模式刷新页面） */
    protected void onTokenDelivered(WebView view) {
    }

    /**
     * 第一次拿到 WebView 时补齐所有移植 ROM 上必须的设置。
     * 幂等，可反复调用。
     */
    protected void prepare(final WebView view) {
        if (view == null) return;
        lastView = view;

        if (!configured) {
            configured = true;
            try {
                WebSettings ws = view.getSettings();
                ws.setJavaScriptEnabled(true);
                ws.setDomStorageEnabled(true);
                ws.setDatabaseEnabled(true);
                // 关键：允许 window.open 弹窗，否则点登录没反应
                ws.setSupportMultipleWindows(true);
                ws.setJavaScriptCanOpenWindowsAutomatically(true);
                ws.setUseWideViewPort(true);
                ws.setLoadWithOverviewMode(true);
                ws.setBuiltInZoomControls(true);
                ws.setDisplayZoomControls(false);
                ws.setSupportZoom(true);
                ws.setCacheMode(WebSettings.LOAD_DEFAULT);
                try {
                    ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                } catch (Throwable ignored) {
                }
                // 移植 ROM 上第三方 Cookie 常被默认拦截，QQ 登录态写不进去
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                try {
                    cm.setAcceptThirdPartyCookies(view, true);
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
            try {
                chrome = new LoginChromeClient(activity, this);
                view.setWebChromeClient(chrome);
                LoginWebBridge.install(activity, view, this);
                bridgeReady = true;
            } catch (Throwable ignored) {
            }
        } else if (!bridgeReady) {
            bridgeReady = true;
            try {
                LoginWebBridge.install(activity, view, this);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 页面加载完后按几个时间点做健康检查：QQ 的错误提示是异步画出来的。 */
    private void scheduleHealthChecks(final WebView view) {
        if (view == null) return;
        final long[] delays = new long[]{1500L, 4000L, 8000L, 15000L};
        for (int i = 0; i < delays.length; i++) {
            final long d = delays[i];
            try {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        checkHealth(view);
                    }
                }, d);
            } catch (Throwable ignored) {
            }
        }
    }

    private void checkHealth(final WebView view) {
        if (view == null || tokenGot || appidErrorNotified) return;
        try {
            view.evaluateJavascript(HEALTH_JS, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    onHealthResult(view, value);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 页面加载后多次尝试把登录页切到扫码面板（QQ 有时会在切换后才生成二维码）。 */
    private void scheduleQrGuard(final WebView view) {
        if (view == null) return;
        final long[] delays = new long[]{1200L, 3000L, 6000L, 10000L};
        for (int i = 0; i < delays.length; i++) {
            try {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        applyQrGuard(view);
                    }
                }, delays[i]);
            } catch (Throwable ignored) {
            }
        }
    }

    private void applyQrGuard(final WebView view) {
        if (view == null || tokenGot || qrSwitchedNotified) return;
        try {
            view.evaluateJavascript(QR_GUARD_JS, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    onQrGuardResult(value);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void onQrGuardResult(String raw) {
        if (raw == null) return;
        String v = raw.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        logQr("guard=" + v);
        if (v.startsWith("QR_NOSRC") || v.startsWith("NOQR") || v.startsWith("JS_ERROR")) {
            com.tencent.tim.Diag.fail("Login", "onQrGuardResult", "扫码面板不可用: " + v + " url=" + lastUrlForDiag());
        }
        if (v.startsWith("QR_SWITCHED") && !qrSwitchedNotified) {
            qrSwitchedNotified = true;
            toast("已切换到扫码登录");
        }
    }

    /** 把扫码面板的状态写进 qr_debug.txt，方便远程排查「不显示二维码」这类只在真机上出现的问题。 */
    private void logQr(String msg) {
        try {
            java.io.File f = new java.io.File(com.tencent.tim.TokenStore.rootDir(), "qr_debug.txt");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date()) + " " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private void onHealthResult(WebView view, String raw) {
        if (raw == null || tokenGot) return;
        String v = raw.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        if (v.startsWith("TOKEN:")) {
            deliver(view, v.substring(6));
            return;
        }
        if (v.contains("APPID_ERROR") && !appidErrorNotified) {
            appidErrorNotified = true;
            com.tencent.tim.Diag.fail("Login", "onHealthResult", "页面出现「无法获取appid」类文案；url=" + lastUrlForDiag());
            try {
                handler.removeCallbacks(watchdog);
            } catch (Throwable ignored) {
            }
            try {
                if (view != null) view.stopLoading();
            } catch (Throwable ignored) {
            }
            try {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            activity.onLoginAppidMissing("");
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private void startWatchdog(WebView view) {
        if (view == null || tokenGot) return;
        if (watchdogFired) return;
        try {
            handler.removeCallbacks(watchdog);
            handler.postDelayed(watchdog, WATCHDOG_MS);
        } catch (Throwable ignored) {
        }
    }

    protected void toast(final String msg) {
        try {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        prepare(view);
        startWatchdog(view);
        deliver(view, url);
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        deliver(view, url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        prepare(view);
        try {
            LoginWebBridge.inject(view);
        } catch (Throwable ignored) {
        }
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        if (!deliver(view, url) && view != null) {
            deliver(view, view.getUrl());
        }
        try {
            if (url != null && url.indexOf("xlogin") >= 0) logQr("load " + url);
        } catch (Throwable ignored) {
        }
        scheduleHealthChecks(view);
        scheduleQrGuard(view);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
        com.tencent.tim.Diag.fail("Login", "onReceivedError(deprecated)",
                "code=" + errorCode + " desc=" + description + " url=" + failingUrl);
        if (!errorToastShown) {
            errorToastShown = true;
            toast("页面加载失败(" + errorCode + "): " + description);
        }
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        super.onReceivedError(view, request, error);
        try {
            String u = request == null ? "?" : String.valueOf(request.getUrl());
            String d0 = error == null ? "?" : String.valueOf(error.getDescription());
            int c0 = error == null ? -1 : error.getErrorCode();
            boolean main = request != null && request.isForMainFrame();
            if (main) {
                com.tencent.tim.Diag.fail("Login", "onReceivedError", "main=true code=" + c0 + " desc=" + d0 + " url=" + u);
            } else {
                com.tencent.tim.Diag.warn("Login", "onReceivedError", "子资源失败 code=" + c0 + " desc=" + d0 + " url=" + u);
            }
        } catch (Throwable ignored) {
        }
        if (request != null && request.isForMainFrame() && !errorToastShown) {
            errorToastShown = true;
            String d = "";
            try {
                d = String.valueOf(error.getDescription());
            } catch (Throwable ignored) {
            }
            toast("页面加载失败: " + d);
        }
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        boolean crashed = true;
        try {
            crashed = detail == null || detail.didCrash();
        } catch (Throwable ignored) {
        }
        com.tencent.tim.Diag.fail("Login", "onRenderProcessGone", "didCrash=" + crashed + " url=" + lastUrlForDiag());
        toast("WebView 渲染进程崩溃，正在自动恢复界面");
        try {
            handler.removeCallbacks(watchdog);
        } catch (Throwable ignored) {
        }
        try {
            view.destroy();
        } catch (Throwable ignored) {
        }
        try {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        activity.recreate();
                    } catch (Throwable t) {
                        try {
                            activity.finish();
                        } catch (Throwable ignored2) {
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * 自定义 scheme 分类处理（抓号 / 挂机共用）。
     *
     * 以前子类对所有非 http(s) 一律 return true，把 about: / data: / blob:
     * 这类内联页面也一起吞掉，登录页里的二维码、协议页就这么白屏；
     * intent:// 之类的跳转也被静默丢弃，看起来就是「点登录没反应」。
     *
     * 现在按协议分类：
     * - http(s) 交给 WebView 自己加载
     * - about:/data:/blob:/javascript:/file: 内联内容放行
     * - auth: 已在上一步尝试取 token，取不到给出明确提示
     * - 其余自定义 scheme 交给系统，交不出去也要说出来，不静默
     */
    protected boolean handleUrl(WebView view, String url) {
        if (url == null) return false;
        prepare(view);
        if (deliver(view, url)) return true;
        String low = url.toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("http://") || low.startsWith("https://")) return false;
        if (low.startsWith("about:") || low.startsWith("data:") || low.startsWith("blob:")
                || low.startsWith("javascript:") || low.startsWith("file:")) {
            return false;
        }
        if (low.startsWith("auth:")) {
            com.tencent.tim.Diag.fail("Login", "handleUrl.auth", "回调缺少 access_token: " + url);
            toast("登录回调缺少 access_token: " + url);
            return true;
        }
        try {
            android.content.Intent intent = android.content.Intent.parseUri(
                    url, android.content.Intent.URI_INTENT_SCHEME);
            intent.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            activity.startActivity(intent);
        } catch (Throwable t) {
            com.tencent.tim.Diag.fail("Login", "handleUrl.intent", "无法处理的跳转: " + url, t);
            toast("无法处理的跳转: " + url);
        }
        return true;
    }
}
