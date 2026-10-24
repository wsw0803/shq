package com.tencent.tim;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import com.tencent.open.agent.AgentActivity;

/**
 * 挂机模式 WebView 客户端。
 *
 * 继承 BaseLoginWebViewClient 获得 token 多路捕获能力；
 * 原来 refreshPage 每次都 postDelayed 一个 Runnable 且不取消，
 * 多次登录会叠加多个定时刷新，导致页面反复重载、看起来像卡住。
 * 这里改为先移除旧任务再投递新任务。
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
        if (low.startsWith("auth:")) {
            toast("登录回调缺少 access_token: " + url);
            return true;
        }
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
