package com.tencent.tim;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;

public class Ui {
    public static final String PREF = "myPrefs";
    public static final String KEY_DPI = "ui_dpi";
    public static final String KEY_GLASS = "ui_glass";
    public static final String KEY_HUE = "ui_hue";
    public static final String KEY_HUE_ALPHA = "ui_hue_alpha";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static Context wrap(Context base) {
        int pct = prefs(base).getInt(KEY_DPI, 100);
        if (pct < 60) pct = 60;
        if (pct > 220) pct = 220;
        if (pct == 100) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.densityDpi = (int) (cfg.densityDpi * pct / 100f);
        return base.createConfigurationContext(cfg);
    }

    public static void apply(Activity a) {
        View hue = a.findViewById(R.id.bg_hue);
        if (hue == null) return;
        int h = prefs(a).getInt(KEY_HUE, 0);
        int alphaPct = prefs(a).getInt(KEY_HUE_ALPHA, 30);
        int g = prefs(a).getInt(KEY_GLASS, 50);
        int color = Color.HSVToColor(new float[]{ (float) h, 0.85f, 0.9f });
        int alpha = (int) (alphaPct * 2.55f);
        hue.setBackgroundColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)));
        hue.setAlpha(0.35f + (g / 100f) * 0.65f);
    }
}
