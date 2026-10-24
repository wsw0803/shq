package com.tencent.tim;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class SettingsActivity extends BaseActivity {

    private static final int REQ_PICK_BG = 1001;
    /** 背景图落到应用私有目录，避免依赖外部 URI 的持久化授权。 */
    private static final String BG_FILE = "bg_custom.img";

    private TextView tvDpi, tvGlass, tvBright;
    private SeekBar seekDpi, seekGlass, seekBright;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
            // ACTION_GET_CONTENT 拿到的 URI 无法持久化授权，
            // 重启或升级后就读不到，背景会「消失」。
            // 改用 ACTION_OPEN_DOCUMENT，并把图片复制进私有目录，
            // 之后不再依赖外部授权。
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

        seekDpi.setOnSeekBarChangeListener(new L() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean u) {
                tvDpi.setText("DPI: " + (v + 50) + "%");
            }
        });
        seekGlass.setOnSeekBarChangeListener(new L() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean u) {
                Ui.prefs(SettingsActivity.this).edit().putInt(Ui.KEY_GLASS, v).apply();
                labels();
                Ui.apply(SettingsActivity.this);
            }
        });
        seekBright.setOnSeekBarChangeListener(new L() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean u) {
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
                Ui.prefs(this).edit().putInt(Ui.KEY_THEME, v).apply();
                recreate();
            });
        }

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

    /**
     * 手动代理设置。
     *
     * 存在的意义：首次使用时代理核心还没下载下来，
     * 而核心本身也要从网上下载，形成死锁。
     * 用户若已有可用的本地代理（如 Clash 的 7890），填进来就能完成引导。
     */
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
                android.widget.Toast.makeText(this, "背景已设置（未能复制到本地，重启后可能失效）",
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
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            try { if (in != null) in.close(); } catch (Exception ignored) {}
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
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {
            if (sb.getId() == R.id.seek_dpi) {
                Ui.prefs(SettingsActivity.this).edit().putInt(Ui.KEY_DPI, sb.getProgress() + 50).apply();
                Ui.applyDpi(SettingsActivity.this);
            }
        }
    }
}
