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
 * 多层兜底抓 token：
 * 1) shouldOverrideUrlLoading  常规跳转
 * 2) onPageStarted / Finished  页面 URL
 * 3) doUpdateVisitedHistory    部分 ROM 只回调历史
 * 4) JS 桥 + 轮询              从页面侧发现跳转
 *
 * 关键修复（此前推送失败未入库）：
 * - LoginChromeClient 复用同一实例。原来每次 prepare() 都 new 一个，
 *   弹窗容器状态全丢，依赖 window.open 登录的游戏会卡死。
 * - 拿到 token 后收起弹窗，避免盖住主界面。
 *
 * 每条回调都写入 LoginDiag，并自动挂上「诊断」悬浮按钮，
 * 卡住时用户点一下即可导出 URL 轨迹给开发者。
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

    /** 复用同一个 ChromeClient，保住弹窗容器状态。 */
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

    /** 统一的 token 投递口。 */
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

    /** 幂等地补齐 WebView 设置，并复用 ChromeClient 与 JS 桥。 */
    protected void prepare(final WebView view) {
        if (view == null) return;
        lastView = view;

        if (!configured) {
            configured = true;
            LoginDiag.begin();
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
            } catch (Throwable ignored) {
            }

            // 挂上诊断按钮，卡住时用户可自行导出
            try {
                LoginDiag.attachButton(activity, activity.getSelectedGameName());
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
        LoginDiag.add("PAGE+", url);
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
