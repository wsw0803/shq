package com.tencent.tim;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * 设置页。
 *
 * 本次修复（2026-10-25）：
 *
 * 1) 不再被系统当成「开机后的入口」。
 *    之前重启手机后从桌面点图标会直接落在设置页：系统把任务栈原样恢复，
 *    而栈顶恰好是设置页，点图标只是把旧任务栈提到前台。
 *    现在两道保险：
 *      - Manifest 给 MainActivity 加了 clearTaskOnLaunch，从桌面进入一律清到主页；
 *      - 这里再兜一层：如果本页被单独恢复成任务根（栈底没有主页），
 *        直接跳回主页并结束自己。正常从主页点进来时 isTaskRoot() 为 false，不受影响。
 *
 * 2) 当前主题按钮会标注「当前」，不用退出去再猜现在是哪个主题。
 */
public class SettingsActivity extends BaseActivity {

    private static final int REQ_PICK_BG = 1001;
    private static final String BG_FILE = "bg_custom.img";

    private TextView tvDpi, tvGlass, tvBright;
    private SeekBar seekDpi, seekGlass, seekBright;
    private TextView tvDiagFiles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 被系统恢复成任务栈根界面 = 不是从主页正常进来的，直接回主页
        if (isTaskRoot()) {
            Intent home = new Intent(this, MainActivity.class);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(home);
            finish();
            return;
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Button back = findViewById(R.id.btn_back);
        Button reset = findViewById(R.id.btn_reset);
        tvDpi = findViewById(R.id.tv_dpi);
        tvGlass = findViewById(R.id.tv_glass);
        tvBright = findViewById(R.id.tv_bright);
        seekDpi = findViewById(R.id.seek_dpi);
        seekGlass = findViewById(R.id.seek_glass);
        seekBright = findViewById(R.id.seek_bright);

        SharedPreferences p = Ui.prefs(this);
        seekDpi.setProgress(p.getInt(Ui.KEY_DPI, 100) - 50);
        seekGlass.setProgress(p.getInt(Ui.KEY_GLASS, 40));
        seekBright.setProgress(p.getInt(Ui.KEY_BRIGHT, 70));
        labels();

        Button pickBg = findViewById(R.id.btn_pick_bg);
        Button clearBg = findViewById(R.id.btn_clear_bg);
        pickBg.setOnClickListener(v -> {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            it.setType("image/*");
            it.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(it, REQ_PICK_BG);
        });
        clearBg.setOnClickListener(v -> {
            Ui.prefs(this).edit().remove(Ui.KEY_BG_URI).apply();
            deleteCustomBg();
            Ui.apply(this);
        });

        setupProxyButton();
        setupDiag();

        seekDpi.setOnSeekBarChangeListener(new L() {
            @Override
            public void onProgressChanged(SeekBar sb, int v, boolean u) {
                tvDpi.setText("DPI: " + (v + 50) + "%");
            }
        });
        seekGlass.setOnSeekBarChangeListener(new L() {
            @Override
            public void onProgressChanged(SeekBar sb, int v, boolean u) {
                Ui.prefs(SettingsActivity.this).edit().putInt(Ui.KEY_GLASS, v).apply();
                labels();
                Ui.apply(SettingsActivity.this);
            }
        });
        seekBright.setOnSeekBarChangeListener(new L() {
            @Override
            public void onProgressChanged(SeekBar sb, int v, boolean u) {
                Ui.prefs(SettingsActivity.this).edit().putInt(Ui.KEY_BRIGHT, v).apply();
                labels();
                Ui.apply(SettingsActivity.this);
            }
        });

        int[] thIds = {R.id.th_dark, R.id.th_light, R.id.th_auto};
        for (int i = 0; i < thIds.length; i++) {
            final int v = i;
            Button b = findViewById(thIds[i]);
            b.setOnClickListener(x -> {
                Ui.prefs(SettingsActivity.this).edit().putInt(Ui.KEY_THEME, v).apply();
                recreate();
            });
        }
        markThemeButtons();

        back.setOnClickListener(v -> finish());
        reset.setOnClickListener(v -> {
            Ui.prefs(this).edit()
                    .putInt(Ui.KEY_DPI, 100)
                    .putInt(Ui.KEY_GLASS, 40)
                    .putInt(Ui.KEY_BRIGHT, 70)
                    .putInt(Ui.KEY_BG, 0)
                    .apply();
            seekDpi.setProgress(50);
            seekGlass.setProgress(40);
            seekBright.setProgress(70);
            labels();
            Ui.apply(this);
        });
    }

    /** 让当前主题按钮一眼看出来。 */
    private void markThemeButtons() {
        int cur = Ui.prefs(this).getInt(Ui.KEY_THEME, 0);
        int[] thIds = {R.id.th_dark, R.id.th_light, R.id.th_auto};
        for (int i = 0; i < thIds.length; i++) {
            Button b = findViewById(thIds[i]);
            if (b == null) continue;
            String base = stripCurrent(String.valueOf(b.getText()));
            b.setText(i == cur ? (base + " · 当前") : base);
        }
    }

