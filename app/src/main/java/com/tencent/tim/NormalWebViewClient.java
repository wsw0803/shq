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
        // auth:// 回调但没有 access_token，说明参数丢失：给用户明确反馈，
        // 不再像以前那样静默 return true 让界面一直卡在登录页。
        if (low.startsWith("auth:")) {
            toast("登录回调缺少 access_token: " + url);
            return true;
        }
        // 其他自定义 scheme（about:、data:、intent: 等）按原逻辑拦截
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
        deliver(view, url);
    }
}
