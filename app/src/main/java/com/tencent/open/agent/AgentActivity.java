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

public class AgentActivity extends Activity {

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
            
            webView.setWebViewClient(new NormalWebViewClient(this));

            String url = getLoginUrl();
            if (url != null && !url.isEmpty()) {
                webView.loadUrl(url);
            } else {
                Toast.makeText(this, "登录URL为空", Toast.LENGTH_SHORT).show();
                finish();
            }
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
            
            webView.setWebViewClient(new HangUpWebViewClient(this));

            String url = getLoginUrl();
            if (url != null && !url.isEmpty()) {
                webView.loadUrl(url);
            } else {
                Toast.makeText(this, "登录URL为空", Toast.LENGTH_SHORT).show();
                finish();
            }
        } catch (Exception e) {
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
        return "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?pt_enable_pwd=1&appid=716027609&pt_3rd_aid=1104466820&daid=381&pt_skey_valid=0&style=35&force_qr=1&autorefresh=1&s_url=http://connect.qq.com&refer_cgi=m_authorize&ucheck=1&fall_to_wv=1&status_os=0&redirect_uri=auth://tauth.qq.com/&client_id=1104466820&pf=openmobile_web&response_type=token&scope=all&sdkp=a&sdkv=3.5.14&sign=0&status_machine=0&switch=1&time=0&loginfrom=add&h5sig=0&loginty=6";
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

    public void performLogin(String url) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "数据为空", Toast.LENGTH_SHORT).show();
            return;
        }
        
        Matcher matcher = TOKEN_PATTERN.matcher(url);
        if (matcher.find()) {
            String accessToken = matcher.group(1);
            String expiresIn = matcher.group(2);
            String openId = matcher.group(3);
            String payToken = matcher.group(4);

            try {
                JSONObject json = new JSONObject();
                json.put("access_token", accessToken);
                json.put("openid", openId);
                json.put("pay_token", payToken);
                json.put("expires_in", expiresIn != null ? expiresIn : "7776000");
                json.put("ret", "0");
                json.put("pf", "desktop_m_qq-10000144-android-2002-");
                json.put("page_type", "1");

                saveRawDataToPreferences(url);
                
                // 上号模式
                if (!"login".equals(currentMode)) {
                    saveAccountToFile(url, currentMode != null ? currentMode : "抓号模式");
                } else {
                    Toast.makeText(this, "登录成功！", Toast.LENGTH_LONG).show();
                }

                Intent resultIntent = new Intent();
                resultIntent.putExtra("key_response", json.toString());
                setResult(RESULT_OK, resultIntent);
                
                new android.os.Handler().postDelayed(() -> finish(), 2000);

            } catch (JSONException e) {
                e.printStackTrace();
                Toast.makeText(this, "JSON处理错误", Toast.LENGTH_SHORT).show();
            }
        } else {
            if (Pattern.compile("access_token=([^&]+)").matcher(url).find()) {
                Toast.makeText(this, "格式不完整，请检查数据", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "内容格式错误", Toast.LENGTH_SHORT).show();
            }
        }
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

    public void saveAccountToFile(String url, String mode) {
        new Thread(() -> {
            try {
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                String gameName = getSelectedGameName();
                String line = time + " | " + gameName + " | " + url + " | " + mode + "\n";
                
                String dirPath = Environment.getExternalStorageDirectory().getPath() + "/-极光";
                String filePath = dirPath + "/数据.txt";
                
                File dir = new File(dirPath);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                
                File file = new File(filePath);
                FileWriter writer = new FileWriter(file, true);
                
                String token = getAccessTokenFromUrl(line);
                if (token != null && isTokenExists(file, token)) {
                    writer.close();
                    return;
                }
                
                writer.append(line);
                writer.flush();
                writer.close();
                
            } catch (IOException e) {
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