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
 * 多层兜底抓 token：跳转 / 页面开始结束 / 历史变化 / JS 桥轮询。
 *
 * 两种「登录卡住」病因在此分别埋点，便于区分：
 * A) Cookie 被拦：登录页正常，扫码成功但被踢回登录页。
 *    每步记录关键域 cookie 长度。
 * B) window.open 被吞：点登录毫无反应。
 *    通过 setSupportMultipleWindows + 真实挂载容器解决。
 *
 * 日志由设置页的开关控制，关闭时 add() 直接丢弃，零开销。
 * 不再有悬浮球。
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
    private int pageCount = 0;

    private LoginChromeClient chromeClient;

    private WebView lastView;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (tokenGot || watchdogFired) return;
            watchdogFired = true;
            LoginDiag.add("WATCHDOG", "90s 无 token，重载页面");
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

    protected final boolean deliver(WebView view, String url) {
        if (!isTokenUrl(url)) return false;
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (url.equals(lastTokenUrl) && (now - lastTokenAt) < 8000L) return true;
            lastTokenUrl = url;
            lastTokenAt = now;
            tokenGot = true;
        }
        LoginDiag.add("TOKEN", "命中 token URL");
        logCookieState("拿到token时");
        try {
            handler.removeCallbacks(watchdog);
        } catch (Throwable ignored) {
        }

        closePopup();

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
                            LoginDiag.add("ERR", "performLogin: " + t);
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
                            LoginDiag.add("ERR", "performLogin: " + t);
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

    protected void onTokenDelivered(WebView view) {
    }

    /**
     * 记录 Cookie 状态。
     * 登录态主要落在 qq.com / ptlogin2.qq.com / connect.qq.com，
     * 记录三处长度即可判断是否写入成功。
     */
    protected void logCookieState(String when) {
        try {
            CookieManager cm = CookieManager.getInstance();
            String qq = cm.getCookie("https://qq.com");
            String pt = cm.getCookie("https://xui.ptlogin2.qq.com");
            String connect = cm.getCookie("https://connect.qq.com");
            int qqLen = qq == null ? 0 : qq.length();
            int ptLen = pt == null ? 0 : pt.length();
            int cnLen = connect == null ? 0 : connect.length();
            LoginDiag.add("COOKIE", when + " qq.com=" + qqLen
                    + " ptlogin=" + ptLen + " connect=" + cnLen
                    + " 有登录态=" + (ptLen > 0 || cnLen > 0));
        } catch (Throwable t) {
            LoginDiag.add("COOKIE", when + " 读取失败: " + t);
        }
    }

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
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                try {
                    cm.setAcceptThirdPartyCookies(view, true);
                } catch (Throwable ignored) {
                }
                LoginDiag.add("SETUP", "UA=" + ws.getUserAgentString());
                logCookieState("初始化时");
            } catch (Throwable ignored) {
            }
        }

        if (chromeClient == null) {
            try {
                chromeClient = new LoginChromeClient(activity, this);
                view.setWebChromeClient(chromeClient);
            } catch (Throwable ignored) {
            }
        }

        if (!bridgeReady) {
            bridgeReady = true;
            try {
                LoginWebBridge.install(activity, view, this);
            } catch (Throwable ignored) {
            }
        }
    }

    protected void closePopup() {
        if (chromeClient != null) {
            try {
                chromeClient.closePopup();
            } catch (Throwable ignored) {
            }
        }
    }

    private void startWatchdog(WebView view) {
        if (view == null || tokenGot || watchdogFired) return;
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
        pageCount++;
        LoginDiag.add("PAGE+", "#" + pageCount + " " + url);
        prepare(view);
        startWatchdog(view);
        deliver(view, url);
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        if (url != null && !isTokenUrl(url)) {
            LoginDiag.add("HIST", url);
        }
        deliver(view, url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        LoginDiag.add("PAGE-", url);
        prepare(view);
        try {
            LoginWebBridge.inject(view);
        } catch (Throwable ignored) {
        }
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        logCookieState("页面加载完成");
        if (!deliver(view, url) && view != null) {
            deliver(view, view.getUrl());
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
        LoginDiag.add("ERR", "code=" + errorCode + " " + description + " @ " + failingUrl);
        if (!errorToastShown) {
            errorToastShown = true;
            toast("页面加载失败(" + errorCode + "): " + description);
        }
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        super.onReceivedError(view, request, error);
        if (request != null && request.isForMainFrame() && !errorToastShown) {
            errorToastShown = true;
            String d = "";
            try {
                d = String.valueOf(error.getDescription());
            } catch (Throwable ignored) {
            }
            LoginDiag.add("ERR", "mainFrame " + d);
            toast("页面加载失败: " + d);
        }
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        LoginDiag.add("CRASH", "WebView 渲染进程崩溃");
        toast("WebView 渲染进程崩溃，正在自动恢复界面");
        try {
            handler.removeCallbacks(watchdog);
        } catch (Throwable ignored) {
        }
        closePopup();
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
}
