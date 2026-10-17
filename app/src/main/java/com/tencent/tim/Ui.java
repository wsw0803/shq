package com.tencent.tim;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.InputStream;

public class Ui {
    public static final String PREF = "myPrefs";
    public static final String KEY_DPI = "ui_dpi";
    public static final String KEY_GLASS = "ui_glass";
    public static final String KEY_BRIGHT = "ui_bright";
    public static final String KEY_BG = "ui_bg";
    public static final String KEY_BG_URI = "ui_bg_uri"; public static final String KEY_THEME = "ui_theme";
    public static final String KEY_BASE_DPI = "ui_base_dpi";

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
        if (prefs(base).getInt(KEY_BASE_DPI, 0) <= 0) {
            prefs(base).edit().putInt(KEY_BASE_DPI, baseDpi).apply();
        }
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
        ImageView custom = a.findViewById(R.id.bg_custom);
        if (custom != null) {
            String uriStr = p.getString(KEY_BG_URI, "");
            if (!uriStr.isEmpty()) {
                try {
                    InputStream in = a.getContentResolver().openInputStream(Uri.parse(uriStr));
                    Drawable d = Drawable.createFromStream(in, "bg");
                    if (in != null) in.close();
                    if (d != null) {
                        custom.setImageDrawable(d);
                        custom.setVisibility(View.VISIBLE);
                    } else {
                        custom.setVisibility(View.GONE);
                    }
                } catch (Exception e) {
                    custom.setVisibility(View.GONE);
                }
            } else {
                custom.setVisibility(View.GONE);
            }
        }
        applyTheme(a);
        View content = a.findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            View root = ((ViewGroup) content).getChildAt(0);
            int th = p.getInt(KEY_THEME, 0); boolean lt = th == 1 || (th == 0 && (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES); if (root != null) root.setBackgroundResource(lt ? R.drawable.bg_light : bgRes(p.getInt(KEY_BG, 0)));
        }
    }


    public static boolean isLightMode(Activity a) {
        int th = prefs(a).getInt(KEY_THEME, 0);
        if (th == 1) return true;
        if (th == 2) return false;
        return (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
    }

    public static void tint(View v, boolean light) {
        if (v instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) v;
            int c = t.getCurrentTextColor();
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            if (light) {
                if (r + g + b > 480) t.setTextColor(0xFF14202E);
                else if (r + g + b > 300) t.setTextColor(0xFF51637A);
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup gp = (android.view.ViewGroup) v;
            for (int i = 0; i < gp.getChildCount(); i++) tint(gp.getChildAt(i), light);
        }
    }

    public static void applyTheme(Activity a) {
        boolean light = isLightMode(a);
        View content = a.findViewById(android.R.id.content);
        if (content != null) tint(content, light);
    }
    public static int bgRes(int idx) {
        switch (idx) {
            case 1: return R.drawable.bg_style_1;
            case 2: return R.drawable.bg_style_2;
            case 3: return R.drawable.bg_style_3;
            default: return R.drawable.bg_window;
        }
    }

    public static void applyDpi(Activity a) {
        int pct = prefs(a).getInt(KEY_DPI, 100);
        int base = prefs(a).getInt(KEY_BASE_DPI, 0);
        if (base <= 0) {
            base = a.getResources().getDisplayMetrics().densityDpi;
            prefs(a).edit().putInt(KEY_BASE_DPI, base).apply();
        }
        int target = (int) (base * pct / 100f);
        a.getResources().getDisplayMetrics().densityDpi = target;
        a.getResources().getConfiguration().densityDpi = target;
        a.recreate();
    }
}
