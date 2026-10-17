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

        seekDpi.setOnSeekBarChangeListener(new L() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean u) {
                Ui.prefs(SettingsActivity.this).edit().putInt(Ui.KEY_DPI, v + 50).apply();
                labels();
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

        int[] bgIds = {R.id.bg_0, R.id.bg_1, R.id.bg_2, R.id.bg_3};
        for (int i = 0; i < bgIds.length; i++) {
            final int idx = i;
            Button b = findViewById(bgIds[i]);
            b.setOnClickListener(v -> {
                Ui.prefs(this).edit().putInt(Ui.KEY_BG, idx).apply();
                Ui.apply(this);
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

    private void labels() {
        SharedPreferences p = Ui.prefs(this);
        tvDpi.setText("DPI: " + p.getInt(Ui.KEY_DPI, 100) + "%");
        tvGlass.setText("玻璃透明度: " + p.getInt(Ui.KEY_GLASS, 40) + "%");
        tvBright.setText("亮度: " + p.getInt(Ui.KEY_BRIGHT, 70) + "%");
    }

    private static abstract class L implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }
}
