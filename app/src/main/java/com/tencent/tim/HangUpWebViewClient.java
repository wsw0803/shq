package com.tencent.tim;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.tencent.open.agent.AgentActivity;

public class HangUpWebViewClient extends WebViewClient {
    private AgentActivity activity;
    public HangUpWebViewClient(AgentActivity activity) { this.activity = activity; }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return handle(view, request.getUrl().toString());
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handle(view, url);
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        if (url != null && (url.startsWith("auth:") || url.contains("access_token="))) {
            handle(view, url);
        }
    }

    private boolean handle(WebView view, String url) {
        if (url == null) return false;
        if (url.startsWith("auth:") || url.contains("access_token=")) {
            activity.performLogin(url);
            return true;
        }
        String host = "";
        try { host = Uri.parse(url).getHost(); } catch (Throwable ignored) {}
        if (host == null) host = "";
        if (host.endsWith("qq.com") || host.endsWith("qq.com.cn")) return false;
        return true;
    }
}