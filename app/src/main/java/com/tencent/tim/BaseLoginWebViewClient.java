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

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (tokenGot || watchdogFired) return;
            watchdogFired = true;
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
                view.setWebChromeClient(new LoginChromeClient(activity, this));
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
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
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
            toast("页面加载失败: " + d);
        }
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
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
}
