package com.tencent.tim;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.tencent.open.agent.AgentActivity;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends BaseActivity {

    private static final String PREF_NAME = "myPrefs";
    private static final String SELECTED_GAME_NAME_KEY = "selected_game_name";
    private static final String SELECTED_GAME_URL_KEY = "selected_game_url";
    private static final String GAME_LIST_URL = "https://iappht.sslqq.cn/user/getFile.php?fid=7487";
    private static final String PTLOGIN_BASE = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?pt_enable_pwd=1&appid=716027609&pt_3rd_aid=%s&daid=381&pt_skey_valid=0&style=35&force_qr=1&autorefresh=1&s_url=http://connect.qq.com&refer_cgi=m_authorize&ucheck=1&fall_to_wv=1&status_os=0&redirect_uri=auth://tauth.qq.com/&client_id=%s&pf=openmobile_web&response_type=token&scope=all&sdkp=a&sdkv=3.5.14&sign=0&status_machine=0&switch=1&time=0&loginfrom=add&h5sig=0&loginty=6";
    private static final String KEY_SYNCED_DATA = "synced_data";
    private static final String KEY_CLOUD_APPID_ENABLED = "cloud_appid_enabled";
    private static final String KEY_STORAGE_URI = "storage_uri";

    /**
     * 内置 AppID 列表（与云端 getFile.php 校验过的一致数据）。
     * 作用：云端拉不到、本地缓存又是空的时候，仍然有合法 AppID 可用，
     * 不会退化到「加载一条 client_id 为空的登录地址」→「无法获取appid」→ 一直转圈。
     */
    private static final String[][] BUILTIN_GAMES = new String[][]{
            {"和平", "1106467070"},
            {"王者", "1104466820"},
            {"火影", "1104307008"},
            {"使命", "1106838536"},
            {"PUBG", "1106545419"},
            {"穿越火线手游", "1104512706"},
            {"三角洲", "1110543085"},
            {"暗区突围", "1110196838"},
            {"永劫无间", "102150659"},
            {"无畏契约", "1111677210"},
            {"极品飞车", "1108169089"},
            {"香肠派对", "1107970117"},
            {"QQ飞车手游", "1104922185"},
            {"英雄联盟手游", "1106040804"},
            {"腾讯欢乐斗地主", "363"},
            {"金铲铲之战", "1109811436"},
            {"地下城与勇士起源", "1104294055"},
            {"天天酷跑", "100692648"},
            {"洛克王国世界", "1110613799"},
            {"部落冲突", "1105771533"},
            {"高能英雄", "1110329808"},
            {"妄想山海", "1109838467"},
            {"斗地主", "363"}
    };

    private static final int REQUEST_CODE_OPEN_DIRECTORY = 1;
    private static final int REQUEST_STORAGE_PERMISSION = 200;
    private static final int REQUEST_MANAGE_STORAGE = 300;
    private static final int REQUEST_AGENT_ACTIVITY = 400;
    private static final int REQUEST_CODE_IMPORT = 1002;
    private static final String KEY_IMPORT_URI = "import_uri";

    private RadioGroup modeRadioGroup;
    private RadioButton radioCaptureMode;
    private RadioButton radioLoginMode;
    private RadioButton radioHangUpMode;
    private Button selectGameButton;
    private Button cloudAppidButton;
    private Button startButton;
    private Button hideButton;
    private Button showButton;
    private Button selectDirectoryButton;
    private TextView directoryStatusText;
    private TextView storagePermissionStatusText;
    private TextView tvLog;
    private ScrollView scrollLog;

    private Window mWindow;

    private List<GameItem> gameList = new ArrayList<>();
    private String selectedGameUrl = "";
    private String selectedGameName = "";
    private Uri storageUri = null;
    private boolean useSaf = false;

    /** 一个 GitHub Release 的下载信息。 */
    private static class ReleaseInfo {
        String version = "";
        String title = "";
        String body = "";
        String apkUrl = "";
        long size = 0;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupImmersiveMode();

        setContentView(R.layout.activity_main);

        initViews();
        setupClickListeners();
        loadSavedMode();
        loadSelectedGame();
        loadStorageMethod();

        addLog("[系统] WEI终端已启动");
        addLog("[系统] Android 版本: " + Build.VERSION.SDK_INT);

        getWindow().getDecorView().postDelayed(new Runnable() { public void run() { checkAndRequestAllPermissions(); } }, 300);

        refreshGameList();
        checkForUpdates();
    }

    /** 设置沉浸式效果（保留状态栏）。 */
    private void setupImmersiveMode() {
        mWindow = getWindow();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            mWindow.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mWindow.setStatusBarColor(Color.parseColor("#0A0F1A"));
            mWindow.setNavigationBarColor(Color.parseColor("#0A0F1A"));
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mWindow != null) {
            mWindow.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    /**
     * 追加日志，敏感信息统一由 LogScrubber 脱敏。
     * 不再逐行打时间戳——满屏 [10:24:31] 反而把真正的内容挤掉，
     * 需要时间的地方（启动、结束）由调用方自己带。
     */
    private void addLog(final String log) {
        runOnUiThread(() -> {
            String msg = LogScrubber.scrub(log);
            String newLog = "› " + msg + "\n";
            if (tvLog != null) tvLog.append(newLog);
            // 流式渐显：新到的一行从轻微模糊过渡到清晰，避免整块文字硬跳
            com.tencent.tim.Ui.fadeInBlur(tvLog);
            if (scrollLog != null) scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void initViews() {
        modeRadioGroup = findViewById(R.id.mode_radio_group);
        radioCaptureMode = findViewById(R.id.radio_capture_mode);
        radioLoginMode = findViewById(R.id.radio_login_mode);
        radioHangUpMode = findViewById(R.id.radio_hang_up_mode);
        selectGameButton = findViewById(R.id.btn_select_game);
        cloudAppidButton = findViewById(R.id.btn_cloud_appid);
        startButton = findViewById(R.id.btn_start);
        hideButton = findViewById(R.id.btn_hide);
        showButton = findViewById(R.id.btn_show);
        selectDirectoryButton = findViewById(R.id.btn_select_directory);
        directoryStatusText = findViewById(R.id.txt_directory_status);
        storagePermissionStatusText = findViewById(R.id.txt_storage_permission_status);
        tvLog = findViewById(R.id.tv_log);
        scrollLog = findViewById(R.id.scroll_log);

        updateSelectGameButtonText();
        if (cloudAppidButton != null) {
            cloudAppidButton.setOnClickListener(v -> toggleCloudAppid());
        }
        updateCloudAppidButton();
        updateStorageStatus();
        android.widget.Button viewToken = findViewById(R.id.btn_view_token);
        if (viewToken != null) {
            viewToken.setOnClickListener(v -> TokenDialog.show(this, "", null));
        }
        android.widget.Button sb = findViewById(R.id.btn_settings);
        if (sb == null) return;
        sb.setOnClickListener(v -> startActivity(new android.content.Intent(this, SettingsActivity.class)));
    }

    private void showCopyTokenMenu() {
        final java.util.List<TokenStore.Entry> list = selectedGameName.isEmpty()
                ? TokenStore.loadAll() : TokenStore.loadGame(selectedGameName);
        if (list.isEmpty()) {
            addLog("[Token] 当前游戏没有已保存的 Token");
            Toast.makeText(this, "没有已保存的 Token，目录: " + TokenStore.rootDir().getAbsolutePath(), Toast.LENGTH_LONG).show();
            return;
        }
        TokenStore.Entry e = list.get(list.size() - 1);
        final String[] items = new String[]{
                "复制 access_token",
                "复制 openid",
                "复制 pay_token",
                "复制 expires_in",
                "复制完整链接",
                "复制全部 token（全部分段逐行）"
        };
        new AlertDialog.Builder(this)
                .setTitle("复制 Token · " + (selectedGameName.isEmpty() ? e.game : selectedGameName))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: TokenStore.copy("access_token", e.accessToken, this); break;
                        case 1: TokenStore.copy("openid", e.openid, this); break;
                        case 2: TokenStore.copy("pay_token", e.payToken, this); break;
                        case 3: TokenStore.copy("expires_in", e.expiresIn, this); break;
                        case 4: TokenStore.copy("完整链接", TokenStore.rebuildUrl(e), this); break;
                        default:
                            TokenStore.copy("全部 token", allSegments(list), this);
                            break;
                    }
                    addLog("[Token] 已复制: " + items[which]);
                })
                .show();
    }

    private String allSegments(java.util.List<TokenStore.Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            TokenStore.Entry e = list.get(i);
            sb.append("[").append(e.game).append("] ").append(e.time).append("\n");
            sb.append("access_token=").append(e.accessToken).append("\n");
            sb.append("openid=").append(e.openid).append("\n");
            sb.append("pay_token=").append(e.payToken).append("\n");
            sb.append("expires_in=").append(e.expiresIn).append("\n\n");
        }
        return sb.toString().trim();
    }

    public void startImportFilePicker() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        android.content.SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String dirUri = sp.getString(KEY_IMPORT_URI, "");
        if (dirUri != null && dirUri.length() > 0 && Build.VERSION.SDK_INT >= 26) {
            try { intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(dirUri)); } catch (Throwable t) {}
        }
        startActivityForResult(intent, REQUEST_CODE_IMPORT);
    }

    private String getFileNameFromUri(android.net.Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx != -1) result = cursor.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private void loadStorageMethod() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String uriString = prefs.getString(KEY_STORAGE_URI, "");
        if (!uriString.isEmpty()) {
            storageUri = Uri.parse(uriString);
            useSaf = true;
        }
    }

    private void saveStorageUri(Uri uri) {
        storageUri = uri;
        SharedPreferences.Editor editor = getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
        editor.putString(KEY_STORAGE_URI, uri.toString());
        editor.apply();
        useSaf = true;
        updateStorageStatus();
    }

    private void updateStorageStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                directoryStatusText.setText("● 存储状态: 正常");
                directoryStatusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                storagePermissionStatusText.setText("● 全文件访问: 已授权");
                storagePermissionStatusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            } else {
                directoryStatusText.setText("● 存储状态: 未授权");
                directoryStatusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                storagePermissionStatusText.setText("● 全文件访问: 未授权");
                storagePermissionStatusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            }
        } else if (storageUri != null) {
            directoryStatusText.setText("● 存储状态: SAF 已授权");
            directoryStatusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        } else {
            if (hasStoragePermission()) {
                directoryStatusText.setText("● 存储状态: 已授权");
                directoryStatusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                storagePermissionStatusText.setText("● 传统存储权限: 已授权");
                storagePermissionStatusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            } else {
                directoryStatusText.setText("● 存储状态: 未授权");
                directoryStatusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                storagePermissionStatusText.setText("● 传统存储权限: 未授权");
                storagePermissionStatusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            }
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            android.Manifest.permission.READ_EXTERNAL_STORAGE
                    },
                    REQUEST_STORAGE_PERMISSION);
        }
    }

    private void checkAndRequestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                addLog("[权限] 请求全文件访问权限");
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
            } else {
                addLog("[权限] 已获得全文件访问权限");
                createDataDirectory();
            }
        } else {
            if (!hasStoragePermission()) {
                addLog("[权限] 请求传统存储权限");
                requestStoragePermission();
            } else {
                addLog("[权限] 已获得存储权限");
                createDataDirectory();
            }
        }
    }

    private void createDataDirectory() {
        try {
            File dir = TokenStore.rootDir();
            if (!dir.exists()) {
                dir.mkdirs();
                addLog("[文件] 创建目录: " + dir.getAbsolutePath());
            } else {
                addLog("[文件] 目录已存在: " + dir.getAbsolutePath());
            }
            addLog("[文件] 采用平铺保存：WEI/游戏名.txt");
        } catch (Exception e) {
            addLog("[错误] 创建目录失败: " + e.getMessage());
        }
    }

    private void setupClickListeners() {
        modeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radio_capture_mode) {
                saveMode("capture");
                addLog("[模式] 切换到抓号模式");
            } else if (checkedId == R.id.radio_login_mode) {
                saveMode("login");
                addLog("[模式] 切换到上号模式");
            } else if (checkedId == R.id.radio_hang_up_mode) {
                saveMode("hang_up");
                addLog("[模式] 切换到挂机模式");
            }
        });

        selectGameButton.setOnClickListener(v -> showGameSelectionDialog());

        startButton.setOnClickListener(v -> {
            addLog("[启动] 开始执行任务");
            startAgentActivity();
        });

        selectDirectoryButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
            } else {
                requestDirectoryPermission();
            }
        });

        hideButton.setOnClickListener(v -> {
            try {
                hidePackage("com.tencent.mobileqq");
                addLog("[Root] 隐藏QQ成功");
                Toast.makeText(this, "隐藏成功", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                addLog("[错误] 隐藏QQ失败: " + e.getMessage());
                Toast.makeText(this, "隐藏失败，请给root再重试", Toast.LENGTH_SHORT).show();
            }
        });

        showButton.setOnClickListener(v -> {
            try {
                unhidePackage("com.tencent.mobileqq");
                addLog("[Root] 显示QQ成功");
                Toast.makeText(this, "恢复成功", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                addLog("[错误] 显示QQ失败: " + e.getMessage());
                Toast.makeText(this, "恢复失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void requestDirectoryPermission() {
        addLog("[SAF] 请求目录访问权限");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_OPEN_DIRECTORY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_IMPORT && resultCode == RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();
            if (uri != null) {
                try { getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable t) {}
                try { getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(KEY_IMPORT_URI, uri.toString()).apply(); } catch (Throwable t) {}
                TokenStore.ImportResult res = TokenStore.importFromUri(this, uri);
                addLog("[导入] 自动识别 " + res.added + " 条，待归类 " + res.unknownLines.size() + " 条，跳过 " + res.skipped + " 条" + (TokenStore.lastError.isEmpty() ? "" : ("，错误: " + TokenStore.lastError)));
                TokenDialog.showChooseGameForTokens(this, res);
            }
        }

        if (requestCode == REQUEST_CODE_OPEN_DIRECTORY && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                     Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                saveStorageUri(treeUri);
                createSafDirectory();
                addLog("[SAF] 目录授权成功");
                Toast.makeText(this, "目录授权成功", Toast.LENGTH_SHORT).show();
            }
        }

        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    addLog("[权限] 已获得全文件访问权限");
                    createDataDirectory();
                    updateStorageStatus();
                    Toast.makeText(this, "已获得所有文件访问权限", Toast.LENGTH_SHORT).show();
                } else {
                    addLog("[权限] 全文件访问权限被拒绝");
                    Toast.makeText(this, "需要所有文件访问权限才能保存数据", Toast.LENGTH_LONG).show();
                }
            }
        }

        if (requestCode == REQUEST_AGENT_ACTIVITY && resultCode == RESULT_OK && data != null) {
            String response = data.getStringExtra("key_response");
            if (response != null) {
                addLog("[抓取] Token获取成功");
                addLog("[抓取] 响应: " + response.substring(0, Math.min(100, response.length())) + "...");
            }
        }
    }

    private void createSafDirectory() {
        if (storageUri == null) return;

        try {
            DocumentFile rootDir = DocumentFile.fromTreeUri(this, storageUri);
            if (rootDir != null) {
                DocumentFile dataDir = rootDir.findFile("WEI");
                if (dataDir == null) {
                    dataDir = rootDir.createDirectory("WEI");
                    addLog("[SAF] 创建目录: WEI");
                }
                if (dataDir != null) {
                    addLog("[SAF] 目录创建成功");
                }
            }
        } catch (Exception e) {
            addLog("[错误] SAF目录创建失败: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                addLog("[权限] 存储权限已授予");
                createDataDirectory();
                updateStorageStatus();
            } else {
                addLog("[权限] 存储权限被拒绝");
            }
        }
    }

    private void loadSavedMode() {
        String mode = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString("pd_key", "capture");
        if ("login".equals(mode)) {
            radioLoginMode.setChecked(true);
        } else if ("capture".equals(mode)) {
            radioCaptureMode.setChecked(true);
        } else if ("hang_up".equals(mode)) {
            radioHangUpMode.setChecked(true);
        }
    }

    private void loadSelectedGame() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        selectedGameUrl = prefs.getString(SELECTED_GAME_URL_KEY, "");
        selectedGameName = prefs.getString(SELECTED_GAME_NAME_KEY, "");
        updateSelectGameButtonText();
    }

    private void updateSelectGameButtonText() {
        if (!selectedGameName.isEmpty()) {
            selectGameButton.setText("> " + selectedGameName);
        } else {
            selectGameButton.setText("> 选择游戏");
        }
    }

    public void saveMode(String mode) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putString("pd_key", mode)
                .apply();
    }

    public void saveSelectedGame(String name, String url) {
        SharedPreferences.Editor editor = getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
        editor.putString(SELECTED_GAME_NAME_KEY, name);
        editor.putString(SELECTED_GAME_URL_KEY, url);
        editor.apply();

        selectedGameName = name;
        selectedGameUrl = url;
        updateSelectGameButtonText();
        addLog("[游戏] 已选择: " + name);
        Toast.makeText(this, "已选择游戏: " + name, Toast.LENGTH_SHORT).show();
    }

    public void showGameSelectionDialog() {
        if (gameList.isEmpty()) {
            addLog("[游戏] 列表为空，正在加载...");
            loadGameList();
        }
        if (gameList.isEmpty()) {
            Toast.makeText(this, "暂无可用游戏，请检查网络后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        final float density = getResources().getDisplayMetrics().density;
        final int dp2 = (int) (2 * density);
        final int dp6 = (int) (6 * density);
        final int dp10 = (int) (10 * density);
        final int dp16 = (int) (16 * density);
        final int dp22 = (int) (22 * density);
        final int screenH = getResources().getDisplayMetrics().heightPixels;
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dlg.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackground(com.tencent.tim.TokenDialog.glass(this, 22, true));
        root.setPadding(dp16, dp16, dp16, dp10);

        // ---- 标题行：选择游戏 · 共 N 个 · 关闭 ----
        android.widget.LinearLayout head = new android.widget.LinearLayout(this);
        head.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("选择游戏");
        title.setTextColor(0xFFF7F8FA);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        final android.widget.TextView countTv = new android.widget.TextView(this);
        countTv.setText("共 " + gameList.size() + " 个");
        countTv.setTextColor(0xFF8A93A3);
        countTv.setTextSize(10);
        countTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        android.widget.LinearLayout.LayoutParams countLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        countLp.leftMargin = dp10;
        head.addView(countTv, countLp);
        android.widget.TextView close = new android.widget.TextView(this);
        close.setText("关闭");
        close.setTextColor(0xFFB4BCC9);
        close.setTextSize(11);
        close.setPadding(dp10, dp6, dp10, dp6);
        close.setBackground(com.tencent.tim.TokenDialog.glass(this, 12, false));
        close.setOnClickListener(v -> {
            try {
                dlg.dismiss();
            } catch (Throwable ignored) {
            }
        });
        head.addView(close);
        root.addView(head, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- 搜索框 ----
        final android.widget.EditText search = new android.widget.EditText(this);
        search.setSingleLine(true);
        search.setTextSize(12);
        search.setTextColor(0xFFF7F8FA);
        search.setHintTextColor(0xFF8A93A3);
        search.setHint("搜索游戏名 / AppID");
        search.setBackgroundResource(R.drawable.glass_input);
        search.setPadding(dp10, dp6, dp10, dp6);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        android.widget.LinearLayout.LayoutParams searchLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        searchLp.topMargin = dp10;
        searchLp.bottomMargin = dp10;
        root.addView(search, searchLp);

        // ---- 宫格列表 ----
        final android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        final android.widget.LinearLayout grid = new android.widget.LinearLayout(this);
        grid.setOrientation(android.widget.LinearLayout.VERTICAL);
        int gridMax = (int) (screenH * 0.5f);
        int gridMin = (int) (140 * density);
        scroll.addView(grid, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        int gridH = Math.min(gridMax, Math.max(gridMin, (int) (((gameList.size() + 2) / 3) * 58 * density)));
        root.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, gridH));

        // ---- 底部提示 ----
        android.widget.TextView hint = new android.widget.TextView(this);
        hint.setText("点选后自动带入该游戏 AppID");
        hint.setTextColor(0xFF8A93A3);
        hint.setTextSize(9);
        hint.setTypeface(android.graphics.Typeface.MONOSPACE);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setPadding(0, dp10, 0, 0);
        root.addView(hint);

        renderGameGrid(grid, gameList, dlg, countTv);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            public void afterTextChanged(android.text.Editable e) {
                String q = e == null ? "" : e.toString().trim().toLowerCase(java.util.Locale.ROOT);
                java.util.List<GameItem> hit = new java.util.ArrayList<GameItem>();
                for (GameItem gi : gameList) {
                    if (gi == null) continue;
                    if (q.isEmpty()) {
                        hit.add(gi);
                        continue;
                    }
                    String nm = gi.name == null ? "" : gi.name.toLowerCase(java.util.Locale.ROOT);
                    String ap = com.tencent.tim.TokenStore.appidOfUrl(gi.url);
                    if (nm.contains(q) || (ap != null && ap.contains(q))) hit.add(gi);
                }
                countTv.setText(q.isEmpty() ? ("共 " + gameList.size() + " 个") : ("命中 " + hit.size() + " 个"));
                renderGameGrid(grid, hit, dlg, countTv);
            }
        });

        dlg.setContentView(root);
        dlg.show();
        Ui.applyDialog(dlg);
    }

    /**
     * 把游戏列表画成三列宫格：每格上排游戏名、下排 AppID（等宽小字），
     * 比原来两列青色文字条更好扫读，长名字也不会撑破一行。
     */
    private void renderGameGrid(final android.widget.LinearLayout grid, final java.util.List<GameItem> items,
                                final android.app.Dialog dlg, final android.widget.TextView countTv) {
        if (grid == null) return;
        final float density = getResources().getDisplayMetrics().density;
        final int cols = 3;
        final int gap = (int) (5 * density);
        grid.removeAllViews();
        if (items == null || items.isEmpty()) {
            android.widget.TextView empty = new android.widget.TextView(this);
            empty.setText("没有匹配的游戏");
            empty.setTextColor(0xFF8A93A3);
            empty.setTextSize(12);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, (int) (28 * density), 0, (int) (28 * density));
            grid.addView(empty, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            return;
        }
        android.widget.LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) {
                row = new android.widget.LinearLayout(this);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = gap;
                grid.addView(row, rlp);
            }
            final GameItem it = items.get(i);
            android.widget.LinearLayout cell = new android.widget.LinearLayout(this);
            cell.setOrientation(android.widget.LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER);
            cell.setPadding(gap, (int) (9 * density), gap, (int) (9 * density));
            cell.setBackground(com.tencent.tim.TokenDialog.glass(this, 12, false));

            android.widget.TextView nm = new android.widget.TextView(this);
            nm.setText(it.name == null ? "未知" : it.name);
            nm.setTextColor(0xFFF7F8FA);
            nm.setTextSize(13);
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nm.setGravity(android.view.Gravity.CENTER);
            cell.addView(nm, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

            android.widget.TextView ap = new android.widget.TextView(this);
            String appid = com.tencent.tim.TokenStore.appidOfUrl(it.url);
            ap.setText(appid == null || appid.isEmpty() ? "----" : appid);
            ap.setTextColor(0xFF8A93A3);
            ap.setTextSize(9);
            ap.setTypeface(android.graphics.Typeface.MONOSPACE);
            ap.setSingleLine(true);
            ap.setGravity(android.view.Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams apLp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            apLp.topMargin = (int) (3 * density);
            cell.addView(ap, apLp);

            cell.setOnClickListener(v -> {
                try {
                    if (it != null && it.name != null && it.url != null) {
                        saveSelectedGame(it.name, it.url);
                        dlg.dismiss();
                    } else {
                        addLog("[错误] 游戏数据错误");
                        Toast.makeText(this, "游戏数据错误", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    addLog("[错误] 选择游戏失败: " + e.getMessage());
                }
            });

            android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % cols != 0) clp.leftMargin = gap;
            row.addView(cell, clp);
        }
        int rest = items.size() % cols;
        if (rest != 0 && row != null) {
            for (int k = rest; k < cols; k++) {
                android.view.View sp = new android.view.View(this);
                row.addView(sp, new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
    }

    private boolean isCloudAppidEnabled() {
        return getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(KEY_CLOUD_APPID_ENABLED, true);
    }

    private void updateCloudAppidButton() {
        if (cloudAppidButton == null) return;
        cloudAppidButton.setText(isCloudAppidEnabled() ? "云端AppID: 开启" : "云端AppID: 关闭 仅本地缓存");
    }

    private void toggleCloudAppid() {
        boolean next = !isCloudAppidEnabled();
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(KEY_CLOUD_APPID_ENABLED, next).apply();
        updateCloudAppidButton();
        if (next) {
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().remove(KEY_SYNCED_DATA).apply();
            gameList.clear();
            addLog("[云端] 已开启 缓存已清空 拉取云端");
            loadGameListFromServer();
        } else {
            overwriteLocalFromList();
            gameList.clear();
            addLog("[云端] 已关闭 本地缓存已覆盖 仅用本地");
            loadGameList();
        }
    }

    private void refreshGameList() {
        gameList.clear();
        if (isCloudAppidEnabled()) {
            loadGameListFromServer();
        } else {
            addLog("[云端] 已关闭 启动只加载本地缓存");
            loadGameList();
        }
    }

    private void overwriteLocalFromList() {
        if (gameList.isEmpty()) { addLog("[local] 列表为空 保留原缓存"); return; }
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        StringBuilder sb = new StringBuilder();
        for (GameItem it : gameList) {
            String appid = extractAppid(it.url);
            if (appid == null || appid.isEmpty()) continue;
            if (!seen.add(appid)) continue;
            if (sb.length() > 0) sb.append(";");
            sb.append(it.name).append("|").append(appid);
        }
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(KEY_SYNCED_DATA, sb.toString()).apply();
        try { com.tencent.tim.TokenStore.invalidateGamesCache(); } catch (Throwable ignored) {}
        addLog("[local] 缓存已覆盖: " + seen.size());
    }

    private String buildLocalUrl(String appid) {
        return String.format(PTLOGIN_BASE, appid, appid);
    }

    private void syncToLocal(java.util.List<GameItem> items) {
        if (items == null || items.isEmpty()) return;
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        StringBuilder sb = new StringBuilder();
        for (GameItem it : items) {
            String appid = extractAppid(it.url);
            if (appid == null || appid.isEmpty()) continue;
            if (!seen.add(appid)) continue;
            if (sb.length() > 0) sb.append(";");
            sb.append(it.name).append("|").append(appid);
        }
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(KEY_SYNCED_DATA, sb.toString()).apply();
        try { com.tencent.tim.TokenStore.invalidateGamesCache(); } catch (Throwable ignored) {}
        addLog("[sync] cache overwritten: " + seen.size());
    }

    /** 诊断用：把响应裁成一行片段，日志里能直接看出服务端返回了什么。 */
    private static String preview(String s, int max) {
        if (s == null) return "null";
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private String extractAppid(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("client_id=([0-9]+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private void loadGameList() {
        SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        gameList.clear();
        String synced = p.getString(KEY_SYNCED_DATA, "");
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String line : synced.split(";")) {
            int i = line.indexOf("|");
            if (i <= 0) continue;
            String name = line.substring(0, i);
            String appid = line.substring(i + 1);
            if (!com.tencent.tim.TokenStore.isNumericAppid(appid)) continue;
            if (!seen.add(appid)) continue;
            gameList.add(new GameItem("", name, buildLocalUrl(appid)));
        }
        if (gameList.isEmpty()) {
            addLog("[local] 缓存为空 使用内置 AppID 列表");
            StringBuilder sb = new StringBuilder();
            for (String[] g : BUILTIN_GAMES) {
                if (!seen.add(g[1])) continue;
                gameList.add(new GameItem("", g[0], buildLocalUrl(g[1])));
                if (sb.length() > 0) sb.append(";");
                sb.append(g[0]).append("|").append(g[1]);
            }
            p.edit().putString(KEY_SYNCED_DATA, sb.toString()).apply();
            try { com.tencent.tim.TokenStore.invalidateGamesCache(); } catch (Throwable ignored) {}
        }
        addLog("[local] games: " + gameList.size());
        ensureValidSelection();
    }

    /** 保证「当前选中游戏」的 URL 里带的是合法数字 AppID，否则换成列表第一个。 */
    private void ensureValidSelection() {
        if (gameList.isEmpty()) return;
        SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String saved = p.getString(SELECTED_GAME_URL_KEY, "");
        if (!com.tencent.tim.TokenStore.sanitizeLoginUrl(saved).isEmpty()) {
            if (selectedGameName == null || selectedGameName.isEmpty()) {
                String name = com.tencent.tim.TokenStore.gameOfUrl(saved);
                if (name != null && !name.isEmpty()) {
                    saveSelectedGame(name, com.tencent.tim.TokenStore.sanitizeLoginUrl(saved));
                    return;
                }
            }
            return;
        }
        GameItem first = gameList.get(0);
        saveSelectedGame(first.name, first.url);
        addLog("[游戏] 已自动选择: " + first.name);
    }

    private void loadGameListFromServer() {
        addLog("[网络] 正在加载游戏列表...");
        new Thread(() -> {
            String response = "";
            for (int attempt = 1; attempt <= 2 && response.isEmpty(); attempt++) {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(GAME_LIST_URL).openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(6000);
                    connection.setReadTimeout(8000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                    connection.setRequestProperty("Accept", "*/*");

                    int code = connection.getResponseCode();
                    if (code == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();
                        response = sb.toString();
                        addLog("[网络] 游戏列表加载成功");
                    } else {
                        com.tencent.tim.Diag.fail("GameList", "loadGameListFromServer", "HTTP " + code + " 第" + attempt + "次");
                        addLog("[错误] 游戏列表 HTTP " + code + " (第" + attempt + "次)");
                    }
                    connection.disconnect();
                } catch (Exception e) {
                    com.tencent.tim.Diag.fail("GameList", "loadGameListFromServer", "第" + attempt + "次拉取失败: " + e, e);
                    addLog("[错误] 加载游戏列表失败(第" + attempt + "次): " + e.getMessage());
                    if (connection != null) {
                        try { connection.disconnect(); } catch (Throwable ignored) {}
                    }
                    try { Thread.sleep(800L); } catch (InterruptedException ignored) {}
                }
            }

            final String finalResponse = response;
            runOnUiThread(() -> parseGameData(finalResponse));
        }).start();
    }

    public void parseGameData(String data) {
        if (data == null || data.isEmpty()) {
            com.tencent.tim.Diag.fail("GameList", "parseGameData", "两次重试后响应仍为空，回退本地缓存");
            addLog("[游戏] 云端为空 回退本地");
            loadGameList();
            return;
        }

        Pattern pattern = Pattern.compile("图标：(.*?)；名称：(.*?)；链接：〖链接(.*?)〗；");
        Matcher matcher = pattern.matcher(data);
        java.util.List<GameItem> cloudParsed = new java.util.ArrayList<GameItem>();

        while (matcher.find()) {
            if (extractAppid(matcher.group(3).trim()) == null) continue;
            GameItem item = new GameItem(
                    matcher.group(1).trim(),
                    matcher.group(2).trim(),
                    matcher.group(3).trim()
            );
            cloudParsed.add(item);
        }

        // 兜底解析：服务端格式一变，上面的整齐正则就会一条都匹配不到。
        // 这里退化成「名称：X」与「client_id=N」按出现顺序配对，尽量别让列表变空。
        if (cloudParsed.isEmpty()) {
            java.util.List<String> names = new java.util.ArrayList<String>();
            Matcher nm = Pattern.compile("名称[：:]([^；;\\n]{1,40})").matcher(data);
            while (nm.find()) names.add(nm.group(1).trim());
            java.util.List<String> ids = new java.util.ArrayList<String>();
            Matcher im = Pattern.compile("client_id=([0-9]{1,12})(?![0-9])").matcher(data);
            while (im.find()) ids.add(im.group(1));
            int n = Math.min(names.size(), ids.size());
            for (int i = 0; i < n; i++) {
                cloudParsed.add(new GameItem("", names.get(i), buildLocalUrl(ids.get(i))));
            }
            if (n > 0) addLog("[游戏] 兼容解析: " + n);
        }

        if (cloudParsed.isEmpty()) {
            com.tencent.tim.Diag.fail("GameList", "parseGameData",
                    "主正则与兼容正则都解析不到游戏；响应长度=" + data.length() + " 前 200 字=" + preview(data, 200));
        }

        syncToLocal(cloudParsed);
        gameList.clear();
        java.util.HashSet<String> seenIds = new java.util.HashSet<String>();
        for (GameItem it : cloudParsed) {
            String aid = extractAppid(it.url);
            if (aid != null && !seenIds.add(aid)) continue;
            gameList.add(it);
        }
        addLog("[游戏] 加载到 " + gameList.size() + " 个游戏");
        addLog("[游戏] 云端解析: " + cloudParsed.size());

        if (gameList.isEmpty()) {
            com.tencent.tim.Diag.fail("GameList", "parseGameData", "云端解析出 " + cloudParsed.size() + " 条但没有可用 AppID，回退内置");
            addLog("[游戏] 云端没有可用 AppID 回退本地/内置");
            Toast.makeText(this, "云端列表不可用，已使用内置 AppID", Toast.LENGTH_SHORT).show();
            loadGameList();
            return;
        }
        ensureValidSelection();
    }

    public void hidePackage(String packageName) throws IOException {
        Process process = Runtime.getRuntime().exec("su");
        DataOutputStream out = new DataOutputStream(process.getOutputStream());
        out.writeBytes("pm hide " + packageName + "\n");
        out.writeBytes("exit\n");
        out.flush();
        out.close();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void unhidePackage(String packageName) throws IOException {
        Process process = Runtime.getRuntime().exec("su");
        DataOutputStream out = new DataOutputStream(process.getOutputStream());
        out.writeBytes("pm unhide " + packageName + "\n");
        out.writeBytes("exit\n");
        out.flush();
        out.close();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void startAgentActivity() {
        try {
            String mode;
            if (radioLoginMode.isChecked()) {
                mode = "login";
            } else if (radioCaptureMode.isChecked()) {
                mode = "capture";
            } else if (radioHangUpMode.isChecked()) {
                mode = "hang_up";
            } else {
                mode = "capture";
            }

            saveMode(mode);

            addLog("[启动] 启动上号界面，模式: " + mode);

            Intent intent = new Intent(this, AgentActivity.class);
            intent.putExtra("mode", mode);
            startActivityForResult(intent, REQUEST_AGENT_ACTIVITY);
        } catch (Exception e) {
            addLog("[错误] 启动失败 " + e.getMessage());
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 检查更新。
     *
     * 原实现只保留「最高的一个版本」（bestVer），所以用户只能看到最新一条，
     * 从 0.0.1 到 0.0.3 之间的版本完全看不到。
     * 现在收集所有高于当前版本的 Release，并在弹窗里逐条列出。
     */
    private void checkForUpdates() {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://api.github.com/repos/wsw0803/shq/releases?per_page=100");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                if (conn.getResponseCode() != 200) return;

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                String currentVersion = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName;

                org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
                java.util.List<ReleaseInfo> newer = new java.util.ArrayList<>();

                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject rel = arr.getJSONObject(i);
                    if (rel.optBoolean("draft", false) || rel.optBoolean("prerelease", false)) continue;

                    String tag = rel.optString("tag_name", "");
                    String ver = tag.startsWith("v") ? tag.substring(1) : tag;
                    if (ver.isEmpty()) continue;
                    if (compareVersion(ver, currentVersion) <= 0) continue;

                    org.json.JSONArray assets = rel.optJSONArray("assets");
                    if (assets == null || assets.length() == 0) continue;

                    String apkUrl = "";
                    long apkSize = 0;
                    for (int a = 0; a < assets.length(); a++) {
                        org.json.JSONObject as = assets.optJSONObject(a);
                        if (as == null) continue;
                        String nm = as.optString("name", "").toLowerCase();
                        String u2 = as.optString("browser_download_url", "");
                        if (u2.isEmpty()) continue;
                        if (nm.endsWith(".apk")) {
                            apkUrl = u2;
                            apkSize = as.optLong("size", 0);
                            break;
                        }
                        if (apkUrl.isEmpty()) {
                            apkUrl = u2;
                            apkSize = as.optLong("size", 0);
                        }
                    }
                    if (apkUrl.isEmpty()) continue;

                    ReleaseInfo info = new ReleaseInfo();
                    info.version = ver;
                    info.title = rel.optString("name", "");
                    if (info.title.isEmpty()) info.title = "shq v" + ver;
                    info.body = rel.optString("body", "");
                    info.apkUrl = apkUrl;
                    info.size = apkSize;
                    newer.add(info);
                }

                if (newer.isEmpty()) return;

                // 按版本号从新到旧排序，弹窗里最新在最上面
                java.util.Collections.sort(newer, (x, y) -> -compareVersion(x.version, y.version));

                final java.util.List<ReleaseInfo> list = newer;
                runOnUiThread(() -> showUpdateDialog(list));
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private static int parseIntSafe(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int compareVersion(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int va = i < x.length ? parseIntSafe(x[i]) : 0;
            int vb = i < y.length ? parseIntSafe(y[i]) : 0;
            if (va != vb) return va < vb ? -1 : 1;
        }
        return 0;
    }

    /**
     * 更新弹窗：列出所有可更新的版本（各自的 GitHub 标题），非强制。
     *
     * 用户点某一条即下载该版本；默认高亮最新版。
     */
    private void showUpdateDialog(java.util.List<ReleaseInfo> releases) {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dlg.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.86f),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }

        float density = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackground(com.tencent.tim.TokenDialog.glass(this, 22, true));
        int p = (int)(16 * density);
        root.setPadding(p, p, p, p);

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("发现 " + releases.size() + " 个新版本");
        title.setTextColor(0xFFF5F7FA);
        title.setTextSize(17);
        title.setGravity(android.view.Gravity.CENTER);
        root.addView(title);

        android.widget.TextView tip = new android.widget.TextView(this);
        tip.setText("可以立即更新，也可以稍后再更新，不影响当前使用");
        tip.setTextColor(0xFF9CA3AF);
        tip.setTextSize(12);
        tip.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams tipLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = (int)(8 * density);
        tipLp.bottomMargin = (int)(12 * density);
        tip.setLayoutParams(tipLp);
        root.addView(tip);

        // 版本列表：每条显示 GitHub Release 的标题 + 版本号
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);

        final ReleaseInfo[] chosen = new ReleaseInfo[]{ releases.get(0) };

        for (int i = 0; i < releases.size(); i++) {
            final int index = i;
            final ReleaseInfo info = releases.get(i);

            android.widget.TextView row = new android.widget.TextView(this);
            String label = info.title;
            if (!label.contains(info.version)) {
                label = label + "  (" + info.version + ")";
            }
            if (i == 0) label = "★ " + label;
            row.setText(label);
            row.setTextSize(14);
            row.setTextColor(i == 0 ? 0xFF22D3EE : 0xFFD5DBDB);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding((int)(10 * density), (int)(10 * density),
                    (int)(10 * density), (int)(10 * density));
            row.setBackground(com.tencent.tim.TokenDialog.glass(this, 12, false));

            android.widget.LinearLayout.LayoutParams rowLp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = (int)(6 * density);
            row.setLayoutParams(rowLp);

            row.setOnClickListener(v -> {
                chosen[0] = info;
                for (int k = 0; k < list.getChildCount(); k++) {
                    android.view.View c = list.getChildAt(k);
                    if (c instanceof android.widget.TextView) {
                        ((android.widget.TextView) c).setTextColor(k == index ? 0xFF22D3EE : 0xFFD5DBDB);
                    }
                }
            });
            list.addView(row);
        }

        scroll.addView(list);
        android.widget.LinearLayout.LayoutParams scrollLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.bottomMargin = (int)(12 * density);
        scroll.setLayoutParams(scrollLp);
        root.addView(scroll, scrollLp);

        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);

        android.widget.Button btnLater = new android.widget.Button(this);
        btnLater.setText("以后再说");
        btnLater.setTextColor(0xFF9CA3AF);
        btnLater.setBackground(com.tencent.tim.TokenDialog.glass(this, 12, false));
        btnLater.setOnClickListener(v -> dlg.dismiss());
        android.widget.LinearLayout.LayoutParams lp1 = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp1.rightMargin = (int)(6 * density);
        btnRow.addView(btnLater, lp1);

        android.widget.Button btnOk = new android.widget.Button(this);
        btnOk.setText("更新所选版本");
        btnOk.setTextColor(0xFF000000);
        btnOk.setBackground(com.tencent.tim.TokenDialog.glass(this, 12, true));
        btnOk.setOnClickListener(v -> {
            final ReleaseInfo target = chosen[0];
            dlg.dismiss();
            Updater.start(MainActivity.this, target.apkUrl, target.size,
                    () -> runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "更新失败，请稍后重试", Toast.LENGTH_SHORT).show();
                    }));
        });
        android.widget.LinearLayout.LayoutParams lp2 = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp2.leftMargin = (int)(6 * density);
        btnRow.addView(btnOk, lp2);

        root.addView(btnRow);
        dlg.setContentView(root);
        // 允许返回键 / 点击外部关闭，不强制更新
        dlg.setCancelable(true);
        dlg.setCanceledOnTouchOutside(true);
        dlg.show();
        Ui.applyDialog(dlg);
    }
}
