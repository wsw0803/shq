package com.tencent.tim;

import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.tencent.open.agent.AgentActivity;

public class NormalWebViewClient extends WebViewClient {

    private AgentActivity activity;

    public NormalWebViewClient(AgentActivity activity) {
        this.activity = activity;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        if (url.startsWith("auth:")) {
            activity.performLogin(url);
            return true;
        }
        return super.shouldOverrideUrlLoading(view, request);
    }
}