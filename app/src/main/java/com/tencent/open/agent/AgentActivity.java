package com.tencent.open.agent;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Toast;

import com.tencent.tim.R;
import com.tencent.tim.BaseActivity;
import com.tencent.tim.NormalWebViewClient;
import com.tencent.tim.HangUpWebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgentActivity extends BaseActivity {

    private static final String PREF_NAME = "MyPrefs";
    private static final String KEY_RAW_TEXT = "raw_text";
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "access_token=([^&]+)(?:&expires_in=([^&]+))?&openid=([^&]+)&pay_token=([^&]+)");

    private WebView webView;
    private com.tencent.tim.BaseLoginWebViewClient loginClient;
    private android.view.View barNext;
    private android.widget.TextView tvNextHint;
    private android.view.View captureTools;
    private ScrollView loginScroll;
    private EditText plainText;
    private Button pasteLoginButton;
    private Button manualLoginButton;

    private String currentMode;
    private Window mWindow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 设置全屏窗口
        requestWindowFeature(1);
        setRequestedOrientation(0);
        this.mWindow = getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            this.mWindow.getAttributes().layoutInDisplayCutoutMode = 1;
        }
        // 5894 = 全屏 + 沉浸式
        this.mWindow.getDecorView().setSystemUiVisibility(5894);
        this.mWindow.setSoftInputMode(3);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setNavigationBarColor(getResources().getColor(0x0106000b));
        }
        
        setContentView(R.layout.activity_agent);

        webView = findViewById(R.id.webview);
        loginScroll = findViewById(R.id.login_scroll);
        plainText = findViewById(R.id.plain_text);
        pasteLoginButton = findViewById(R.id.btn_paste);
        manualLoginButton = findViewById(R.id.btn_manual_login);

        try {
            com.tencent.tim.Ui.apply(this); try { android.view.View bgc = findViewById(R.id.bg_custom); if (bgc != null) bgc.setVisibility(android.view.View.GONE); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

        android.widget.Button pickToken = findViewById(R.id.btn_pick_token);
        if (pickToken != null) {
            pickToken.setOnClickListener(v -> showPickToken());
        }
        barNext = findViewById(R.id.bar_next);
        tvNextHint = findViewById(R.id.tv_next_hint);
        captureTools = findViewById(R.id.capture_tools);
        android.view.View nextBtn = findViewById(R.id.btn_next);
        if (nextBtn != null) nextBtn.setOnClickListener(v -> startNextAccount());
        android.view.View exitBtn = findViewById(R.id.btn_next_exit);
        if (exitBtn != null) exitBtn.setOnClickListener(v -> finish());
        android.view.View swapBtn = findViewById(R.id.btn_swap);
        if (swapBtn != null) swapBtn.setOnClickListener(v -> reloadForNewQr());

        android.view.View closeBtn = findViewById(R.id.btn_close);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> finish());
        }
        plainText.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable ed) {
                updateAutoGameHint(ed == null ? "" : ed.toString());
            }
        });


        Intent intent = getIntent();
        String modeFromIntent = null;
        if (intent != null) {
            modeFromIntent = intent.getStringExtra("mode");
        }

        // 如果 Intent 中有 mode，则直接使用
        if (modeFromIntent != null && !modeFromIntent.isEmpty()) {
            currentMode = modeFromIntent;
        } else {
            // 没有 mode，则从 SharedPreferences 读取保存的模式
            SharedPreferences prefs = getSharedPreferences("myPrefs", MODE_PRIVATE);
            String savedMode = prefs.getString("pd_key", "capture");
            currentMode = savedMode;

            // 如果是从游戏跳转，可以额外显示一个提示
            if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
                String url = intent.getData().toString();
                if (url.startsWith("auth:")) {
                    plainText.setText(url);
                    Toast.makeText(this, "检测到游戏登录请求", Toast.LENGTH_SHORT).show();
                }
            }
        }

        // 根据模式显示界面
        if ("login".equals(currentMode)) {
            setupLoginMode();
        } else if ("capture".equals(currentMode)) {
            setupCaptureMode();
        } else if ("hang_up".equals(currentMode)) {
            setupHangUpMode();
        } else {
            setupCaptureMode();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mWindow != null) {
            // 抓号/挂机模式保持全屏，上号模式恢复状态栏
            if ("login".equals(currentMode)) {
                // 上号模式：恢复状态栏
                mWindow.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            } else {
                // 抓号/挂机模式：保持全屏沉浸式
                mWindow.getDecorView().setSystemUiVisibility(5894);
            }
        }
    }

    private void setupLoginMode() {
        try {
            // 上号模式：显示状态栏，设置状态栏颜色
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getWindow().setStatusBarColor(Color.parseColor("#0A0F1A"));
                getWindow().setNavigationBarColor(Color.parseColor("#0A0F1A"));
            }
            
            // 恢复状态栏显示
            mWindow.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            
            webView.setVisibility(View.GONE);
            loginScroll.setVisibility(View.VISIBLE);
            showCaptureTools(false);
            loadRawDataFromPreferences();

            if (pasteLoginButton != null) {
                pasteLoginButton.setOnClickListener(v -> pasteAndLogin());
            }
            if (manualLoginButton != null) {
                manualLoginButton.setOnClickListener(v -> performLogin(plainText.getText().toString()));
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupCaptureMode() {
        try {
            // 抓号模式：全屏隐藏状态栏
            mWindow.getDecorView().setSystemUiVisibility(5894);
            
            webView.setVisibility(View.VISIBLE);
            loginScroll.setVisibility(View.GONE);

            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setLoadWithOverviewMode(true);
            webView.getSettings().setUseWideViewPort(true);
            webView.getSettings().setBuiltInZoomControls(true);
            webView.getSettings().setDisplayZoomControls(false);
            webView.getSettings().setDomStorageEnabled(true);
            
            loginClient = new NormalWebViewClient(this);
            webView.setWebViewClient(loginClient);
            showCaptureTools(true);

            // 进抓号页先清一次：否则上一个号还登着，QQ 会直接放行、连码都不弹
            clearLoginState();
            loadLoginPage();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "启动抓号模式失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupHangUpMode() {
        try {
            // 挂机模式：全屏隐藏状态栏
            mWindow.getDecorView().setSystemUiVisibility(5894);
            
            webView.setVisibility(View.VISIBLE);
            loginScroll.setVisibility(View.GONE);

            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setLoadWithOverviewMode(true);
            webView.getSettings().setUseWideViewPort(true);
            webView.getSettings().setBuiltInZoomControls(true);
            webView.getSettings().setDisplayZoomControls(false);
            webView.getSettings().setDomStorageEnabled(true);
            
            loginClient = new HangUpWebViewClient(this);
            webView.setWebViewClient(loginClient);
            showCaptureTools(true);

            // 挂机模式不清登录态：清了等于把正在挂的号踢下线
            loadLoginPage();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "启动挂机模式失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /** 加载登录页；拿不到合法 AppID 时明确提示，而不是加载一条必然报错的地址。 */
    private void loadLoginPage() {
        String url = getLoginUrl();
        if (url == null || url.isEmpty()) {
            com.tencent.tim.Diag.fail("Login", "AgentActivity.loadLoginPage",
                    "getLoginUrl 返回空；selected_game_name=" + getSelectedGameName()
                            + " saved_url=" + getSharedPreferences("myPrefs", MODE_PRIVATE).getString("selected_game_url", ""));
            Toast.makeText(this, "未获取到游戏 AppID，请返回重新选择游戏", Toast.LENGTH_LONG).show();
            onLoginAppidMissing("");
            return;
        }
        com.tencent.tim.Diag.step("Login", "AgentActivity.loadLoginPage", "加载登录页 url=" + url);
        webView.loadUrl(url);
    }

    /** 重新加载登录页（首轮自动修正无效后，由用户手动点「重试」触发）。 */
    public void retryLogin() {
        if (webView == null) return;
        String url = getLoginUrl();
        if (url == null || url.isEmpty()) {
            com.tencent.tim.Diag.fail("Login", "AgentActivity.retryLogin", "重试仍拿不到 AppID，退出登录页");
            Toast.makeText(this, "仍未获取到游戏 AppID，请返回重新选择游戏", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        webView.loadUrl(url);
    }

    /**
     * 登录页出现「无法获取appid」时给出可操作的提示，避免用户对着转圈干等。
     * 由 BaseLoginWebViewClient 的页面健康检查调用。
     */
    public void onLoginAppidMissing(final String detail) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    String game = getSelectedGameName();
                    String msg = "QQ 登录页没有拿到有效的游戏 AppID（client_id），所以一直停在加载中。"
                            + "\n\n当前游戏：" + game
                            + (detail == null || detail.isEmpty() ? "" : "\n\n" + detail)
                            + "\n\n点「重试」重新加载，或返回主界面重新选择游戏。";
                    new android.app.AlertDialog.Builder(AgentActivity.this)
                            .setTitle("无法获取 AppID")
                            .setMessage(msg)
                            .setPositiveButton("重试", (d, w) -> retryLogin())
                            .setNegativeButton("返回选择游戏", (d, w) -> finish())
                            .setCancelable(false)
                            .show();
                } catch (Throwable t) {
                    com.tencent.tim.Diag.fail("Login", "AgentActivity.onLoginAppidMissing", "弹窗失败", t);
                    Toast.makeText(AgentActivity.this, "无法获取 AppID，请返回重新选择游戏", Toast.LENGTH_LONG).show();
                    try {
                        finish();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /**
     * 取本次要加载的登录地址。
     *
     * 旧实现在没有保存 URL 时返回一条 pt_3rd_aid 与 client_id 都为空的地址，
     * 这种地址加载出来 QQ 只会显示「无法获取appid」并一直转圈，而 App 侧毫无感知。
     * 现在统一走 TokenStore.resolveLoginUrl：能修就修，修不出来返回空串，
     * 由调用方明确告诉用户「没拿到 AppID，请重新选择游戏」。
     */
    private String getLoginUrl() {
        SharedPreferences prefs = getSharedPreferences("myPrefs", MODE_PRIVATE);
        String savedUrl = prefs.getString("selected_game_url", "");
        String gameName = prefs.getString("selected_game_name", "");
        String url = com.tencent.tim.TokenStore.resolveLoginUrl(savedUrl, gameName);
        if (!url.isEmpty() && !url.equals(savedUrl)) {
            prefs.edit().putString("selected_game_url", url).apply();
            Toast.makeText(this, "已自动修正游戏 AppID", Toast.LENGTH_SHORT).show();
        }
        return url;
    }

    private void showPickToken() {
        String __g = ""; com.tencent.tim.TokenDialog.show(this, __g, e -> {
            String url2 = com.tencent.tim.TokenStore.rebuildUrl(e);
            plainText.setText(url2);
            updateAutoGameHint(url2);
            performLogin(url2);
        });
    }

    private void updateAutoGameHint(String url) {
        // 自动识别提示已移除
    }

    public void pasteAndLogin() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                String text = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
                if (!TextUtils.isEmpty(text)) {
                    plainText.setText(text);
                    performLogin(text);
                    Toast.makeText(this, "已从剪切板粘贴并登录", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "剪切板没有内容", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "粘贴失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String grab(String src, String regex) {
        Matcher m = Pattern.compile(regex).matcher(src);
        return m.find() ? m.group(1) : "";
    }

    public void performLogin(String url) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "数据为空", Toast.LENGTH_SHORT).show();
            return;
        }

        String accessToken = grab(url, "access_token=([^&\\s#]+)");
        String expiresIn = grab(url, "expires_in=([^&\\s#]+)");
        String openId = grab(url, "openid=([^&\\s#]+)");
        String payToken = grab(url, "pay_token=([^&\\s#]+)");
        if (!accessToken.isEmpty()) {

            try {
                JSONObject json = new JSONObject();
                json.put("access_token", accessToken);
                json.put("openid", openId);
                json.put("pay_token", payToken);
                json.put("expires_in", expiresIn == null || expiresIn.isEmpty() ? "7776000" : expiresIn);
                json.put("ret", "0");
                json.put("pf", "desktop_m_qq-10000144-android-2002-");
                json.put("page_type", "1");

                String saved = normalizeAuthUrl(url);
                saveRawDataToPreferences(saved);

                if (!"login".equals(currentMode)) {
                    // 登录页的 AppID 和回调里带回来的不一致，说明中途换了游戏或复用了旧页面。
                    // 记一笔并按实际加载的登录页为准，避免 token 记到别的游戏名下。
                    String callbackAppid = appidFrom(url);
                    String pageAppid = com.tencent.tim.TokenStore.appidOfUrl(getLoginUrl());
                    if (!callbackAppid.isEmpty() && !pageAppid.isEmpty()
                            && !callbackAppid.equals(pageAppid)) {
                        com.tencent.tim.Diag.fail("Login", "appidMismatch",
                                "登录页 appid=" + pageAppid + " 回调 appid=" + callbackAppid
                                        + " game=" + getSelectedGameName());
                    }
                    String finalAppid = callbackAppid.isEmpty() ? pageAppid : callbackAppid;
                    saveAccountToFile(saved, finalAppid, currentMode != null ? currentMode : "抓号模式");
                } else {
                    Toast.makeText(this, "登录成功！", Toast.LENGTH_LONG).show();
                }

                Intent resultIntent = new Intent();
                resultIntent.putExtra("key_response", json.toString());
                setResult(RESULT_OK, resultIntent);

                if ("login".equals(currentMode)) {
                    new android.os.Handler().postDelayed(() -> finish(), 1200);
                } else {
                    // 抓号模式不自动退出：留在这里点一下就能换下一个号，
                    // 省掉每次重开页面（重开要重新拉 HTML + 重新生成二维码）。
                    showNextBar();
                    // 抓号模式：抓到就立刻把登录态清掉，这样即使直接退出 App，
                    // 下次进来也不会带着这个号的会话自动放行。挂机模式不清，
                    // 清了会把正在挂的号踢下线。
                    if ("capture".equals(currentMode)) {
                        new android.os.Handler().postDelayed(this::clearLoginState, 600);
                    }
                }

            } catch (JSONException e) {
                e.printStackTrace();
                Toast.makeText(this, "JSON处理错误", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "内容格式错误", Toast.LENGTH_SHORT).show();
        }
    }

    /** 抓号模式下常驻的小工具条（换号）。 */
    private void showCaptureTools(boolean show) {
        try {
            if (captureTools != null) {
                captureTools.setVisibility(show ? View.VISIBLE : View.GONE);
            }
            if (!show && barNext != null) barNext.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
    }

    /** 抓到号之后的浮条：「继续抓下一个」/「退出」。 */
    private void showNextBar() {
        try {
            if (barNext != null) barNext.setVisibility(View.VISIBLE);
            if (tvNextHint != null) tvNextHint.setText("已抓到 token · 登录态已清除");
        } catch (Throwable ignored) {
        }
    }

    /** 换一个号：先把客户端的一次性状态复位，再重新拉一次登录页（会生成新的二维码）。 */
    private void startNextAccount() {
        try {
            if (loginClient != null) loginClient.resetForNext();
            if (barNext != null) barNext.setVisibility(View.GONE);
            reloadForNewQr();
        } catch (Throwable t) {
            com.tencent.tim.Diag.fail("Login", "startNextAccount", "换号失败", t);
        }
    }

    /**
     * 重新拉登录页。
     *
     * 注意这里不能只刷新二维码图片：QQ 的二维码和页面里的轮询 sig 是配对的，
     * 单独换 &lt;img&gt; 的 src 会得到一张「扫了没反应」的码。所以整页重载，
     * 但静态资源仍然走 WebView 缓存，只有 xlogin 和二维码走网络，比冷启动快得多。
     */
    private void reloadForNewQr() {
        try {
            String url = getLoginUrl();
            if (url == null || url.isEmpty()) {
                Toast.makeText(this, "拿不到游戏 AppID，无法换号", Toast.LENGTH_SHORT).show();
                return;
            }
            // 每次重载都复位一次：漏掉的话 tokenGot 还是 true，看门狗不会再计时
            if (loginClient != null) loginClient.resetForNext();
            clearLoginState();
            com.tencent.tim.Diag.step("Login", "reloadForNewQr", "已清登录态，重新拉取登录页（生成新二维码）");
            webView.loadUrl(url);
            Toast.makeText(this, "已清登录态，请扫码", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            com.tencent.tim.Diag.fail("Login", "reloadForNewQr", "换号重载失败", t);
        }
    }

    /**
     * 清掉 WebView 里的 QQ 登录态。
     *
     * 不清的话，抓完 A 号再点「换号」，QQ 会直接用上一个号的 cookie 自动放行，
     * 拿回来的还是 A 号——看着像"换号没生效"，其实是没让它重新扫码。
     * 只清 cookie，不动页面缓存，所以静态资源照样走缓存、二维码照样很快。
     */
    private void clearLoginState() {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            com.tencent.tim.Diag.step("Login", "clearLoginState", "已清除 Cookie");
        } catch (Throwable t) {
            com.tencent.tim.Diag.fail("Login", "clearLoginState", "清除 Cookie 失败", t);
        }
    }

    private String appidFrom(String url) {
        String a = com.tencent.tim.TokenStore.appidOfUrl(url);
        if (a == null || a.isEmpty()) {
            String su = getSharedPreferences("myPrefs", MODE_PRIVATE).getString("selected_game_url", "");
            a = com.tencent.tim.TokenStore.appidOfUrl(su);
        }
        return (a == null || a.isEmpty()) ? "未知" : a;
    }

    private String normalizeAuthUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        if (url.startsWith("auth://tauth.qq.com") && url.contains("access_token=")) return url;
        String at = grab(url, "access_token=([^&#]+)");
        if (at.isEmpty()) return url;
        String openid = grab(url, "openid=([^&#]+)");
        String pay = grab(url, "pay_token=([^&#]+)");
        String exp = grab(url, "expires_in=([^&#]+)");
        if (exp.isEmpty()) exp = "7776000";
        String authTime = grab(url, "auth_time=([^&#]+)");
        String pfkey = grab(url, "key=([^&#]+)");
        StringBuilder sb = new StringBuilder("auth://tauth.qq.com/?#");
        sb.append("access_token=").append(at);
        sb.append("&expires_in=").append(exp);
        sb.append("&openid=").append(openid);
        sb.append("&pay_token=").append(pay);
        sb.append("&ret=0");
        sb.append("&pf=desktop_m_qq-10000144-android-2002-");
        if (!pfkey.isEmpty()) sb.append("&pfkey=").append(pfkey);
        if (!authTime.isEmpty()) sb.append("&auth_time=").append(authTime);
        sb.append("&page_type=0");
        return sb.toString();
    }

    private String withAppid(String url) {
        if (url == null || url.contains("appid=") || url.contains("client_id=")) return url;
        String a = "";
        try { a = com.tencent.tim.TokenStore.appidOfGame(getSelectedGameName()); } catch (Exception ignored) {}
        if (a == null || a.isEmpty()) a = "1104466820";
        return url + (url.indexOf("?") >= 0 ? "&" : "?") + "appid=" + a;
    }
    private void saveRawDataToPreferences(String data) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_RAW_TEXT, data)
                .apply();
    }

    private void loadRawDataFromPreferences() {
        String data = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getString(KEY_RAW_TEXT, "");
        if (data != null && !data.isEmpty()) {
            plainText.setText(data);
        } else {
            plainText.setText("");
        }
    }

    public String getSelectedGameName() {
        return getSharedPreferences("myPrefs", MODE_PRIVATE)
                .getString("selected_game_name", "未知游戏");
    }

    public void saveAccountToFile(String url, String appid, String mode) {
        new Thread(() -> {
            try {
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                String gameName = getSelectedGameName();
                final String aidShown = (appid == null || appid.isEmpty()) ? "未知" : appid;
                String line = com.tencent.tim.TokenStore.joinLine("", time, aidShown, url, mode);
                final boolean ok = com.tencent.tim.TokenStore.saveGameEntry(gameName, line);
                final String g = gameName;
                runOnUiThread(() -> {
                    if (ok) {
                        Toast.makeText(AgentActivity.this, "已保存 " + g + " appid=" + aidShown + " -> " + com.tencent.tim.TokenStore.gameFile(g).getAbsolutePath(), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(AgentActivity.this, "保存失败: " + com.tencent.tim.TokenStore.lastError, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private boolean isTokenExists(File file, String token) {
        try {
            Scanner scanner = new Scanner(file);
            while (scanner.hasNextLine()) {
                if (scanner.nextLine().contains(token)) {
                    scanner.close();
                    return true;
                }
            }
            scanner.close();
        } catch (Exception e) {
            // 文件不存在
        }
        return false;
    }

    private String getAccessTokenFromUrl(String line) {
        Matcher matcher = Pattern.compile("access_token=([^&\\s]+)").matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }
}