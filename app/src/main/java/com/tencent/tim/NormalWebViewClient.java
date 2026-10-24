package com.tencent.tim;

import android.graphics.Bitmap;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import com.tencent.open.agent.AgentActivity;

/**
 * 抓号模式 WebView 客户端。
 *
 * 原来只重写 shouldOverrideUrlLoading / onPageStarted 判断 access_token=，
 * 在 Y700 四代 + 一加移植包上（新版 WebView 会丢弃 auth:// 自定义 scheme 跳转）
 * 会出现登录完成后卡住不动。现改为继承 BaseLoginWebViewClient，
 * 由基类的四层兜底统一捕获 token。
 */
public class NormalWebViewClient extends BaseLoginWebViewClient {

    public NormalWebViewClient(AgentActivity activity) {
        super(activity);
    }

    private boolean handle(WebView view, String url) {
        if (url == null) return false;
        prepare(view);
        if (deliver(view, url)) return true;
        String low = url.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")) return false;
        // 非 http(s) 且不含 token 的自定义 scheme（如 auth:// 回调但参数缺失）
        // 旧逻辑直接返回 true 会静默吞掉跳转，导致界面卡在登录页，
        // 这里放行并给出提示，避免无反馈。
        toast("拦截到登录回调: " + url);
        return true;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return handle(view, request.getUrl() == null ? null : request.getUrl().toString());
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handle(view, url);
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        handle(view, url);
    }
}
