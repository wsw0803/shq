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
 *
 * 跳转分类统一交给 handleUrl：内联页面放行、自定义 scheme 交给系统，
 * 不再像以前那样把 about:/data:/blob:/intent:// 全部静默吞掉。
 */
public class NormalWebViewClient extends BaseLoginWebViewClient {

    public NormalWebViewClient(AgentActivity activity) {
        super(activity);
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return handleUrl(view, request.getUrl() == null ? null : request.getUrl().toString());
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handleUrl(view, url);
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        deliver(view, url);
    }
}
