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
            // 全量上报：任何含 access_token 的 URL 都送回 Java，一个字段都不挑。
            // 三角洲的 MSDK 认的票据就在完整回包里，之前精简字段把它砍掉了。
            + "function send(u){"
            + "try{"
            + "if(!u||window.__weiLoginDone){return;}"
            + "var s=String(u);"
            + "if(s.indexOf('access_token=')<0){return;}"
            + "window.__weiLoginDone=true;"
            + "if(B&&B.onUrl){B.onUrl(s);}"
            + "}catch(e){}"
            + "}"
            + "function note(t,d){try{if(B&&B.onEvent){B.onEvent(t,String(d).substring(0,600));}}catch(e){}}"
            // 记录页面上的表单提交，看清登录链路每一跳
            + "try{"
            + "window.addEventListener('submit',function(e){"
            + "try{var f=e&&e.target;note('JS-SUBMIT', f&&f.action?f.action:'');}catch(x){}"
            + "},true);"
            + "}catch(e){}"
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
            + "var __last='';"
            + "var __t=setInterval(function(){"
            + "try{"
            + "var h=location.href;"
            // 非 token 页面的每一次变化也记下来。
            // 三角洲登录链路上会出现一个 v 开头的页面/参数，
            // 不记录跳转链路就永远不知道它叫什么、在第几跳出现。
            + "if(h&&h!==__last){"
            + "  __last=h;"
            + "  if(h.indexOf('access_token=')<0){note('JS-NAV',h);}"
            + "}"
            + "if(window.__weiLoginDone){clearInterval(__t);return;}"
            + "send(h);"
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
        /*
         * 原样记录下来。
         *
         * 上一版只打「页面侧发现 token」四个字，等于把唯一有用的东西藏了。
         * 三角洲的 MSDK 认哪个字段、要不要 v 开头的票据，全在完整 URL 里。
         * 这里把字段名逐个列出来（值只打长度，避免 token 明文进日志），
         * 下一份日志能直接读出回包到底带了哪些 key。
         */
        LoginDiag.add("JS-TOKEN", "页面侧发现 token 字段清单: " + keyList(u));
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

    /** 把 URL 的 query 拆成 key(len) 列表，key 名原样保留。 */
    private static String keyList(String url) {
        try {
            String q = url;
            int h = q.indexOf('#');
            if (h >= 0) {
                q = q.substring(h + 1);
            }
            int p = q.indexOf('?');
            if (p >= 0) {
                q = q.substring(p + 1);
            }
            StringBuilder sb = new StringBuilder();
            String[] parts = q.split("&");
            for (String kv : parts) {
                int eq = kv.indexOf('=');
                String k = eq > 0 ? kv.substring(0, eq) : kv;
                String v = eq > 0 ? kv.substring(eq + 1) : "";
                if (k.isEmpty()) {
                    continue;
                }
                sb.append(k).append('(').append(v.length()).append(") ");
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "解析失败: " + t;
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
