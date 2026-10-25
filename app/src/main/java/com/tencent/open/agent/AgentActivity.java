package com.tencent.open.agent;

import android.content.ClipboardManager;
import android.content.ComponentName;
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

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上号 / 抓号 / 挂机 三种模式的界面。
 *
 * 上号模式（2026-10-25 第六次修订）。
 *
 * 上一版的黑屏，根因是两个错误叠在一起：
 *
 * 1) 有真实回传通道，却去启动了游戏的内部界面。
 *
 *    getCallingActivity = com.tencent.tmgp.dfm/com.tencent.connect.common.AssistActivity
 *
 *    AssistActivity 是 QQ SDK 里收结果、回调 IUiListener 的组件，它确实在
 *    startActivityForResult 等我们。这种情况下正确做法只有一个：setResult 后
 *    立刻 finish，一个多余界面都别启动。
 *    结果代码去 began 了 MSDK 的 WebViewActivity —— 那是「内部页」，
 *    靠 Intent 里的 url / title 参数渲染，我们给不出，它起来就
 *    destroySurface 退出，留下空任务栈 → 黑屏。
 *
 * 2) 「收下并停在前台」被当成了成功，没有区分「稳住」和「一闪而过」。
 *
 * 现在的规则：
 *
 *   有真实回传通道 → 只 setResult + finish，绝不碰游戏内部界面。
 *   （真实通道的判定：调用方是游戏包名，且落在 AssistActivity /
 *    AuthActivity / AuthorityActivity 这类回调组件上。）
 *
 *   没有真实通道 → 才交给 GameLaunch 兜底，而它现在也只试回调组件，
 *    内部 UI 页（webview / wechat / pay / splash ...）一律排除，
 *    并且落地后要再等一会儿确认前台稳住，避免再黑屏。
 */
public class AgentActivity extends BaseActivity {

    private static final String PREF_NAME = "MyPrefs";
    private static final String KEY_RAW_TEXT = "raw_text";

    private final Handler ui = new Handler(Looper.getMainLooper());

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        // QQ SDK 的 AssistActivity 会优先读 key_response，也兼容 auth 字段
        resultIntent.putExtra("auth", saved);
        setResult(RESULT_OK, resultIntent);

        if (!"login".equals(mode)) {
            saveAccountToFile(saved, appid, mode);
            finishLater(1200);
            return;
        }

        // ---- 上号模式 ----
        //
        // 先判断真实回传通道，有就直接交回去，绝不启动游戏内部界面。
        // 上一版的 WebViewActivity 就是这么把屏幕搞黑的：
        // 它是 MSDK 内部页，缺 url 参数，起来就 destroySurface 退出，
        // 留下空任务栈，用户看到的是黑屏。
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

    /**
     * 真实回传通道：调用方是游戏包名，并且落在回调组件上。
     *
     * 判据来自实际日志：三角洲唤起时 getCallingActivity 是它自己的
     * com.tencent.connect.common.AssistActivity，这是 QQ SDK 收结果的地方。
     * 而 ColorOS 在 NEW_TASK 唤起时可能把调用方填成别的包（甚至自己），
     * 那种情况不能当成有通道。
     */
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
            // 调用方必须就是我们要交付的那个游戏
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

    /** 调用方组件名；null 表示系统没记。 */
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
     * 只有「上号 + 外部唤起 + 没有真实回传通道」才删任务：
     * 有通道时必须让结果随着任务栈一起交回游戏，删了任务结果就丢了。
     */
    private void finishLater(long delay) {
        ui.postDelayed(() -> {
            if (isFinishing()) {
                return;
            }
            boolean hasChannel = hasRealChannel();
            boolean removeTask = "login".equals(currentMode) && launchedExternally && !hasChannel;
            if (removeTask && Build.VERSION.SDK_INT >= 21) {
                finishAndRemoveTask();
            } else {
                finish();
            }
        }, delay);
    }

    private String appidFrom(String url) {
        String a = com.tencent.tim.TokenStore.appidOfUrl(url);
        if (a == null || a.isEmpty()) {
            String su = getSharedPreferences("myPrefs", MODE_PRIVATE).getString("selected_game_url", "");
            a = com.tencent.tim.TokenStore.appidOfUrl(su);
        }
        return (a == null || a.isEmpty()) ? "未知" : a;
    }

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
        if (url == null || url.isEmpty()) {
            return url;
        }
        if (url.startsWith("auth://tauth.qq.com") && url.contains("access_token=")) {
            return url;
        }
        String at = grab(url, "access_token=([^&#]+)");
        if (at.isEmpty()) {
            return url;
        }
        String openid = grab(url, "openid=([^&#]+)");
        String pay = grab(url, "pay_token=([^&#]+)");
        String exp = grab(url, "expires_in=([^&#]+)");
        if (exp.isEmpty()) {
            exp = "7776000";
        }
        String authTime = grab(url, "auth_time=([^&#]+)");
        String pfkey = grab(url, "key=([^&#]+)");
        StringBuilder sb = new StringBuilder("auth://tauth.qq.com/?#");
        sb.append("access_token=").append(at);
        sb.append("&expires_in=").append(exp);
        sb.append("&openid=").append(openid);
        sb.append("&pay_token=").append(pay);
        sb.append("&ret=0");
        sb.append("&pf=desktop_m_qq-10000144-android-2002-");
        if (!pfkey.isEmpty()) {
            sb.append("&pfkey=").append(pfkey);
        }
        if (!authTime.isEmpty()) {
            sb.append("&auth_time=").append(authTime);
        }
        sb.append("&page_type=0");
        return sb.toString();
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
