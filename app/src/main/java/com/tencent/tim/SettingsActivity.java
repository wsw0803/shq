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

    private TextView tvDpi, tvGlass, tvBright, tvCorner;
    private SeekBar seekDpi, seekGlass, seekBright, seekCorner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Button back = findViewById(R.id.btn_back);
        Button reset = findViewById(R.id.btn_reset);
        tvDpi = findViewById(R.id.tv_dpi);
        tvGlass = findViewById(R.id.tv_glass);
        tvBright = findViewById(R.id.tv_bright);
        tvCorner = findViewById(R.id.tv_corner);
        seekDpi = findViewById(R.id.seek_dpi);
        seekGlass = findViewById(R.id.seek_glass);
        seekBright = findViewById(R.id.seek_bright);
        seekCorner = findViewById(R.id.seek_corner);

        SharedPreferences p = Ui.prefs(this);
        seekDpi.setProgress(p.getInt(Ui.KEY_DPI, 100) - 50);
        seekGlass.setProgress(p.getInt(Ui.KEY_GLASS, 40));
        seekBright.setProgress(p.getInt(Ui.KEY_BRIGHT, 70));
        seekCorner.setProgress(p.getInt(Ui.KEY_CORNER, Ui.CORNER_DEFAULT) - 8);
        labels();

        Button diagShow = findViewById(R.id.btn_diag_log);
        Button diagClear = findViewById(R.id.btn_diag_clear);
        if (diagShow != null) diagShow.setOnClickListener(v -> showDiagDialog());
        if (diagClear != null) {
            diagClear.setOnClickListener(v -> {
                Diag.clear();
                android.widget.Toast.makeText(this, "诊断日志已清空", android.widget.Toast.LENGTH_SHORT).show();
            });
        }

        Button pickBg = findViewById(R.id.btn_pick_bg);
        Button clearBg = findViewById(R.id.btn_clear_bg);
        pickBg.setOnClickListener(v -> {
            // 关键：ACTION_GET_CONTENT 拿到的 URI 无法持久化授权，
            // 重启或升级后就读不到了，背景会「消失」。
            // 改用 ACTION_OPEN_DOCUMENT，并在拿到后把图片复制进应用私有目录，
            // 从此不再依赖外部授权。
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
        seekCorner.setOnSeekBarChangeListener(new L() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean u) {
                Ui.prefs(SettingsActivity.this).edit().putInt(Ui.KEY_CORNER, v + 8).apply();
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
                    .putInt(Ui.KEY_CORNER, Ui.CORNER_DEFAULT)
                    .putInt(Ui.KEY_BG, 0)
                    .apply();
            seekDpi.setProgress(50);
            seekGlass.setProgress(40);
            seekBright.setProgress(70);
            seekCorner.setProgress(Ui.CORNER_DEFAULT - 8);
            labels();
            Ui.apply(this);
        });
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
            // 复制到私有目录：即便外部授权失效、或应用升级换了 UID，背景也不会丢
            if (copyToPrivate(uri)) {
                Ui.prefs(this).edit()
                        .putString(Ui.KEY_BG_URI, Ui.localBgPath(this))
                        .apply();
            } else {
                // 复制失败时退回原 URI，至少本次能用
                Ui.prefs(this).edit().putString(Ui.KEY_BG_URI, uri.toString()).apply();
                android.widget.Toast.makeText(this, "背景已设置（未能复制到本地，重启后可能失效）",
                        android.widget.Toast.LENGTH_LONG).show();
            }
            Ui.apply(this);
        }
    }

    /** 把选中的图片复制到应用私有目录，成功返回 true。 */
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

    /**
     * 诊断日志弹窗：直接把最近 400 行贴出来，可一键复制发给别人。
     * 覆盖范围见 Diag 的注释——登录四层捕获、看门狗、WebView 错误、云端列表、root 写入、崩溃。
     */
    private void showDiagDialog() {
        final String text = Diag.tail(400);
        float density = getResources().getDisplayMetrics().density;

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackground(TokenDialog.glass(this, 22, true));
        int pad = (int) (16 * density);
        root.setPadding(pad, pad, pad, pad);

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("诊断日志");
        title.setTextSize(16);
        title.setTextColor(0xFFF7F8FA);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        android.widget.TextView sub = new android.widget.TextView(this);
        sub.setText("文件: " + (Diag.currentFile() == null ? "-" : Diag.currentFile().getAbsolutePath()));
        sub.setTextSize(9);
        sub.setTextColor(0xFF8A93A3);
        sub.setTypeface(android.graphics.Typeface.MONOSPACE);
        sub.setPadding(0, (int) (4 * density), 0, (int) (8 * density));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.TextView body = new android.widget.TextView(this);
        body.setText(text);
        body.setTextSize(9);
        body.setTextColor(0xFFD5DAE3);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        sv.addView(body);
        root.addView(title);
        root.addView(sub);
        root.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.5f)));

        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dlg.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }

        android.widget.LinearLayout actions = new android.widget.LinearLayout(this);
        actions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams aLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = (int) (10 * density);

        android.widget.Button copy = new android.widget.Button(this);
        copy.setText("复制全部");
        copy.setTextSize(12);
        copy.setAllCaps(false);
        copy.setTextColor(0xFFF7F8FA);
        copy.setBackground(TokenDialog.glass(this, 12, true));
        copy.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("wei-diag", Diag.tail(4000)));
                android.widget.Toast.makeText(this, "已复制完整日志", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                android.widget.Toast.makeText(this, "复制失败: " + t, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        android.widget.Button close = new android.widget.Button(this);
        close.setText("关闭");
        close.setTextSize(12);
        close.setAllCaps(false);
        close.setTextColor(0xFFF7F8FA);
        close.setBackground(TokenDialog.glass(this, 12, false));
        close.setOnClickListener(v -> dlg.dismiss());

        android.widget.LinearLayout.LayoutParams lp1 = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = (int) (6 * density);
        actions.addView(copy, lp1);
        actions.addView(close, new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(actions, aLp);

        dlg.setContentView(root);
        dlg.show();
        Ui.applyDialog(dlg);
    }

    private void labels() {
        SharedPreferences p = Ui.prefs(this);
        tvDpi.setText("DPI: " + p.getInt(Ui.KEY_DPI, 100) + "%");
        tvGlass.setText("玻璃透明度: " + p.getInt(Ui.KEY_GLASS, 40) + "%");
        tvBright.setText("亮度: " + p.getInt(Ui.KEY_BRIGHT, 70) + "%");
        tvCorner.setText("圆角: " + p.getInt(Ui.KEY_CORNER, Ui.CORNER_DEFAULT) + "dp");
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
