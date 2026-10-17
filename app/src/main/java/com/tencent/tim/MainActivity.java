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
import android.widget.CheckBox;
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
    private static final String KEY_LOCAL_ON = "local_on";
    private static final String KEY_CLOUD_ON = "cloud_on";
    private static final String LOCAL_GAME_DATA = "和平|1106467070;王者|1104466820;三角洲|1110543085;无畏契约|1111677210";
    private static final String KEY_STORAGE_URI = "storage_uri";
    
    private static final int REQUEST_CODE_OPEN_DIRECTORY = 1;
    private static final int REQUEST_STORAGE_PERMISSION = 200;
    private static final int REQUEST_MANAGE_STORAGE = 300;
    private static final int REQUEST_AGENT_ACTIVITY = 400;

    private RadioGroup modeRadioGroup;
    private RadioButton radioCaptureMode;
    private RadioButton radioLoginMode;
    private RadioButton radioHangUpMode;
    private Button selectGameButton;
    private Button startButton;
    private Button hideButton;
    private Button showButton;
    private Button selectDirectoryButton;
    private TextView directoryStatusText;
    private TextView storagePermissionStatusText;
    private TextView tvLog;
    private ScrollView scrollLog;
    private CheckBox cbLocalSource;
    private CheckBox cbCloudSource;

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
        
        checkAndRequestAllPermissions();
        
        loadGameList();
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
            String newLog = "[" + time + "] " + log + "\n";
            tvLog.append(newLog);
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }
    
    private void initViews() {
        modeRadioGroup = findViewById(R.id.mode_radio_group);
        radioCaptureMode = findViewById(R.id.radio_capture_mode);
        radioLoginMode = findViewById(R.id.radio_login_mode);
        radioHangUpMode = findViewById(R.id.radio_hang_up_mode);
        selectGameButton = findViewById(R.id.btn_select_game);
        startButton = findViewById(R.id.btn_start);
        hideButton = findViewById(R.id.btn_hide);
        showButton = findViewById(R.id.btn_show);
        selectDirectoryButton = findViewById(R.id.btn_select_directory);
        cbLocalSource = findViewById(R.id.cb_local_source);
        cbCloudSource = findViewById(R.id.cb_cloud_source);
        setupSourceToggles();
        directoryStatusText = findViewById(R.id.txt_directory_status);
        storagePermissionStatusText = findViewById(R.id.txt_storage_permission_status);
        tvLog = findViewById(R.id.tv_log);
        scrollLog = findViewById(R.id.scroll_log);

        updateSelectGameButtonText();
        updateStorageStatus();
        android.widget.Button viewToken = findViewById(R.id.btn_view_token);
        if (viewToken != null) {
            viewToken.setOnClickListener(v -> TokenDialog.show(this, selectedGameName, null));
        }
        android.widget.Button sb = findViewById(R.id.btn_settings);
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
            for (String g : TokenStore.GAMES) {
                File gd = TokenStore.gameDir(g);
                if (!gd.exists()) gd.mkdirs();
            }
            addLog("[文件] 已按游戏分目录: 和平 / 王者 / 三角洲 / 无畏契约");
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
                DocumentFile dataDir = rootDir.findFile("维/保存数据");
                if (dataDir == null) {
                    dataDir = rootDir.createDirectory("维/保存数据");
                    addLog("[SAF] 创建目录: 维/保存数据");
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

        String[] gameNames = new String[gameList.size()];
        for (int i = 0; i < gameList.size(); i++) {
            gameNames[i] = gameList.get(i).name;
        }

        new AlertDialog.Builder(this)
                .setTitle("选择游戏")
                .setItems(gameNames, (dialog, which) -> {
                    try {
                        GameItem selected = gameList.get(which);
                        if (selected != null && selected.name != null && selected.url != null) {
                            saveSelectedGame(selected.name, selected.url);
                        } else {
                            addLog("[错误] 游戏数据错误");
                            Toast.makeText(this, "游戏数据错误", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        addLog("[错误] 选择游戏失败: " + e.getMessage());
                    }
                })
                .show();
    }

    private void setupSourceToggles() {
        SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        cbLocalSource.setChecked(p.getBoolean(KEY_LOCAL_ON, true));
        cbCloudSource.setChecked(p.getBoolean(KEY_CLOUD_ON, false));
        cbLocalSource.setOnCheckedChangeListener((v, c) -> {
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(KEY_LOCAL_ON, c).apply();
            onSourceToggled();
        });
        cbCloudSource.setOnCheckedChangeListener((v, c) -> {
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(KEY_CLOUD_ON, c).apply();
            onSourceToggled();
        });
    }

    private void onSourceToggled() {
        SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean localOn = p.getBoolean(KEY_LOCAL_ON, true);
        boolean cloudOn = p.getBoolean(KEY_CLOUD_ON, false);
        if (!localOn && !cloudOn) {
            p.edit().remove(SELECTED_GAME_NAME_KEY).remove(SELECTED_GAME_URL_KEY).remove(KEY_SYNCED_DATA).apply();
            selectedGameName = "";
            selectedGameUrl = "";
            gameList.clear();
            updateSelectGameButtonText();
            addLog("[source] 两个开关已关闭，缓存已清空");
        }
        loadGameList();
    }

    private String buildLocalUrl(String appid) {
        return String.format(PTLOGIN_BASE, appid, appid);
    }

    private void syncToLocal(java.util.List<GameItem> items) {
        if (items == null || items.isEmpty()) return;
        SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String old = p.getString(KEY_SYNCED_DATA, "");
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        StringBuilder sb = new StringBuilder();
        for (String line : old.split(";")) {
            int i = line.indexOf("|");
            if (i <= 0) continue;
            String appid = line.substring(i + 1);
            if (seen.add(appid)) {
                if (sb.length() > 0) sb.append(";");
                sb.append(line);
            }
        }
        for (GameItem it : items) {
            String appid = extractAppid(it.url);
            if (appid == null || appid.isEmpty()) continue;
            if (seen.add(appid)) {
                if (sb.length() > 0) sb.append(";");
                sb.append(it.name).append("|").append(appid);
            }
        }
        p.edit().putString(KEY_SYNCED_DATA, sb.toString()).apply();
        addLog("[sync] cloud appid saved: " + items.size());
    }

    private String extractAppid(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("client_id=([0-9]+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private void loadGameList() {
        SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean localOn = p.getBoolean(KEY_LOCAL_ON, true);
        boolean cloudOn = p.getBoolean(KEY_CLOUD_ON, false);
        gameList.clear();
        if (localOn) {
            String synced = p.getString(KEY_SYNCED_DATA, "");
            String all = LOCAL_GAME_DATA + (synced.isEmpty() ? "" : ";" + synced);
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (String line : all.split(";")) {
                int i = line.indexOf("|");
                if (i <= 0) continue;
                String name = line.substring(0, i);
                String appid = line.substring(i + 1);
                if (!seen.add(appid)) continue;
                gameList.add(new GameItem("", name, buildLocalUrl(appid)));
            }
            addLog("[local] games: " + gameList.size());
        }
        if (cloudOn) {
            loadGameListFromServer();
        } else {
            if (!gameList.isEmpty() && selectedGameName.isEmpty()) {
                saveSelectedGame(gameList.get(0).name, gameList.get(0).url);
            }
            if (gameList.isEmpty()) {
                addLog("[game] no data source");
            }
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

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
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
            addLog("[游戏] 列表为空");
            return;
        }

        Pattern pattern = Pattern.compile("图标：(.*?)；名称：(.*?)；链接：〖链接(.*?)〗；");
        Matcher matcher = pattern.matcher(data);

        while (matcher.find()) {
            if (!matcher.group(3).trim().startsWith("https://xui.ptlogin2.qq.com/")) continue;
            GameItem item = new GameItem(
                    matcher.group(1).trim(),
                    matcher.group(2).trim(),
                    matcher.group(3).trim()
            );
            gameList.add(item);
        }

        syncToLocal(gameList);
        addLog("[游戏] 加载到 " + gameList.size() + " 个游戏");

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
}