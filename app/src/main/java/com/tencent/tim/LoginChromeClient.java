package com.tencent.tim;

import android.graphics.Bitmap;
import android.os.Message;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.tencent.open.agent.AgentActivity;

/**
 * 接管登录页的弹窗与 window.open。
 *
 * QQ 登录页常用 window.open 弹小窗（扫码确认、二次验证、协议页）。
 * 只在 onCreateWindow 里 new 一个 WebView 交给 transport 是不够的：
 * 不在视图树里的 WebView 既不渲染也收不到触摸，表现就是点登录毫无反应、
 * 弹窗永远不出现，最终抓不到 token。
 *
 * 这里不显示那层不可见的弹窗，而是把它加载出来的内容直接转交给主线 WebView：
 * 页面仍然“以为”弹窗开成功了（window.open 返回非 null，不会走降级或报错），
 * 用户看到并操作的始终是可见的主窗口，auth: 回调也一定回到主 client。
 * 这也正是 0.0.1 能抓到的原因 —— 那时根本没有开多窗口，页面自己降级成本页跳转。
 */
public class LoginChromeClient extends WebChromeClient {

    private final AgentActivity activity;
    private final BaseLoginWebViewClient client;

    /** 主（可见的）WebView，弹窗内容都往它身上灌。 */
    private WebView mainView;

    public LoginChromeClient(AgentActivity activity, BaseLoginWebViewClient client) {
        this.activity = activity;
        this.client = client;
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                  Message resultMsg) {
        try {
            if (view != null) mainView = view;

            final WebView popup = new WebView(activity);
            popup.getSettings().setJavaScriptEnabled(true);
            popup.getSettings().setDomStorageEnabled(true);
            popup.getSettings().setSupportMultipleWindows(false);
            popup.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    return forward(request.getUrl() == null ? null : request.getUrl().toString());
                }

                @Override
                @SuppressWarnings("deprecation")
                public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    return forward(url);
                }

                @Override
                public void onPageStarted(WebView v, String url, Bitmap favicon) {
                    forward(url);
                }
            });

            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 弹窗里的目标地址转交给主 WebView。
     * 带 token / auth: 的回调先走统一处理；其余 http(s) 直接在主窗口打开。
     */
    private boolean forward(String url) {
        try {
            if (url == null || url.isEmpty()) return false;
            final WebView target = mainView;
            if (target == null) return false;
            final String u = url;
            if (!client.handleUrl(target, u)) {
                target.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            target.loadUrl(u);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 兼容 BaseLoginWebViewClient 的调用：现在没有覆盖层可收，
     * 保留方法只是为了让「拿到 token 就收尾」的语义不变。
     */
    public void closePopup() {
    }

    /**
     * 页面 JS 报错 / console.error 全记下来：QQ 登录页是强依赖脚本的单页应用，
     * 脚本一挂就是白屏或一直转圈，而 App 侧原本完全无感。
     */
    @Override
    public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
        try {
            if (cm != null) {
                int level = cm.messageLevel() == null ? 0 : cm.messageLevel().ordinal();
                // 0=ERROR 1=WARNING；别的级别只当普通步骤，避免日志被 info 刷满
                if (level <= 1) {
                    String where = cm.sourceId() + ":" + cm.lineNumber();
                    if (level == 0) {
                        Diag.fail("Login", "onConsoleMessage", cm.message() + " @ " + where);
                    } else {
                        Diag.warn("Login", "onConsoleMessage", cm.message() + " @ " + where);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public void onCloseWindow(WebView window) {
        super.onCloseWindow(window);
        try {
            if (window != null) {
                window.setWebViewClient(null);
                window.stopLoading();
                window.destroy();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
        // 部分登录页用 alert 做跳转提示，不能让它静默吞掉事件
        try {
            result.confirm();
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
        try {
            result.confirm();
        } catch (Throwable ignored) {
        }
        return true;
    }
}
