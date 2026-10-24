package com.tencent.tim;

import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;

import com.tencent.open.agent.AgentActivity;

/**
 * 接管登录页的弹窗与 window.open。
 *
 * 修复一个关键 bug：
 * 原来 onCreateWindow 创建了 WebView 并交给 resultMsg，
 * 但从未 addView 到任何容器。
 * 不在视图树里的 WebView 不会渲染、也收不到触摸，
 * 表现就是「点登录没反应」或弹窗区域空白。
 * 依赖 window.open 完成登录的游戏会因此卡死。
 *
 * 现在建真实覆盖层容器把弹窗挂进去，
 * 并在右上角加「关闭」按钮——很多登录页不实现 window.close，
 * 没有兜底入口用户会被困住。
 */
public class LoginChromeClient extends WebChromeClient {

    private final AgentActivity activity;
    private final BaseLoginWebViewClient client;

    private FrameLayout popupHost;
    private WebView currentPopup;

    public LoginChromeClient(AgentActivity activity, BaseLoginWebViewClient client) {
        this.activity = activity;
        this.client = client;
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                  Message resultMsg) {
        LoginDiag.add("POPUP", "onCreateWindow isDialog=" + isDialog + " gesture=" + isUserGesture);
        try {
            closePopup();

            final WebView popup = new WebView(activity);
            popup.getSettings().setJavaScriptEnabled(true);
            popup.getSettings().setDomStorageEnabled(true);
            popup.getSettings().setDatabaseEnabled(true);
            popup.getSettings().setUseWideViewPort(true);
            popup.getSettings().setLoadWithOverviewMode(true);
            popup.getSettings().setSupportMultipleWindows(true);
            popup.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
            popup.setWebViewClient(client);
            popup.setWebChromeClient(this);

            FrameLayout host = new FrameLayout(activity);
            host.setBackgroundColor(0xFF0A0F1A);
            host.addView(popup, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            Button close = new Button(activity);
            close.setText("关闭");
            close.setTextSize(12);
            close.setTextColor(0xFFF5F7FA);
            close.setBackground(TokenDialog.glass(activity, 12, false));
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    LoginDiag.add("POPUP", "用户手动关闭");
                    closePopup();
                }
            });
            int pad = (int) (10 * activity.getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            closeLp.gravity = Gravity.TOP | Gravity.END;
            closeLp.topMargin = pad;
            closeLp.rightMargin = pad;
            host.addView(close, closeLp);

            ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
            root.addView(host, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            popupHost = host;
            currentPopup = popup;

            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            LoginDiag.add("POPUP", "已挂载并接收 transport");
            return true;
        } catch (Throwable t) {
            LoginDiag.add("POPUP", "创建失败: " + t);
            closePopup();
            return false;
        }
    }

    @Override
    public void onCloseWindow(WebView window) {
        super.onCloseWindow(window);
        LoginDiag.add("POPUP", "onCloseWindow");
        if (window != null && window == currentPopup) {
            closePopup();
        }
    }

    public void closePopup() {
        WebView p = currentPopup;
        FrameLayout host = popupHost;
        currentPopup = null;
        popupHost = null;

        if (host != null) {
            try {
                ViewParent parent = host.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(host);
                }
            } catch (Throwable ignored) {
            }
        }
        if (p != null) {
            try {
                p.stopLoading();
                p.loadUrl("about:blank");
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean hasPopup() {
        return currentPopup != null && popupHost != null;
    }

    @Override
    public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
        LoginDiag.add("JS", "alert: " + message);
        try {
            result.confirm();
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
        LoginDiag.add("JS", "confirm: " + message);
        try {
            result.confirm();
        } catch (Throwable ignored) {
        }
        return true;
    }
}
