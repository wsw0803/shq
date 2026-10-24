package com.tencent.tim;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import com.tencent.open.agent.AgentActivity;

/**
 * 挂机模式 WebView 客户端。
 *
 * 修复两处：
 *
 * 1) 与 NormalWebViewClient 同样的协议处理问题：
 *    原来对所有非 http(s) 一律 return true，把 about:/data:/intent:// 等
 *    全部静默吞掉。现在按协议分类，其他 scheme 交给系统 Intent。
 *    另外子框架跳转不再拦截，避免误伤 iframe。
 *
 * 2) refreshPage 每次 postDelayed 都不取消，多次登录会叠加多个定时刷新，
 *    页面反复重载。现在先移除旧任务再投递。
 */
public class HangUpWebViewClient extends BaseLoginWebViewClient {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    public HangUpWebViewClient(AgentActivity activity) {
        super(activity);
    }

    private boolean handle(WebView view, String url) {
        if (url == null) return false;
        prepare(view);
        if (deliver(view, url)) return true;

        String low = url.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")) return false;

        if (low.startsWith("about:") || low.startsWith("data:") || low.startsWith("blob:")
                || low.startsWith("javascript:")) {
            return false;
        }

        if (low.startsWith("auth:") || low.startsWith("tencent:") || low.startsWith("qq:")) {
            toast("登录回调缺少 access_token: " + url);
            return true;
        }

        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(it);
            return true;
        } catch (Throwable t) {
            toast("无法处理该链接: " + url);
            return true;
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) return false;
        if (!request.isForMainFrame()) return false;
        return handle(view, request.getUrl().toString());
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handle(view, url);
    }

    @Override
    protected void onTokenDelivered(WebView view) {
        refreshPage(view);
    }

    private void refreshPage(final WebView webView) {
        if (webView == null) return;
        if (refreshRunnable != null) {
            handler.removeCallbacks(refreshRunnable);
            refreshRunnable = null;
        }
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    String url = activity.getSharedPreferences("myPrefs", android.content.Context.MODE_PRIVATE)
                            .getString("selected_game_url", "");
                    if (url == null || url.isEmpty()) {
                        url = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?pt_enable_pwd=1&appid=716027609&pt_3rd_aid=1104466820&daid=381&pt_skey_valid=0&style=35&force_qr=1&autorefresh=1&s_url=http://connect.qq.com&refer_cgi=m_authorize&ucheck=1&fall_to_wv=1&status_os=0&redirect_uri=auth://tauth.qq.com/&client_id=1104466820&pf=openmobile_web&response_type=token&scope=all&sdkp=a&sdkv=3.5.14&sign=0&status_machine=0&switch=1&time=0&loginfrom=add&h5sig=0&loginty=6";
                    }
                    webView.loadUrl(url);
                } catch (Throwable ignored) {
                }
            }
        };
        handler.postDelayed(refreshRunnable, 3000);
    }
}