    private String stripCurrent(String s) {
        int i = s.indexOf(" · 当前");
        return i < 0 ? s : s.substring(0, i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDiagFiles();
    }

    private void setupDiag() {
        final Switch sw = findViewById(R.id.sw_diag);
        tvDiagFiles = findViewById(R.id.tv_diag_files);
        if (sw == null) return;
        sw.setChecked(LoginDiag.isEnabled(this));
        sw.setOnCheckedChangeListener((btn, on) -> {
            LoginDiag.setEnabled(this, on);
            if (on) {
                android.widget.Toast.makeText(this, "已开启，操作完成后关闭即可自动保存日志",
                        android.widget.Toast.LENGTH_LONG).show();
            } else {
                android.widget.Toast.makeText(this, "已关闭，本次日志已自动保存",
                        android.widget.Toast.LENGTH_LONG).show();
                btn.postDelayed(this::refreshDiagFiles, 800);
            }
        });
        // 长按日志开关切换「全局抓取」：完全不过滤，原始行全留。
        // 不加新控件，避免改布局；排障时才需要，藏在这里够用。
        sw.setOnLongClickListener(v -> {
            boolean now = !LoginDiag.isRawAll(this);
            LoginDiag.setRawAll(this, now);
            android.widget.Toast.makeText(this,
                    now ? "已开启全局抓取：不再过滤，所有日志原样保留"
                        : "已关闭全局抓取：恢复白名单过滤",
                    android.widget.Toast.LENGTH_LONG).show();
            refreshDiagFiles();
            return true;
        });

        Button export = findViewById(R.id.btn_diag_export);
        if (export != null) {
            export.setOnClickListener(v -> showDiagList());
        }
        Button clear = findViewById(R.id.btn_diag_clear);
        if (clear != null) {
            clear.setOnClickListener(v -> confirmDeleteAll());
        }
        refreshDiagFiles();
    }

    private void refreshDiagFiles() {
        if (tvDiagFiles == null) return;

        // App 进程的日志（LoginDiag，静态内存 + 落盘）
        List<File> all = LoginDiag.listLogs(this);
        String appPart;
        if (all.isEmpty()) {
            appPart = "应用进程: 无";
        } else {
            File latest = all.get(0);
            appPart = "应用进程: " + all.size() + " 份，最新 " + latest.getName();
        }

        // 游戏进程的日志（XposedEntry 经 Diag 写到游戏自己的 files 目录）
        // 这是本轮重构的重点：以前靠导出整个 logcat，4MB 里几乎没有有效行，
        // 还受 LSPosed 日志级别影响。现在直接读文件，条数一眼可见。
        String pkg = com.tencent.tim.GameLaunch.packageOf(getSelectedGameForDiag());
        int inproc = com.tencent.tim.core.ShqXposedBridge
                .readInprocLog(pkg, 1).size();
        String inprocPart;
        if (pkg == null || pkg.isEmpty()) {
            inprocPart = "游戏进程: 未选游戏";
        } else if (inproc > 0) {
            inprocPart = "游戏进程: 有日志 ✓";
        } else {
            inprocPart = "游戏进程: 空（未注入或未运行）";
        }

        String modePart = LoginDiag.isRawAll(this)
                ? "模式: 全局抓取（不过滤）"
                : "模式: 白名单过滤";
        tvDiagFiles.setText(appPart + "\n" + inprocPart + "\n" + modePart);
    }

    /** 取当前用于诊断的游戏名，供上面拼包名。 */
    private String getSelectedGameForDiag() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("myPrefs", MODE_PRIVATE);
            // key 与 AgentActivity.getSelectedGameName() 保持一致，
            // 是 selected_game_name 不是 selected_game，写错了会永远读到空。
            String g = sp.getString("selected_game_name", "");
            if (g == null || "未知游戏".equals(g)) {
                return "";
            }
            return g;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 日志列表：点选复制内容，长按删除该条。
     */
    private void showDiagList() {
        final List<File> all = LoginDiag.listLogs(this);
        if (all.isEmpty()) {
            android.widget.Toast.makeText(this,
                    "还没有日志。开启开关、操作一次、再关闭即可自动生成",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            File f = all.get(i);
            long kb = Math.max(1, f.length() / 1024);
            items[i] = f.getName() + " (" + kb + " KB)";
        }
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("诊断日志（点选复制 / 长按删除）")
                .setItems(items, (d, which) -> {
                    File f = all.get(which);
                    String content = LoginDiag.readFile(f);
                    if (content.isEmpty()) {
                        android.widget.Toast.makeText(this, "读取失败", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", content));
                    } catch (Throwable ignored) {
                    }
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(f.getName())
                            .setMessage("路径:\n" + f.getAbsolutePath()
                                    + "\n\n内容已复制到剪贴板，可直接粘贴发送。")
                            .setPositiveButton("知道了", null)
                            .show();
                })
                .setNegativeButton("关闭", null)
                .create();

        dlg.setOnShowListener(d -> {
            android.widget.ListView lv = dlg.getListView();
            if (lv == null) return;
            lv.setOnItemLongClickListener((parent, view, position, id) -> {
                final File target = all.get(position);
                new android.app.AlertDialog.Builder(this)
                        .setTitle("删除日志")
                        .setMessage("确定删除 " + target.getName() + " ？")
                        .setPositiveButton("删除", (dd, ww) -> {
                            boolean ok = LoginDiag.deleteLog(target);
                            android.widget.Toast.makeText(this, ok ? "已删除" : "删除失败",
                                    android.widget.Toast.LENGTH_SHORT).show();
                            dlg.dismiss();
                            refreshDiagFiles();
                            showDiagList();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            });
        });
        dlg.show();
    }

    private void confirmDeleteAll() {
        final List<File> all = LoginDiag.listLogs(this);
        if (all.isEmpty()) {
            android.widget.Toast.makeText(this, "没有可删除的日志", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("清空全部日志")
                .setMessage("将删除 " + all.size() + " 份日志文件，且不可恢复。\n当前正在记录的缓冲也会清空。")
                .setPositiveButton("全部删除", (d, w) -> {
                    int n = LoginDiag.deleteAllLogs(this);
                    LoginDiag.clear();
                    android.widget.Toast.makeText(this, "已删除 " + n + " 份",
                            android.widget.Toast.LENGTH_SHORT).show();
                    refreshDiagFiles();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setupProxyButton() {
        Button btn = findViewById(R.id.btn_proxy);
        if (btn == null) return;
        updateProxyLabel(btn);
        btn.setOnClickListener(v -> showProxyDialog(btn));
    }

    private void updateProxyLabel(Button btn) {
        String cur = Net.getManualProxy(this);
        btn.setText(cur.isEmpty() ? "更新代理: 未设置" : ("更新代理: " + cur));
    }

    private void showProxyDialog(final Button label) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(Net.getManualProxy(this));
        input.setHint("例如 127.0.0.1:7890");
        input.setSingleLine(true);
        // 输入框自己定色：AlertDialog 跟系统主题走，不一定浅不一定深
        input.setTextColor(Ui.isLightMode(this) ? 0xFF121C2A : 0xFFE6EEF8);
        input.setHintTextColor(0xFF8A9BB8);
        new android.app.AlertDialog.Builder(this)
                .setTitle("更新下载代理")
                .setMessage("填写本地 SOCKS 代理 host:port，用于下载 xray 核心与更新包。\n留空表示不使用。")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    Net.setManualProxy(this, input.getText().toString());
                    updateProxyLabel(label);
                    android.widget.Toast.makeText(this,
                            "已保存，当前线路: " + Net.routeName(this),
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_BG && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            if (copyToPrivate(uri)) {
                Ui.prefs(this).edit()
                        .putString(Ui.KEY_BG_URI, Ui.localBgPath(this))
                        .apply();
            } else {
                Ui.prefs(this).edit().putString(Ui.KEY_BG_URI, uri.toString()).apply();
                android.widget.Toast.makeText(this,
                        "背景已设置（未能复制到本地，重启后可能失效）",
                        android.widget.Toast.LENGTH_LONG).show();
            }
            Ui.apply(this);
        }
    }

    private boolean copyToPrivate(Uri src) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = getContentResolver().openInputStream(src);
            if (in == null) return false;
            File dst = new File(getFilesDir(), BG_FILE);
            out = new FileOutputStream(dst, false);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return dst.length() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void deleteCustomBg() {
        try {
            File f = new File(getFilesDir(), BG_FILE);
            if (f.exists()) f.delete();
        } catch (Exception ignored) {
        }
    }

    private void labels() {
        SharedPreferences p = Ui.prefs(this);
        tvDpi.setText("DPI: " + p.getInt(Ui.KEY_DPI, 100) + "%");
        tvGlass.setText("玻璃透明度: " + p.getInt(Ui.KEY_GLASS, 40) + "%");
        tvBright.setText("亮度: " + p.getInt(Ui.KEY_BRIGHT, 70) + "%");
    }

    private abstract class L implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar sb) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar sb) {
            if (sb.getId() == R.id.seek_dpi) {
                Ui.prefs(SettingsActivity.this).edit()
                        .putInt(Ui.KEY_DPI, sb.getProgress() + 50).apply();
                Ui.applyDpi(SettingsActivity.this);
            }
        }
    }
}
