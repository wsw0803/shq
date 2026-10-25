package com.tencent.open.agent;

import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Toast;

import com.tencent.tim.BaseActivity;
import com.tencent.tim.GameLaunch;
import com.tencent.tim.HangUpWebViewClient;
import com.tencent.tim.LoginDiag;
import com.tencent.tim.NormalWebViewClient;
import com.tencent.tim.R;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上号 / 抓号 / 挂机 三种模式的界面。
 *
 * 上号模式（2026-10-25 第七次修订）。
 *
 * 上一版已经不再启动游戏内部界面，黑屏消失，但留下一个观测盲区：
 * setResult 之后我们 finish()，**没有任何代码去确认游戏有没有回来**。
 * 日志到「只走 setResult」就断了，如果 AssistActivity 没把结果交给游戏主逻辑，
 * 用户会停在空白桌面 —— 外观上和黑屏一样。
 *
 * 现在补一个看门狗：finish 之后继续轮询前台最多 3 秒，
 *   - 游戏回来了 → 记「返回后前台: dfm/...GameActivity ✓」；
 *   - 游戏没回来 → 记日志并主动 bringToFront，不会卡在桌面。
 *
 * 同时保留上一版成果：有真实回传通道时只 setResult，不碰游戏内部界面。
 */
public class AgentActivity extends BaseActivity {

    private static final String PREF_NAME = "MyPrefs";
    private static final String KEY_RAW_TEXT = "raw_text";

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 收尾看门狗用：Activity 已 finish 后不能再拿它当 Context。 */
    private static Context appCtx;

    private WebView webView;
    private ScrollView loginScroll;
    private EditText plainText;
    private Button pasteLoginButton;
    private Button manualLoginButton;

