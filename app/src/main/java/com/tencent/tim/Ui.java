package com.tencent.tim;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;

public class Ui {
    public static final String PREF = "myPrefs";
    public static final String KEY_DPI = "ui_dpi";
    public static final String KEY_GLASS = "ui_glass";
    public static final String KEY_BRIGHT = "ui_bright";
    public static final String KEY_BG = "ui_bg";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static Context wrap(Context base) {
        int pct = prefs(base).getInt(KEY_DPI, 100);
        if (pct < 50) pct = 50;
        if (pct > 200) pct = 200;
        if (pct == 100) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int baseDpi = base.getResources().getDisplayMetrics().densityDpi;
        cfg.densityDpi = (int) (baseDpi * (pct / 100f));
        return base.createConfigurationContext(cfg);
    }

    public static void apply(Activity a) {
        SharedPreferences p = prefs(a);
        View glass = a.findViewById(R.id.bg_glass);
        if (glass != null) {
            int g = p.getInt(KEY_GLASS, 40);
            int ga = (int) (g * 1.1f);
            if (ga > 130) ga = 130;
            glass.setBackgroundColor(Color.argb(ga, 255, 255, 255));
        }
        View bright = a.findViewById(R.id.bg_hue);
        if (bright != null) {
            int b = p.getInt(KEY_BRIGHT, 70);
            int ba = (int) ((100 - b) * 2.55f);
            bright.setBackgroundColor(Color.argb(ba, 0, 0, 0));
        }
        View content = a.findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            View root = ((ViewGroup) content).getChildAt(0);
            if (root != null) root.setBackgroundResource(bgRes(p.getInt(KEY_BG, 0)));
        }
    }

    public static int bgRes(int idx) {
        switch (idx) {
            case 1: return R.drawable.bg_style_1;
            case 2: return R.drawable.bg_style_2;
            case 3: return R.drawable.bg_style_3;
            default: return R.drawable.bg_window;
        }
    }
}
