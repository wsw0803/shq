package com.tencent.tim;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.tencent.open.agent.AgentActivity;

/**
 * 页面侧的 token 探测器。
 *
 * 移植包 ROM 上 QQ 登录完成后是 302 到 auth://tauth.qq.com/?#access_token=...
 * 新版 WebView 会直接丢弃这次导航，Java 侧收不到任何回调。
 * 因此在页面里注入脚本：
 * - 接管 window.open，把弹窗转成本页跳转并上报 URL
 * - 挂钩 history.pushState / replaceState
 * - 监听 hashchange
 * - 每 500ms 轮询 location.href
 * 只要发现 URL 里带 access_token 就回传给 Java。
 */
public class LoginWebBridge {

    private static final String JS =
            "(function(){"
            + "try{"
            + "if(window.__weiLoginHooked){return;}"
            + "window.__weiLoginHooked=true;"
            + "var B=window.WeiLoginBridge||null;"
            + "function send(u){"
            + "try{"
            + "if(!u){return;}"
            + "var s=String(u);"
            + "if(s.indexOf('access_token=')<0){return;}"
            + "if(B&&B.onUrl){B.onUrl(s);}"
            + "}catch(e){}"
            + "}"
            + "try{"
            + "var _open=window.open;"
            + "window.open=function(u){"
            + "try{ if(u){ send(u); try{ location.href=String(u); }catch(e2){} return null; } }catch(e){}"
            + "try{ return _open? _open.call(window,u):null; }catch(e){}"
            + "return null;"
            + "};"
            + "}catch(e){}"
            + "function wrap(n){"
            + "try{"
            + "var f=history[n];"
            + "if(!f){return;}"
            + "history[n]=function(){var r=f.apply(this,arguments);try{send(location.href);}catch(e){}return r;};"
            + "}catch(e){}"
            + "}"
            + "wrap('pushState');"
            + "wrap('replaceState');"
            + "try{window.addEventListener('hashchange',function(){send(location.href);},false);}catch(e){}"
            + "setInterval(function(){try{send(location.href);}catch(e){}},500);"
            + "}catch(e){}"
            + "})();";

    private final AgentActivity activity;
    private final WebView view;
    private final BaseLoginWebViewClient client;

    private LoginWebBridge(AgentActivity activity, WebView view, BaseLoginWebViewClient client) {
        this.activity = activity;
        this.view = view;
        this.client = client;
    }

    public static void install(AgentActivity activity, WebView view, BaseLoginWebViewClient client) {
        if (view == null) return;
        try {
            view.getSettings().setJavaScriptEnabled(true);
        } catch (Throwable ignored) {
        }
        try {
            view.addJavascriptInterface(new LoginWebBridge(activity, view, client), "WeiLoginBridge");
        } catch (Throwable ignored) {
        }
        inject(view);
    }

    public static void inject(WebView view) {
        if (view == null) return;
        try {
            view.evaluateJavascript(JS, null);
        } catch (Throwable t) {
            try {
                view.loadUrl("javascript:" + JS);
            } catch (Throwable ignored) {
            }
        }
    }

    @JavascriptInterface
    public void onUrl(String url) {
        if (url == null || url.isEmpty()) return;
        final String u = url;
        if (!client.isTokenUrl(u)) return;
        try {
            view.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        client.deliver(view, u);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    @JavascriptInterface
    public void onReady() {
        // 仅用于确认脚本已注入，无需处理
    }
}
