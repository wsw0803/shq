package com.tencent.tim;

import android.graphics.Bitmap;
import android.os.Handler;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.tencent.open.agent.AgentActivity;

public class HangUpWebViewClient extends WebViewClient {

    private final AgentActivity activity;
    private final Handler handler = new Handler();
    private Runnable refreshRunnable;

    public HangUpWebViewClient(AgentActivity activity) {
        this.activity = activity;
    }

    private boolean handle(WebView view, String url) {
        if (url == null) return false;
        String low = url.toLowerCase();
        if (low.startsWith("auth:") || low.contains("access_token=")) {
            activity.performLogin(url);
            refreshPage(view);
            return true;
        }
        return !(low.startsWith("http://") || low.startsWith("https://"));
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
        if (url != null && url.toLowerCase().contains("access_token=")) handle(view, url);
    }

    private void refreshPage(WebView webView) {
        refreshRunnable = () -> {
            String url = activity.getSharedPreferences("myPrefs", android.content.Context.MODE_PRIVATE)
                    .getString("selected_game_url", "");
            if (url.isEmpty()) {
                url = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?pt_enable_pwd=1&appid=716027609&pt_3rd_aid=1104466820&daid=381&pt_skey_valid=0&style=35&force_qr=1&autorefresh=1&s_url=http://connect.qq.com&refer_cgi=m_authorize&ucheck=1&fall_to_wv=1&status_os=0&redirect_uri=auth://tauth.qq.com/&client_id=1104466820&pf=openmobile_web&response_type=token&scope=all&sdkp=a&sdkv=3.5.14&sign=0&status_machine=0&switch=1&time=0&loginfrom=add&h5sig=0&loginty=6";
            }
            webView.loadUrl(url);
        };
        handler.postDelayed(refreshRunnable, 3000);
    }
}
