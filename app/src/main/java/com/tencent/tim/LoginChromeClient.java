package com.tencent.tim;

import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import com.tencent.open.agent.AgentActivity;

/**
 * 接管登录页的弹窗与 window.open。
 *
 * 移植包 ROM 上 QQ 登录页常用 window.open 弹小窗，若没有 onCreateWindow 接管，
 * 弹窗会被直接忽略 —— 表现为点登录没反应、界面卡在登录页。
 * 这里让弹窗沿用主 WebView 加载，并在新窗口 URL 里直接嗅探 token。
 */
public class LoginChromeClient extends WebChromeClient {

    private final AgentActivity activity;
    private final BaseLoginWebViewClient client;

    public LoginChromeClient(AgentActivity activity, BaseLoginWebViewClient client) {
        this.activity = activity;
        this.client = client;
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                  android.os.Message resultMsg) {
        try {
            final WebView popup = new WebView(activity);
            popup.getSettings().setJavaScriptEnabled(true);
            popup.getSettings().setDomStorageEnabled(true);
            popup.getSettings().setUseWideViewPort(true);
            popup.getSettings().setLoadWithOverviewMode(true);
            popup.setWebViewClient(client);
            popup.setWebChromeClient(this);
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        } catch (Throwable t) {
            return false;
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
