package com.tencent.tim;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tencent.open.agent.AgentActivity;

/**
 * 接管登录页的弹窗与 window.open。
 *
 * QQ 登录页常用 window.open 弹小窗（扫码确认、二次验证、协议页）。
 * 只在 onCreateWindow 里 new 一个 WebView 交给 transport 是不够的：
 * 不在视图树里的 WebView 既不渲染也收不到触摸，表现就是点登录毫无反应、
 * 弹窗永远不出现，最终抓不到 token。
 *
 * 这里把弹窗 WebView 挂进一个覆盖层（带右上角关闭按钮）真正显示出来，
 * 关闭时销毁，避免残留叠层。
 */
public class LoginChromeClient extends WebChromeClient {

    private final AgentActivity activity;
    private final BaseLoginWebViewClient client;

    /** 当前弹窗覆盖层；同一时间只保留一层。 */
    private FrameLayout overlay;
    private WebView popup;

    public LoginChromeClient(AgentActivity activity, BaseLoginWebViewClient client) {
        this.activity = activity;
        this.client = client;
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                  android.os.Message resultMsg) {
        try {
            final WebView child = new WebView(activity);
            WebSettings s = child.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setSupportMultipleWindows(true);
            s.setJavaScriptCanOpenWindowsAutomatically(true);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            try {
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            } catch (Throwable ignored) {
            }
            child.setWebViewClient(client);
            child.setWebChromeClient(this);

            showPopup(child);

            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(child);
            resultMsg.sendToTarget();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把弹窗 WebView 真正显示出来：覆盖层 + 右上角关闭。 */
    private void showPopup(final WebView child) {
        try {
            closePopup();
            ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
            if (root == null) return;

            FrameLayout layer = new FrameLayout(activity);
            layer.setBackgroundColor(Color.parseColor("#F00A0F1A"));
            layer.setClickable(true);

            // 顶部条：提示 + 关闭按钮
            LinearLayout bar = new LinearLayout(activity);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(12), dp(10), dp(12), dp(10));

            TextView tip = new TextView(activity);
            tip.setText("登录弹窗");
            tip.setTextColor(Color.WHITE);
            tip.setTextSize(14f);
            bar.addView(tip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView close = new TextView(activity);
            close.setText("关闭 ✕");
            close.setTextColor(Color.WHITE);
            close.setTextSize(14f);
            close.setPadding(dp(10), dp(6), dp(10), dp(6));
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closePopup();
                }
            });
            bar.addView(close);

            FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            barLp.gravity = Gravity.TOP;
            layer.addView(bar, barLp);

            FrameLayout.LayoutParams webLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            bar.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            webLp.topMargin = Math.max(dp(44), bar.getMeasuredHeight());
            layer.addView(child, webLp);

            root.addView(layer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            overlay = layer;
            popup = child;
        } catch (Throwable ignored) {
        }
    }

    /** 关掉弹窗并销毁 WebView，避免叠层与内存泄漏。 */
    public void closePopup() {
        try {
            if (overlay != null && overlay.getParent() != null) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (popup != null) {
                popup.setWebChromeClient(null);
                popup.setWebViewClient(null);
                popup.stopLoading();
                popup.destroy();
            }
        } catch (Throwable ignored) {
        }
        overlay = null;
        popup = null;
    }

    private int dp(int v) {
        try {
            return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
        } catch (Throwable t) {
            return v;
        }
    }

    @Override
    public void onCloseWindow(WebView window) {
        super.onCloseWindow(window);
        closePopup();
    }

    @Override
    public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
        // 部分登录页用 alert 做跳转提示，不能让它静默吞掉事件
        try {
            result.confirm();
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
        try {
            result.confirm();
        } catch (Throwable ignored) {
        }
        return true;
    }
}
