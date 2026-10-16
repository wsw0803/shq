package com.tencent.tim;

import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

public class SettingsActivity extends BaseActivity {

    private TextView tvDpi, tvGlass, tvHue, tvHueAlpha;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Button back = findViewById(R.id.btn_back);
        Button reset = findViewById(R.id.btn_reset);
        tvDpi = findViewById(R.id.tv_dpi);
        tvGlass = findViewById(R.id.tv_glass);
        tvHue = findViewById(R.id.tv_hue);
        tvHueAlpha = findViewById(R.id.tv_hue_alpha);
        SeekBar seekDpi = findViewById(R.id.seek_dpi);
        SeekBar seekGlass = findViewById(R.id.seek_glass);
        SeekBar seekHue = findViewById(R.id.seek_hue);
        SeekBar seekHueAlpha = findViewById(R.id.seek_hue_alpha);

        int dpi = Ui.prefs(this).getInt(Ui.KEY_DPI, 100);
        int glass = Ui.prefs(this).getInt(Ui.KEY_GLASS, 50);
        int hue = Ui.prefs(this).getInt(Ui.KEY_HUE, 0);
        int hueAlpha = Ui.prefs(this).getInt(Ui.KEY_HUE_ALPHA, 30);

        seekDpi.setProgress(dpi - 40);
        seekGlass.setProgress(glass);
        seekHue.setProgress(hue);
        seekHueAlpha.setProgress(hueAlpha);
        updateLabels(dpi, glass, hue, hueAlpha);

        seekDpi.setOnSeekBarChangeListener(new L() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                int v = p + 40;
                Ui.prefs(SettingsActivity.this).edit().putInt(Ui.KEY_DPI, v).apply();
                tvDpi.setText("当前 DPI: " + v + "%");
            }
        });
        seekGlass.setOnSeekBarChangeListener(new L() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                Ui.prefs(SettingsActivity.this).edit().putInt(Ui.KEY_GLASS, p).apply();
                tvGlass.setText("玻璃强度: " + p + "%");
                Ui.apply(SettingsActivity.this);
            }
        });
        seekHue.setOnSeekBarChangeListener(new L() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                Ui.prefs(SettingsActivity.this).edit().putInt(Ui.KEY_HUE, p).apply();
                tvHue.setText("色相: " + p + " 度");
                Ui.apply(SettingsActivity.this);
            }
        });
        seekHueAlpha.setOnSeekBarChangeListener(new L() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                Ui.prefs(SettingsActivity.this).edit().putInt(Ui.KEY_HUE_ALPHA, p).apply();
                tvHueAlpha.setText("色度浓度: " + p + "%");
                Ui.apply(SettingsActivity.this);
            }
        });

        back.setOnClickListener(v -> finish());
        reset.setOnClickListener(v -> {
            Ui.prefs(this).edit().putInt(Ui.KEY_DPI, 100).putInt(Ui.KEY_GLASS, 50).putInt(Ui.KEY_HUE, 0).putInt(Ui.KEY_HUE_ALPHA, 30).apply();
            seekDpi.setProgress(60);
            seekGlass.setProgress(50);
            seekHue.setProgress(0);
            seekHueAlpha.setProgress(30);
            updateLabels(100, 50, 0, 30);
            Ui.apply(this);
        });
    }

    private void updateLabels(int dpi, int glass, int hue, int hueAlpha) {
        tvDpi.setText("当前 DPI: " + dpi + "%");
        tvGlass.setText("玻璃强度: " + glass + "%");
        tvHue.setText("色相: " + hue + " 度");
        tvHueAlpha.setText("色度浓度: " + hueAlpha + "%");
    }

    private static abstract class L implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }
}
