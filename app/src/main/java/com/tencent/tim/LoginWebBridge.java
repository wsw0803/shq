package com.tencent.tim;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.tencent.open.agent.AgentActivity;

/**
 * 页面侧的 token 探测器。
 *
 * 关键修复：
 * 原来无条件劫持 window.open —— 不管参数是不是 token，
 * 都强制 location.href = u 并 return null，
 * 把页面正常的业务弹窗（二次验证、协议页）全部破坏。
 * 现在只有 URL 里真的含 access_token 时才接管，其余原样放行。
 *
 * 轮询从 500ms 放宽到 800ms，并在拿到 token 后停止。
 * 同时把页面侧发现的跳转记入 LoginDiag，便于排查。
 */
public class LoginWebBridge {

    private static final String JS =
            "(function(){"
            + "try{"
            + "if(window.__weiLoginHooked){return 1;}"
            + "window.__weiLoginHooked=true;"
            + "window.__weiLoginDone=false;"
            + "var B=window.WeiLoginBridge||null;"
            + "function isTok(u){try{return String(u).indexOf('access_token=')>=0;}catch(e){return false;}}"
            + "function send(u){"
            + "try{"
            + "if(!u||window.__weiLoginDone){return;}"
            + "var s=String(u);"
            + "if(s.indexOf('access_token=')<0){return;}"
            + "window.__weiLoginDone=true;"
            + "if(B&&B.onUrl){B.onUrl(s);}"
            + "}catch(e){}"
            + "}"
            + "function note(t,d){try{if(B&&B.onEvent){B.onEvent(t,String(d).substring(0,300));}}catch(e){}}"
            // 只在参数确实是 token 时接管，其余原样交给浏览器
            + "try{"
            + "var _open=window.open;"
            + "window.open=function(u){"
            + "try{ note('JS-OPEN', u); if(u&&isTok(u)){ send(u); return null; } }catch(e){}"
            + "try{ return _open? _open.apply(window,arguments):null; }catch(e){}"
            + "return null;"
            + "};"
            + "}catch(e){}"
            + "function wrap(n){"
            + "try{"
            + "var f=history[n];"
            + "if(!f){return;}"
            + "history[n]=function(){var r=f.apply(this,arguments);try{note('JS-HIST',location.href);send(location.href);}catch(e){}return r;};"
            + "}catch(e){}"
            + "}"
            + "wrap('pushState');"
            + "wrap('replaceState');"
            + "try{window.addEventListener('hashchange',function(){note('JS-HASH',location.href);send(location.href);},false);}catch(e){}"
            + "var __t=setInterval(function(){"
            + "try{"
            + "if(window.__weiLoginDone){clearInterval(__t);return;}"
            + "send(location.href);"
            + "}catch(e){}"
            + "},800);"
            + "return 1;"
            + "}catch(e){return 0;}"
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

    public static void inject(final WebView view) {
        if (view == null) return;
        try {
            view.evaluateJavascript(JS, value -> {
                if (value == null || "0".equals(value)) {
                    LoginDiag.add("JS", "注入失败: " + value);
                }
            });
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
        LoginDiag.add("JS-TOKEN", "页面侧发现 token");
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
    public void onEvent(String tag, String detail) {
        LoginDiag.add(tag == null ? "JS" : tag, detail == null ? "" : detail);
    }

    @JavascriptInterface
    public void onReady() {
        LoginDiag.add("JS", "注入成功");
    }
}
