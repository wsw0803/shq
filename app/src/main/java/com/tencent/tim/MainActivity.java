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
    
    private static final int REQUEST_CODE_OPEN_DIRECTORY = 1;
    private static final int REQUEST_STORAGE_PERMISSION = 200;
    private static final int REQUEST_MANAGE_STORAGE = 300;
    private static final int REQUEST_AGENT_ACTIVITY = 400;
    private static final int REQUEST_CODE_IMPORT = 1002;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 设置沉浸式效果（保留状态栏）
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
    
    /**
 * 设置沉浸式效果（保留状态栏）
 */
private void setupImmersiveMode() {
    mWindow = getWindow();
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        // 只设置内容延伸，不隐藏状态栏
        mWindow.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
    
    // 设置状态栏颜色（不透明）
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        mWindow.setStatusBarColor(Color.parseColor("#0A0F1A"));
        mWindow.setNavigationBarColor(Color.parseColor("#0A0F1A"));
    }
}
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mWindow != null) {
            // 保持沉浸式效果
            mWindow.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }
    
    private void addLog(final String log) {
        runOnUiThread(() -> {
            String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
            String msg = log;
            if (msg != null) {
                msg = replaceToken(msg, "\"access_token\":\"");
                msg = replaceToken(msg, "\"openid\":\"");
                msg = replaceToken(msg, "\"pay_token\":\"");
                msg = replaceToken(msg, "\"openkey\":\"");
                msg = replaceToken(msg, "access_token=");
                msg = replaceToken(msg, "openid=");
            }
            String newLog = "[" + time + "] " + msg + "\n";
            if (tvLog != null) tvLog.append(newLog);
            if (scrollLog != null) scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    private String replaceToken(String s, String prefix) {
        int start = 0;
        while ((start = s.indexOf(prefix, start)) != -1) {
            int valStart = start + prefix.length();
            int valEnd = s.indexOf("\"", valStart);
            if (valEnd == -1) valEnd = s.indexOf("&", valStart);
            if (valEnd == -1) valEnd = s.indexOf(" ", valStart);
            if (valEnd != -1) {
                s = s.substring(0, valStart) + "****" + s.substring(valEnd);
                start = valStart + 4;
            } else {
                break;
            }
        }
        return s;
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
                TokenStore.ImportResult res = TokenStore.importFromUri(this, uri);
                addLog("[导入] 新增 " + res.added + " 条，已存在 " + res.existing + " 条，跳过 " + res.skipped + " 条，待定 " + res.unknownLines.size() + " 条" + (TokenStore.lastError.isEmpty() ? "" : ("，错误: " + TokenStore.lastError)));
                if (!res.unknownLines.isEmpty()) {
                    TokenDialog.showChooseGameForTokens(this, res.unknownLines, res.added);
                } else {
                    Toast.makeText(this, res.added > 0 ? ("已导入 " + res.added + " 条新记录" + (res.existing > 0 ? ("，已存在 " + res.existing + " 条") : "") + (res.skipped > 0 ? ("，跳过 " + res.skipped + " 条无效") : "")) : (res.skipped > 0 ? ("未发现新记录（跳过 " + res.skipped + " 条重复/无效）") : "未发现新记录"), Toast.LENGTH_LONG).show();
                    TokenDialog.show(this, "", null);
                }
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
            Toast.makeText(this, "正在加载游戏列表，请稍后...", Toast.LENGTH_SHORT).show();
            loadGameList();
            return;
        }
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dlg.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.8f), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackground(com.tencent.tim.TokenDialog.glass(this, 22, true));
        int p = (int)(16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("选择游戏");
        title.setTextColor(0xFFF5F7FA);
        title.setTextSize(17);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, (int)(10 * getResources().getDisplayMetrics().density));
        root.addView(title);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout colA = new android.widget.LinearLayout(this);
        colA.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout colB = new android.widget.LinearLayout(this);
        colB.setOrientation(android.widget.LinearLayout.VERTICAL);
        list.addView(colA, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        list.addView(colB, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        scroll.addView(list);
        root.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        for (int i = 0; i < gameList.size(); i++) {
            final int index = i;
            GameItem item = gameList.get(i);
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText(item.name);
            tv.setTextColor(0xFF22D3EE);
            tv.setTextSize(14);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setPadding(0, (int)(12 * getResources().getDisplayMetrics().density), 0, (int)(12 * getResources().getDisplayMetrics().density));
            tv.setBackground(com.tencent.tim.TokenDialog.glass(this, 12, false));
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int)(8 * getResources().getDisplayMetrics().density);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> {
                try {
                    GameItem selected = gameList.get(index);
                    if (selected != null && selected.name != null && selected.url != null) {
                        saveSelectedGame(selected.name, selected.url);
                        dlg.dismiss();
                    } else {
                        addLog("[错误] 游戏数据错误");
                        Toast.makeText(this, "游戏数据错误", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    addLog("[错误] 选择游戏失败: " + e.getMessage());
                }
            });
            if (index % 2 == 0) colA.addView(tv); else colB.addView(tv);
        }
        dlg.setContentView(root);
        dlg.show();
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
            if (!seen.add(appid)) continue;
            gameList.add(new GameItem("", name, buildLocalUrl(appid)));
        }
        addLog("[local] games: " + gameList.size());
        if (!gameList.isEmpty() && selectedGameName.isEmpty()) {
            saveSelectedGame(gameList.get(0).name, gameList.get(0).url);
        }
    }

    private void loadGameListFromServer() {
        addLog("[网络] 正在加载游戏列表...");
        new Thread(() -> {
            String response = "";
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(GAME_LIST_URL).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                response = sb.toString();
                connection.disconnect();
                addLog("[网络] 游戏列表加载成功");
            } catch (Exception e) {
                addLog("[错误] 加载游戏列表失败: " + e.getMessage());
            }

            final String finalResponse = response;
            runOnUiThread(() -> parseGameData(finalResponse));
        }).start();
    }

    public void parseGameData(String data) {
        if (data == null || data.isEmpty()) {
            addLog("[游戏] 云端为空 回退本地");
            loadGameList();
            return;
        }

        Pattern pattern = Pattern.compile("图标：(.*?)；名称：(.*?)；链接：〖链接(.*?)〗；");
        Matcher matcher = pattern.matcher(data); java.util.List<GameItem> cloudParsed = new java.util.ArrayList<GameItem>();

        while (matcher.find()) {
            if (extractAppid(matcher.group(3).trim()) == null) continue;
            GameItem item = new GameItem(
                    matcher.group(1).trim(),
                    matcher.group(2).trim(),
                    matcher.group(3).trim()
            );
            cloudParsed.add(item);
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

        if (!gameList.isEmpty() && selectedGameName.isEmpty()) {
            GameItem first = gameList.get(0);
            saveSelectedGame(first.name, first.url);
        }

        if (gameList.isEmpty()) {
            Toast.makeText(this, "未找到游戏数据", Toast.LENGTH_SHORT).show();
        }
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
    private void checkForUpdates() {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL("https://api.github.com/repos/wsw0803/shq/releases?per_page=100");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
                    String bestVer = "";
                    String bestUrl = "";
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject rel = arr.getJSONObject(i);
                        if (rel.optBoolean("draft", false) || rel.optBoolean("prerelease", false)) continue;
                        String tag = rel.optString("tag_name", "");
                        String ver = tag.startsWith("v") ? tag.substring(1) : tag;
                        if (ver.isEmpty()) continue;
                        if (!bestVer.isEmpty() && compareVersion(ver, bestVer) <= 0) continue;
                        org.json.JSONArray assets = rel.optJSONArray("assets");
                        if (assets == null || assets.length() == 0) continue;
                        String apkUrl = assets.getJSONObject(0).optString("browser_download_url", "");
                        if (apkUrl.isEmpty()) continue;
                        bestVer = ver;
                        bestUrl = apkUrl;
                    }
                    String currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                    if (!bestVer.isEmpty() && compareVersion(bestVer, currentVersion) > 0) {
                        final String fv = bestVer;
                        final String fu = bestUrl;
                        runOnUiThread(() -> showUpdateDialog(fv, fu));
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static int parseIntSafe(String v) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return 0; }
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

    private void showUpdateDialog(String newVersion, String url) {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dlg.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels * 0.8f), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackground(com.tencent.tim.TokenDialog.glass(this, 22, true));
        int p = (int)(16 * getResources().getDisplayMetrics().density);
        root.setPadding(p, p, p, p);
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("发现新版本 " + newVersion);
        title.setTextColor(0xFFF5F7FA);
        title.setTextSize(17);
        title.setGravity(android.view.Gravity.CENTER);
        root.addView(title);
        android.widget.TextView tip = new android.widget.TextView(this);
        tip.setText("此版本为强制更新，必须更新后才能继续使用");
        tip.setTextColor(0xFF9CA3AF);
        tip.setTextSize(13);
        tip.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int)(10 * getResources().getDisplayMetrics().density);
        lp.bottomMargin = (int)(16 * getResources().getDisplayMetrics().density);
        tip.setLayoutParams(lp);
        root.addView(tip);
        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.Button btnOk = new android.widget.Button(this);
        btnOk.setText("立即更新");
        btnOk.setTextColor(0xFF000000);
        btnOk.setBackground(com.tencent.tim.TokenDialog.glass(this, 12, true));
        btnOk.setOnClickListener(v -> {
            dlg.dismiss();
            Updater.start(MainActivity.this, url, () -> runOnUiThread(() -> showUpdateDialog(newVersion, url)));
        });
        android.widget.LinearLayout.LayoutParams lp2 = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp2.leftMargin = (int)(6 * getResources().getDisplayMetrics().density);
        btnRow.addView(btnOk, lp2);
        root.addView(btnRow);
        dlg.setContentView(root);
        dlg.setCancelable(false);
        dlg.setCanceledOnTouchOutside(false);
        dlg.show();
    }
}