    private String currentMode;
    private Window mWindow;
    private String callerPackage = "";
    private boolean launchedExternally = false;
    private boolean dispatching = false;
    /** 游戏自己唤起我们时带的 appid，优先于本地选择的 appid。 */
    private String gameAppid = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appCtx = getApplicationContext();
        requestWindowFeature(1);
        setRequestedOrientation(0);
        this.mWindow = getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            this.mWindow.getAttributes().layoutInDisplayCutoutMode = 1;
        }
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
                if (bgc != null) {
                    bgc.setVisibility(android.view.View.GONE);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        android.widget.Button pickToken = findViewById(R.id.btn_pick_token);
        if (pickToken != null) {
            pickToken.setOnClickListener(v -> showPickToken());
        }
        plainText.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(android.text.Editable ed) {
                updateAutoGameHint(ed == null ? "" : ed.toString());
            }
        });

        Intent intent = getIntent();
        String modeFromIntent = null;
        if (intent != null) {
            modeFromIntent = intent.getStringExtra("mode");
        }
        resolveCaller(intent);
        gameAppid = requestedAppid(intent);
        if (modeFromIntent != null && !modeFromIntent.isEmpty()) {
            currentMode = modeFromIntent;
        } else {
            SharedPreferences prefs = getSharedPreferences("myPrefs", MODE_PRIVATE);
            currentMode = prefs.getString("pd_key", "capture");
        }

        String incoming = incomingAuth(intent);

        LoginDiag.add("AGENT", "mode=" + currentMode
                + " game=" + getSelectedGameName()
                + " 外部唤起=" + launchedExternally
                + " 调用方=" + (callerPackage.isEmpty() ? "无" : callerPackage)
                + " 带token=" + (incoming != null)
                + " 游戏请求appid=" + (gameAppid.isEmpty() ? "未提供" : gameAppid)
                + " getCallingActivity=" + (callingActivityName() == null ? "null" : callingActivityName())
                + " 真实回传通道=" + (hasRealChannel() ? "有" : "无"));

        if ("login".equals(currentMode)) {
            setupLoginMode();
        } else if ("capture".equals(currentMode)) {
            setupCaptureMode();
        } else if ("hang_up".equals(currentMode)) {
            setupHangUpMode();
        } else {
            setupCaptureMode();
        }

        if (incoming != null) {
            plainText.setText(incoming);
            Toast.makeText(this, "检测到游戏登录回调", Toast.LENGTH_SHORT).show();
            ui.postDelayed(() -> performLogin(incoming), 300);
        }
    }

    private String incomingAuth(Intent intent) {
        if (intent == null) {
            return null;
        }
        try {
            String data = intent.getDataString();
            String extra = intent.getStringExtra("auth");
            if (extra == null || extra.isEmpty()) {
                extra = intent.getStringExtra("key_response");
            }
            if (data != null && data.contains("access_token=")) {
                return data;
            }
            if (extra != null && extra.contains("access_token=")) {
                return extra;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void resolveCaller(Intent intent) {
        try {
            String ref = "";
            if (Build.VERSION.SDK_INT >= 22) {
                ref = getReferrer() == null ? "" : String.valueOf(getReferrer());
            }
            String pkg = "";
            if (ref.startsWith("android-app://")) {
                pkg = ref.substring("android-app://".length());
                int cut = pkg.indexOf('/');
                if (cut > 0) {
                    pkg = pkg.substring(0, cut);
                }
            }
            if (!pkg.isEmpty() && !getPackageName().equals(pkg)) {
                callerPackage = pkg;
                launchedExternally = true;
            }
            if (intent != null && intent.getData() != null) {
                String scheme = intent.getData().getScheme();
                if (scheme != null && (scheme.equalsIgnoreCase("auth")
                        || scheme.equalsIgnoreCase("tencent")
                        || scheme.equalsIgnoreCase("qq"))) {
                    launchedExternally = true;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mWindow != null) {
            if ("login".equals(currentMode)) {
                mWindow.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
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
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
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
                    String text = plainText.getText() == null ? "" : plainText.getText().toString();
                    if (text.isEmpty() || !text.contains("access_token=")) {
                        Toast.makeText(this, "请先填入 token（可点「选择Token」）", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (dispatching) {
                        Toast.makeText(this, "正在派发，请稍候", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    performLogin(text);
                });
                manualLoginButton.setOnLongClickListener(v -> {
                    showDispatchPicker();
                    return true;
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
        });
    }

    private void showDispatchPicker() {
        final String game = getSelectedGameName();
        final String pkg = GameLaunch.packageOf(game);
        if (pkg.isEmpty()) {
            Toast.makeText(this, "「" + game + "」还没有配置包名，无法列组件", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "正在读取 " + pkg + " 的组件…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final java.util.List<String> all = GameLaunch.activitiesOf(this, pkg);
            final java.util.List<String> cands = GameLaunch.findAuthActivities(this, pkg);
            ui.post(() -> showDispatchDialog(game, pkg, all, cands));
        }).start();
    }

    private void showDispatchDialog(final String game, final String pkg,
                                    java.util.List<String> all, java.util.List<String> cands) {
        if (isFinishing()) {
            return;
        }
        if (all == null || all.isEmpty()) {
            Toast.makeText(this, "没读到 " + pkg + " 的组件，需要 root 或包可见性", Toast.LENGTH_LONG).show();
            LoginDiag.add("DISPATCH", "读取组件为空: " + pkg);
            return;
        }
        final java.util.List<String> items = new java.util.ArrayList<>();
        items.add("清除手动指定（恢复自动挑选）");
        if (cands != null) {
            for (String s : cands) {
                if (!items.contains(s)) {
                    items.add(s);
                }
            }
        }
        for (String s : all) {
            if (!items.contains(s)) {
                items.add(s);
            }
        }
        String[] arr = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            arr[i] = items.get(i);
        }
        LoginDiag.add("DISPATCH", "打开组件选择器 game=" + game + " pkg=" + pkg
                + " 条目=" + items.size());
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择 " + game + " 的登录组件")
                .setItems(arr, (d, which) -> {
                    SharedPreferences sp = getSharedPreferences("myPrefs", MODE_PRIVATE);
                    String key = "dispatch_class_" + game;
                    if (which == 0) {
                        sp.edit().remove(key).apply();
                        LoginDiag.add("DISPATCH", "已清除手动组件: " + game);
                        Toast.makeText(this, "已恢复自动挑选", Toast.LENGTH_SHORT).show();
                    } else {
                        String cls = items.get(which);
                        sp.edit().putString(key, cls).apply();
                        LoginDiag.add("DISPATCH", "手动指定组件 " + game + " -> " + cls);
                        Toast.makeText(this, "已记住: " + cls, Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void updateAutoGameHint(String url) {
        // 自动识别提示已移除
    }

    public void pasteAndLogin() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()
                    && clipboard.getPrimaryClip() != null
                    && clipboard.getPrimaryClip().getItemAt(0) != null) {
                CharSequence cs = clipboard.getPrimaryClip().getItemAt(0).getText();
                String text = cs == null ? "" : cs.toString();
                if (!TextUtils.isEmpty(text)) {
                    LoginDiag.add("LOGIN", "粘贴登录，长度=" + text.length()
                            + " 含token=" + text.contains("access_token="));
                    plainText.setText(text);
                    performLogin(text);
                    Toast.makeText(this, "已从剪贴板粘贴并登录", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "剪贴板没有内容", Toast.LENGTH_SHORT).show();
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
        LoginDiag.add("PARSE", "at=" + (accessToken.isEmpty() ? "缺" : ("len" + accessToken.length()))
                + " oid=" + (openId.isEmpty() ? "缺" : ("len" + openId.length()))
                + " pt=" + (payToken.isEmpty() ? "缺" : ("len" + payToken.length()))
                + " exp=" + (expiresIn.isEmpty() ? "缺" : expiresIn));
        if (accessToken.isEmpty()) {
            LoginDiag.add("ERR", "performLogin: at 为空，内容格式不符");
            Toast.makeText(this, "内容格式错误", Toast.LENGTH_SHORT).show();
            return;
        }

        final String saved = normalizeAuthUrl(url);
        final String gameName = getSelectedGameName();
        final String appid = appidFrom(url);
        final String chosenAppid = getSelectedGameAppid();
        final String mode = currentMode != null ? currentMode : "capture";

        JSONObject json = new JSONObject();
        try {
            json.put("access_token", accessToken);
            json.put("openid", openId);
            json.put("pay_token", payToken);
            json.put("expires_in", expiresIn == null || expiresIn.isEmpty() ? "7776000" : expiresIn);
            json.put("ret", "0");
            json.put("pf", "desktop_m_qq-10000144-android-2002-");
            json.put("page_type", "1");
        } catch (Throwable e) {
            LoginDiag.add("ERR", "JSON: " + e);
            Toast.makeText(this, "JSON处理错误", Toast.LENGTH_SHORT).show();
            return;
        }

        saveRawDataToPreferences(saved);

        LoginDiag.add("RESULT", "game=" + gameName
                + " tokenAppid=" + appid
                + " 已选appid=" + chosenAppid
                + " 一致=" + appid.equals(chosenAppid)
                + " mode=" + mode);

        Intent resultIntent = new Intent();
        resultIntent.putExtra("key_response", json.toString());
        resultIntent.putExtra("auth", saved);
        /*
         * 关键补齐：把 auth URI 同时设成 Intent 的 data。
         *
         * QQ SDK 的 AssistActivity 在 onActivityResult 里是这么解析的（各版本有差异）：
         *   旧版：data.getStringExtra("key_response") → 当 JSON 解析
         *   新版：data.getDataString() → 解析 auth://tauth.qq.com/?#access_token=...
         *
         * 上一版只给了 extras，data 是 null。如果游戏里这份 SDK 走的是 data 分支，
         * 它拿到的就是 null，于是「结果交回去了、游戏也回来了、但就是不登录」——
         * 和日志里观察到的现象完全一致。两条路一起给，哪种解析都能命中。
         */
        String dataUri = saved == null ? "" : saved.replace("auth://tauth.qq.com/?#", "auth://tauth.qq.com/#");
        resultIntent.setData(android.net.Uri.parse(dataUri));
        // 一些 MSDK 版本还会检查这两个标记位，缺了就当「用户取消」处理。
        resultIntent.putExtra("ret", 0);
        resultIntent.putExtra("action", "login");
        setResult(RESULT_OK, resultIntent);

        LoginDiag.add("RETURN", "resultIntent: data="
                + (dataUri.isEmpty() ? "空" : "len" + dataUri.length())
                + " extras=[key_response len" + json.toString().length()
                + ", auth len" + (saved == null ? 0 : saved.length())
                + "] resultCode=RESULT_OK");

        if (!"login".equals(mode)) {
            saveAccountToFile(saved, appid, mode);
            finishLater(1200);
            return;
        }

        if (hasRealChannel()) {
            LoginDiag.add("RETURN", "真实回传通道 " + callingActivityName() + "，只走 setResult");
            Toast.makeText(this, "已交回 " + gameName + "，请看游戏", Toast.LENGTH_SHORT).show();
            finishLater(500);
            return;
        }

        if (!GameLaunch.hasPackage(gameName)) {
            LoginDiag.add("DISPATCH", "「" + gameName + "」未配置包名，只能靠 setResult");
            Toast.makeText(this, "「" + gameName + "」未配置包名，游戏收不到 token",
                    Toast.LENGTH_LONG).show();
            finishLater(2500);
            return;
        }

        dispatching = true;
        Toast.makeText(this, "正在交付给 " + gameName + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int result = GameLaunch.RESULT_NONE;
            try {
                result = GameLaunch.dispatchEx(this, gameName, saved, appid);
            } catch (Throwable t) {
                LoginDiag.add("DISPATCH", "派发异常: " + t);
            }
            final int r = result;
            ui.post(() -> {
                dispatching = false;
                if (isFinishing()) {
                    return;
                }
                if (r == GameLaunch.RESULT_LANDED) {
                    Toast.makeText(this, "已交给 " + gameName + "，请看游戏",
                            Toast.LENGTH_LONG).show();
                    LoginDiag.add("RETURN", "落地成功：回调组件收下且前台稳住");
                    finishLater(900);
                    return;
                }
                if (r == GameLaunch.RESULT_WOKE) {
                    Toast.makeText(this,
                            "已把 " + gameName + " 唤到前台，但 token 可能没被读取；\n"
                                    + "auth 已复制到剪贴板，可在游戏内手动粘贴",
                            Toast.LENGTH_LONG).show();
                    LoginDiag.add("RETURN", "仅唤醒游戏，没有回调组件消费 token");
                    finishLater(2500);
                    return;
                }
                Toast.makeText(this,
                        "交付失败：auth 已复制到剪贴板，请在游戏内手动粘贴",
                        Toast.LENGTH_LONG).show();
                LoginDiag.add("RETURN", "派发全部失败，已回退剪贴板");
                finishLater(2500);
            });
        }).start();
    }

    /** 真实回传通道：调用方是游戏包名，并且落在回调组件上。 */
    private boolean hasRealChannel() {
        try {
            ComponentName c = getCallingActivity();
            if (c == null) {
                return false;
            }
            String pkg = c.getPackageName();
            String cls = c.getClassName() == null ? "" : c.getClassName().toLowerCase();
            if (pkg == null || pkg.equals(getPackageName())) {
                return false;
            }
            String target = GameLaunch.packageOf(getSelectedGameName());
            if (!target.isEmpty() && !target.equals(pkg)) {
                LoginDiag.add("RETURN", "调用方是 " + pkg + "，与目标游戏 " + target + " 不是同一个");
                return false;
            }
            boolean callback = cls.contains("assistactivity") || cls.contains("authactivity")
                    || cls.contains("authorityactivity") || cls.contains("authorize");
            if (!callback) {
                LoginDiag.add("RETURN", "调用方是 " + c.flattenToShortString() + "，不是回调组件");
            }
            return callback;
        } catch (Throwable t) {
            return false;
        }
    }

    private String callingActivityName() {
        try {
            ComponentName c = getCallingActivity();
            return c == null ? null : c.flattenToShortString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 统一收尾。
     *
     * 有真实回传通道时不能删任务，否则结果会跟着任务一起丢；
     * 只在「上号 + 外部唤起 + 没通道」时 finishAndRemoveTask，避免退回 shq 挡住游戏。
     */
    private void finishLater(long delay) {
        final boolean login = "login".equals(currentMode);
        final boolean external = launchedExternally;
        final boolean channel = hasRealChannel();
        final String gameName = getSelectedGameName();
        ui.postDelayed(() -> {
            if (isFinishing()) {
                return;
            }
            boolean removeTask = login && external && !channel;
            if (removeTask && Build.VERSION.SDK_INT >= 21) {
                finishAndRemoveTask();
            } else {
                finish();
            }
            if (login) {
                watchGameBack(gameName);
            }
        }, delay);
    }

    /**
     * 收尾看门狗：finish 之后确认游戏有没有真的回来。
     *
     * 之前日志到「只走 setResult」就断了，看不出结果是否被游戏接受、
     * 游戏有没有回到前台。这里补上，并在游戏没回来时主动切回去，
     * 避免用户停在空白桌面（外观上和黑屏一样）。
     */
    private void watchGameBack(final String gameName) {
        final String pkg = GameLaunch.packageOf(gameName);
        if (pkg.isEmpty()) {
            return;
        }
        new Thread(() -> {
            String comp = "";
            boolean back = false;
            for (int i = 0; i < 6; i++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
                comp = foregroundOf();
                if (comp.startsWith(pkg + "/")) {
                    back = true;
                    break;
                }
            }
            if (back) {
                LoginDiag.add("RETURN", "返回后前台: " + comp + " ✓ 游戏已回到前台");
                return;
            }
            LoginDiag.add("RETURN", "返回后前台: "
                    + (comp.isEmpty() ? "未知" : comp) + " → 游戏没回来，主动切回");
            Context c = appCtx;
            if (c != null) {
                boolean ok = GameLaunch.bringToFront(c, pkg);
                LoginDiag.add("RETURN", "主动切回游戏=" + ok);
            }
        }).start();
    }

    private static String foregroundOf() {
        String dump = runSu("dumpsys window | grep -m 1 -E 'mCurrentFocus|mFocusedApp'");
        String comp = componentOf(dump);
        if (comp.isEmpty()) {
            dump = runSu("dumpsys activity activities | grep -m 1 -E 'topResumedActivity|mResumedActivity'");
            comp = componentOf(dump);
        }
        return comp;
    }

    private static String componentOf(String dump) {
        if (dump == null || dump.isEmpty()) {
            return "";
        }
        int i = 0;
        while (true) {
            int slash = dump.indexOf('/', i);
            if (slash < 0) {
                return "";
            }
            int b = slash;
            while (b > 0 && isCompChar(dump.charAt(b - 1))) {
                b--;
            }
            int e = slash + 1;
            while (e < dump.length() && isCompChar(dump.charAt(e))) {
                e++;
            }
            String left = dump.substring(b, slash);
            String right = dump.substring(slash + 1, e);
            if (left.indexOf('.') > 0 && !right.isEmpty() && right.charAt(0) != '0') {
                return left + "/" + right;
            }
            i = slash + 1;
        }
    }

    private static boolean isCompChar(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '$' || ch == '-';
    }

    private static String runSu(String cmd) {
        final char lf = (char) 10;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(cmd + " 2>&1");
            os.write(lf);
            os.writeBytes("exit");
            os.write(lf);
            os.flush();
            os.close();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) {
                sb.append(ln).append(lf);
            }
            br.close();
            try {
                p.waitFor();
            } catch (InterruptedException ignored) {
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    /**
     * 游戏唤起我们时自己带的 appid。
     *
     * 这是最权威的来源：是游戏本体告诉我们要用哪个 appid 登录，
     * 比本地「已选游戏」的配置可靠。MSDK 会拿它跟 token 里的 appid 校验，
     * 不一致就直接拒绝 —— 这正是「token 交回了但游戏不登录」最常见的原因。
     */
    private String requestedAppid(Intent intent) {
        if (intent == null) {
            return "";
        }
        String[] keys = {"appid", "app_id", "client_id", "aid", "srcAppid"};
        for (String k : keys) {
            try {
                String v = intent.getStringExtra(k);
                String digits = com.tencent.tim.GameLaunch.digitsOf(v);
                if (!digits.isEmpty()) {
                    return digits;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            String d = intent.getDataString();
            String digits = com.tencent.tim.GameLaunch.digitsOf(
                    com.tencent.tim.GameLaunch.paramOf(d, "client_id"));
            if (digits.isEmpty()) {
                digits = com.tencent.tim.GameLaunch.digitsOf(
                        com.tencent.tim.GameLaunch.paramOf(d, "appid"));
            }
            return digits;
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String appidFrom(String url) {
        String a = com.tencent.tim.TokenStore.appidOfUrl(url);
        if (a == null || a.isEmpty()) {
            a = gameAppid;
        }
        if (a == null || a.isEmpty()) {
            String su = getSharedPreferences("myPrefs", MODE_PRIVATE).getString("selected_game_url", "");
            a = com.tencent.tim.TokenStore.appidOfUrl(su);
        }
        return (a == null || a.isEmpty()) ? "未知" : a;
    }

    private String getSelectedGameAppid() {
        try {
            if (gameAppid != null && !gameAppid.isEmpty()) {
                return gameAppid;
            }
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

    /**
     * 把任意形态的 token 文本规范成一个 auth:// URL。
     *
     * 2026-10-25 第九次修订（三角洲）。
     *
     * 上一版是「按固定字段重拼」：只取 access_token / openid / pay_token /
     * expires_in / pfkey / auth_time 六个字段，其余全部丢弃。
     *
     * 三角洲的 MSDK 不走这套精简格式，它要整条原始回包：
     *   - 有的版本要 &key= / &pf= / &page_type= 原值比对
     *   - 有的版本要 &ret= / &expires_in= 之外的 &sig=、&user_cancelled= 之类
     *   - 少了任何一个字段，checkToken 就会判「响应不完整」直接 ret=-1
     *
     * 症状和现在一模一样：结果交回去了、游戏回来了、就是不登录。
     *
     * 所以改成「原样保留」：
     *   已经是 auth:// 的，一个字节都不动；
     *   不是的，把原始 query 整段搬过来，只补缺失的必需字段，不删任何东西。
     */
    private String normalizeAuthUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        if (url.startsWith("auth://tauth.qq.com") && url.contains("access_token=")) {
            // 原始回包，原样使用。三角洲要的就是这个完整度。
            LoginDiag.add("PARSE", "auth URI 原样保留 len=" + url.length()
                    + " 字段数=" + countParams(url));
            return url;
        }
        String at = grab(url, "access_token=([^&#]+)");
        if (at.isEmpty()) {
            return url;
        }

        // 取出原始 query 整段（access_token 之后的全部内容），不做任何筛选。
        int q = url.indexOf("access_token=");
        String tail = q < 0 ? "" : url.substring(q);
        // 去掉原串里可能带的 scheme/host 残留，只留 key=value&key=value
        int bad = tail.indexOf('#');
        if (bad >= 0) {
            tail = tail.substring(0, bad);
        }
        tail = tail.trim();

        String openid = grab(url, "openid=([^&#]+)");
        String exp = grab(url, "expires_in=([^&#]+)");
        String pay = grab(url, "pay_token=([^&#]+)");

        StringBuilder sb = new StringBuilder("auth://tauth.qq.com/?#");
        sb.append(tail);

        // 必需字段兜底：原始串里没有才补，有就不动。
        if (!tail.contains("expires_in=")) {
            sb.append("&expires_in=").append(exp.isEmpty() ? "7776000" : exp);
        }
        if (!tail.contains("openid=")) {
            sb.append("&openid=").append(openid);
        }
        if (!tail.contains("pay_token=")) {
            sb.append("&pay_token=").append(pay);
        }
        if (!tail.contains("ret=")) {
            sb.append("&ret=0");
        }
        if (!tail.contains("pf=")) {
            sb.append("&pf=desktop_m_qq-10000144-android-2002-");
        }
        if (!tail.contains("page_type=")) {
            sb.append("&page_type=0");
        }

        String out = sb.toString();
        LoginDiag.add("PARSE", "auth URI 重建 len=" + out.length()
                + " 原始字段数=" + countParams(url) + " 输出字段数=" + countParams(out));
        return out;
    }

    /** 数 key=value 的个数，只用于日志判断字段有没有被丢掉。 */
    private static int countParams(String url) {
        if (url == null || url.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < url.length(); i++) {
            if (url.charAt(i) == '=') {
                n++;
            }
        }
        return n;
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
                LoginDiag.add("SAVE", "game=" + gameName + " appid=" + aidShown + " ok=" + ok
                        + (ok ? "" : (" err=" + com.tencent.tim.TokenStore.lastError)));
                final String g = gameName;
                ui.post(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    if (ok) {
                        Toast.makeText(this,
                                "已保存 " + g + " appid=" + aidShown + " -> "
                                        + com.tencent.tim.TokenStore.gameFile(g).getAbsolutePath(),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "保存失败: " + com.tencent.tim.TokenStore.lastError,
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                LoginDiag.add("ERR", "saveAccountToFile: " + e);
                e.printStackTrace();
            }
        }).start();
    }
}
