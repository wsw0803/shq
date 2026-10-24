package com.tencent.open.agent;

import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Toast;

import com.tencent.tim.BaseActivity;
import com.tencent.tim.HangUpWebViewClient;
import com.tencent.tim.LoginDiag;
import com.tencent.tim.NormalWebViewClient;
import com.tencent.tim.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上号 / 抓号 / 挂机 三种模式的界面。
 *
 * 本次改动：接入 LoginDiag，让三种模式都能导出诊断。
 * 上号模式不走 WebView，所以诊断点埋在 AgentActivity 里：
 *   - 进入界面时记录 模式 / 游戏 / appid / 输入内容是否含 token
 *   - performLogin 记录各字段长度（不记录明文）
 *   - 记录解析出的 appid 与「已选游戏登记的 appid」是否一致
 *     —— 这是排查「其他游戏能登、某个游戏登不上」的关键
 *   - 保存到文件的结果也记录
 *
 * 业务逻辑与原实现保持一致，只加了埋点。
 */
public class AgentActivity extends BaseActivity {

    private static final String PREF_NAME = "MyPrefs";
    private static final String KEY_RAW_TEXT = "raw_text";
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "access_token=([^&]+)(?:&expires_in=([^&]+))?&openid=([^&]+)&pay_token=([^&]+)");

    private WebView webView;
    private ScrollView loginScroll;
    private EditText plainText;
    private Button pasteLoginButton;
    private Button manualLoginButton;
    private String currentMode;
    private Window mWindow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
            com.tencent.tim.Ui.apply(this);
            try {
                android.view.View bgc = findViewById(R.id.bg_custom);
                if (bgc != null) bgc.setVisibility(android.view.View.GONE);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }

        android.widget.Button pickToken = findViewById(R.id.btn_pick_token);
        if (pickToken != null) {
            pickToken.setOnClickListener(v -> showPickToken());
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

        if (modeFromIntent != null && !modeFromIntent.isEmpty()) {
            currentMode = modeFromIntent;
        } else {
            SharedPreferences prefs = getSharedPreferences("myPrefs", MODE_PRIVATE);
            currentMode = prefs.getString("pd_key", "capture");
            if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
                String url = intent.getData().toString();
                if (url.startsWith("auth:")) {
                    plainText.setText(url);
                    Toast.makeText(this, "检测到游戏登录请求", Toast.LENGTH_SHORT).show();
                }
            }
        }

        // 诊断从这里开始，三种模式都覆盖
        LoginDiag.resetButtonFlag();
        LoginDiag.begin();
        LoginDiag.add("AGENT", "mode=" + currentMode + " game=" + getSelectedGameName());

        if ("login".equals(currentMode)) {
            setupLoginMode();
        } else if ("capture".equals(currentMode)) {
            setupCaptureMode();
        } else if ("hang_up".equals(currentMode)) {
            setupHangUpMode();
        } else {
            setupCaptureMode();
        }

        // 上号模式也挂诊断按钮。WebView 模式已由 BaseLoginWebViewClient 挂过，
        // 内部按 Activity 去重，不会重复。
        try {
            LoginDiag.attachButton(this, getSelectedGameName());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mWindow != null) {
            if ("login".equals(currentMode)) {
                mWindow.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            } else {
                mWindow.getDecorView().setSystemUiVisibility(5894);
            }
        }
    }

    private void setupLoginMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getWindow().setStatusBarColor(Color.parseColor("#0A0F1A"));
                getWindow().setNavigationBarColor(Color.parseColor("#0A0F1A"));
            }
            mWindow.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

            webView.setVisibility(View.GONE);
            loginScroll.setVisibility(View.VISIBLE);
            loadRawDataFromPreferences();

            String input = plainText.getText() == null ? "" : plainText.getText().toString();
            LoginDiag.add("LOGIN", "game=" + getSelectedGameName()
                    + " 已选appid=" + getSelectedGameAppid()
                    + " 输入长度=" + input.length()
                    + " 含token=" + input.contains("access_token="));

            if (pasteLoginButton != null) {
                pasteLoginButton.setOnClickListener(v -> pasteAndLogin());
            }
            if (manualLoginButton != null) {
                manualLoginButton.setOnClickListener(v -> {
                    LoginDiag.add("LOGIN", "点击执行登录");
                    performLogin(plainText.getText().toString());
                });
            }
        } catch (Exception e) {
            LoginDiag.add("ERR", "setupLoginMode: " + e);
            e.printStackTrace();
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupCaptureMode() {
        try {
            mWindow.getDecorView().setSystemUiVisibility(5894);
            webView.setVisibility(View.VISIBLE);
            loginScroll.setVisibility(View.GONE);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setLoadWithOverviewMode(true);
            webView.getSettings().setUseWideViewPort(true);
            webView.getSettings().setBuiltInZoomControls(true);
            webView.getSettings().setDisplayZoomControls(false);
            webView.getSettings().setDomStorageEnabled(true);
            webView.setWebViewClient(new NormalWebViewClient(this));

            String url = getLoginUrl();
            LoginDiag.add("CAPTURE", "url appid=" + com.tencent.tim.TokenStore.appidOfUrl(url));
            if (url != null && !url.isEmpty()) {
                webView.loadUrl(url);
            } else {
                Toast.makeText(this, "登录URL为空", Toast.LENGTH_SHORT).show();
                finish();
            }
        } catch (Exception e) {
            LoginDiag.add("ERR", "setupCaptureMode: " + e);
            e.printStackTrace();
            Toast.makeText(this, "启动抓号模式失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupHangUpMode() {
        try {
            mWindow.getDecorView().setSystemUiVisibility(5894);
            webView.setVisibility(View.VISIBLE);
            loginScroll.setVisibility(View.GONE);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setLoadWithOverviewMode(true);
            webView.getSettings().setUseWideViewPort(true);
            webView.getSettings().setBuiltInZoomControls(true);
            webView.getSettings().setDisplayZoomControls(false);
            webView.getSettings().setDomStorageEnabled(true);
            webView.setWebViewClient(new HangUpWebViewClient(this));

            String url = getLoginUrl();
            LoginDiag.add("HANGUP", "url appid=" + com.tencent.tim.TokenStore.appidOfUrl(url));
            if (url != null && !url.isEmpty()) {
                webView.loadUrl(url);
            } else {
                Toast.makeText(this, "登录URL为空", Toast.LENGTH_SHORT).show();
                finish();
            }
        } catch (Exception e) {
            LoginDiag.add("ERR", "setupHangUpMode: " + e);
            e.printStackTrace();
            Toast.makeText(this, "启动挂机模式失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private String getLoginUrl() {
        SharedPreferences prefs = getSharedPreferences("myPrefs", MODE_PRIVATE);
        String savedUrl = prefs.getString("selected_game_url", "");
        if (!savedUrl.isEmpty()) {
            return savedUrl;
        }
        return "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?pt_enable_pwd=1&appid=716027609&pt_3rd_aid=&daid=381&pt_skey_valid=0&style=35&force_qr=1&autorefresh=1&s_url=http://connect.qq.com&refer_cgi=m_authorize&ucheck=1&fall_to_wv=1&status_os=0&redirect_uri=auth://tauth.qq.com/&client_id=&pf=openmobile_web&response_type=token&scope=all&sdkp=a&sdkv=3.5.14&sign=0&status_machine=0&switch=1&time=0&loginfrom=add&h5sig=0&loginty=6";
    }

    private void showPickToken() {
        LoginDiag.add("LOGIN", "打开 Token 选择器");
        String g = "";
        com.tencent.tim.TokenDialog.show(this, g, e -> {
            String url2 = com.tencent.tim.TokenStore.rebuildUrl(e);
            LoginDiag.add("LOGIN", "选中 Token game=" + e.game + " appid=" + e.appid);
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
                    LoginDiag.add("LOGIN", "粘贴登录，长度=" + text.length()
                            + " 含token=" + text.contains("access_token="));
                    plainText.setText(text);
                    performLogin(text);
                    Toast.makeText(this, "已从剪切板粘贴并登录", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "剪切板没有内容", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            LoginDiag.add("ERR", "pasteAndLogin: " + e);
            Toast.makeText(this, "粘贴失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String grab(String src, String regex) {
        Matcher m = Pattern.compile(regex).matcher(src);
        return m.find() ? m.group(1) : "";
    }

    public void performLogin(String url) {
        if (url == null || url.isEmpty()) {
            LoginDiag.add("ERR", "performLogin: 数据为空");
            Toast.makeText(this, "数据为空", Toast.LENGTH_SHORT).show();
            return;
        }

        String accessToken = grab(url, "access_token=([^&\\s#]+)");
        String expiresIn = grab(url, "expires_in=([^&\\s#]+)");
        String openId = grab(url, "openid=([^&\\s#]+)");
        String payToken = grab(url, "pay_token=([^&\\s#]+)");

        // 关键诊断：各字段只记录长度，脱敏后可安全展示
        LoginDiag.add("PARSE", "access_token=" + (accessToken.isEmpty() ? "空" : ("len" + accessToken.length()))
                + " openid=" + (openId.isEmpty() ? "空" : ("len" + openId.length()))
                + " pay_token=" + (payToken.isEmpty() ? "空" : ("len" + payToken.length()))
                + " expires_in=" + (expiresIn.isEmpty() ? "空" : expiresIn));

        if (accessToken.isEmpty()) {
            LoginDiag.add("ERR", "performLogin: access_token 为空，内容格式不符");
            Toast.makeText(this, "内容格式错误", Toast.LENGTH_SHORT).show();
            return;
        }

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

            String gameName = getSelectedGameName();
            String appid = appidFrom(url);
            String chosenAppid = getSelectedGameAppid();

            // appid 一致性检查：这是「其他游戏能登、某个游戏登不上」的首要怀疑点
            LoginDiag.add("RESULT", "game=" + gameName
                    + " token里appid=" + appid
                    + " 已选游戏appid=" + chosenAppid
                    + " 一致=" + appid.equals(chosenAppid)
                    + " mode=" + currentMode);

            if (!"login".equals(currentMode)) {
                saveAccountToFile(saved, appid, currentMode != null ? currentMode : "抓号模式");
            } else {
                Toast.makeText(this, "登录成功！", Toast.LENGTH_LONG).show();
            }

            Intent resultIntent = new Intent();
            resultIntent.putExtra("key_response", json.toString());
            setResult(RESULT_OK, resultIntent);

            new android.os.Handler().postDelayed(() -> finish(), 2000);
        } catch (JSONException e) {
            LoginDiag.add("ERR", "JSON: " + e);
            e.printStackTrace();
            Toast.makeText(this, "JSON处理错误", Toast.LENGTH_SHORT).show();
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

    /** 当前选中游戏登记的 appid，用于与 token 里的 appid 比对。 */
    private String getSelectedGameAppid() {
        try {
            String name = getSelectedGameName();
            String a = com.tencent.tim.TokenStore.appidOfGame(name);
            if (a == null || a.isEmpty()) {
                String su = getSharedPreferences("myPrefs", MODE_PRIVATE).getString("selected_game_url", "");
                a = com.tencent.tim.TokenStore.appidOfUrl(su);
            }
            return (a == null || a.isEmpty()) ? "未知" : a;
        } catch (Throwable t) {
            return "未知";
        }
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
        try {
            a = com.tencent.tim.TokenStore.appidOfGame(getSelectedGameName());
        } catch (Exception ignored) {
        }
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
                LoginDiag.add("SAVE", "game=" + gameName + " appid=" + aidShown
                        + " ok=" + ok + (ok ? "" : (" err=" + com.tencent.tim.TokenStore.lastError)));
                final String g = gameName;
                runOnUiThread(() -> {
                    if (ok) {
                        Toast.makeText(AgentActivity.this,
                                "已保存 " + g + " appid=" + aidShown + " -> "
                                        + com.tencent.tim.TokenStore.gameFile(g).getAbsolutePath(),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(AgentActivity.this,
                                "保存失败: " + com.tencent.tim.TokenStore.lastError,
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                LoginDiag.add("ERR", "saveAccountToFile: " + e);
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
