package com.tencent.tim;

import android.graphics.Bitmap;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.tencent.open.agent.AgentActivity;

public class NormalWebViewClient extends WebViewClient {

    private final AgentActivity activity;

    public NormalWebViewClient(AgentActivity activity) {
        this.activity = activity;
    }

    private boolean handle(String url) {
        if (url == null) return false;
        String low = url.toLowerCase();
        if (low.startsWith("auth:") || low.contains("access_token=")) {
            activity.performLogin(url);
            return true;
        }
        return !(low.startsWith("http://") || low.startsWith("https://"));
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return handle(request.getUrl() == null ? null : request.getUrl().toString());
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handle(url);
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        if (url != null && url.toLowerCase().contains("access_token=")) handle(url);
    }
}
