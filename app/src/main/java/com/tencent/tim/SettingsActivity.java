package com.tencent.tim;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

public class SettingsActivity extends BaseActivity {

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
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
            it.setType("image/*");
            startActivityForResult(it, 1001);
        });
        clearBg.setOnClickListener(v -> {
            Ui.prefs(this).edit().remove(Ui.KEY_BG_URI).apply();
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
            Ui.prefs(this).edit().putInt(Ui.KEY_DPI, 100).putInt(Ui.KEY_GLASS, 40).putInt(Ui.KEY_BRIGHT, 70).putInt(Ui.KEY_BG, 0).apply();
            seekDpi.setProgress(50);
            seekGlass.setProgress(40);
            seekBright.setProgress(70);
            labels();
            Ui.apply(this);
        });
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1001 && res == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            Ui.prefs(this).edit().putString(Ui.KEY_BG_URI, uri.toString()).apply();
            Ui.apply(this);
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
