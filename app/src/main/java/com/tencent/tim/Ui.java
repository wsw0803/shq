package com.tencent.tim;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.File;
import java.io.InputStream;

public class Ui {
    public static final String PREF = "myPrefs";
    public static final String KEY_DPI = "ui_dpi";
    public static final String KEY_GLASS = "ui_glass";
    public static final String KEY_BRIGHT = "ui_bright";
    public static final String KEY_BG = "ui_bg";
    public static final String KEY_BG_URI = "ui_bg_uri";
    public static final String KEY_THEME = "ui_theme";
    public static final String KEY_BASE_DPI = "ui_base_dpi";

    private static final int GLASS_BASE = 0x33FFFFFF;
    /** 应用私有目录里的背景图文件名，与 SettingsActivity 保持一致。 */
    private static final String BG_FILE = "bg_custom.img";

    /** 私有背景图的绝对路径，写入 KEY_BG_URI 时用这个而不是 content:// 。 */
    public static String localBgPath(Context c) {
        return new File(c.getFilesDir(), BG_FILE).getAbsolutePath();
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static Context wrap(Context base) {
        int pct = prefs(base).getInt(KEY_DPI, 100);
        if (pct < 50) pct = 50;
        if (pct > 200) pct = 200;
        if (pct == 100) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        // 基准 DPI 只在首次计算时写入一次，避免和 applyDpi 抢写导致缩放漂移
        int baseDpi = prefs(base).getInt(KEY_BASE_DPI, 0);
        if (baseDpi <= 0) {
            baseDpi = base.getResources().getDisplayMetrics().densityDpi;
            prefs(base).edit().putInt(KEY_BASE_DPI, baseDpi).apply();
        }
        cfg.densityDpi = (int) (baseDpi * (pct / 100f));
        return base.createConfigurationContext(cfg);
    }

    public static void apply(Activity a) {
        SharedPreferences p = prefs(a);
        View glassLayer = a.findViewById(R.id.bg_glass);
        if (glassLayer != null) {
            glassLayer.setBackgroundColor(Color.argb(36, 255, 255, 255));
        }
        applyGlass(a.findViewById(android.R.id.content), p.getInt(KEY_GLASS, 40));
        View bright = a.findViewById(R.id.bg_hue);
        if (bright != null) {
            int b = p.getInt(KEY_BRIGHT, 70);
            int ba = (int) ((100 - b) * 1.55f);
            bright.setBackgroundColor(Color.argb(ba, 0, 0, 0));
        }
        ImageView custom = a.findViewById(R.id.bg_custom);
        if (custom != null) {
            applyCustomBg(a, custom, p.getString(KEY_BG_URI, ""));
        }
        applyTheme(a);
        View content = a.findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            View root = ((ViewGroup) content).getChildAt(0);
            int th = p.getInt(KEY_THEME, 0);
            boolean lt = th == 1 || (th == 0
                    && (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES);
            if (root != null) root.setBackgroundResource(lt ? R.drawable.bg_light : bgRes(p.getInt(KEY_BG, 0)));
        }
    }

    /**
     * 设置自定义背景。
     *
     * 优先读私有目录里的文件（这种路径是绝对路径，不依赖 URI 授权，升级/重启后仍在）；
     * 兼容老的 content:// 记录，读不到就隐藏，不再让 View 停留在上一次的图。
     */
    private static void applyCustomBg(Activity a, ImageView custom, String ref) {
        if (ref == null || ref.isEmpty()) {
            custom.setVisibility(View.GONE);
            return;
        }
        InputStream in = null;
        try {
            Drawable d;
            if (ref.startsWith("/")) {
                // 私有目录绝对路径
                File f = new File(ref);
                if (!f.exists()) {
                    custom.setVisibility(View.GONE);
                    return;
                }
                d = Drawable.createFromPath(ref);
            } else {
                in = a.getContentResolver().openInputStream(Uri.parse(ref));
                d = Drawable.createFromStream(in, "bg");
            }
            if (d != null) {
                custom.setImageDrawable(d);
                custom.setVisibility(View.VISIBLE);
            } else {
                custom.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            custom.setVisibility(View.GONE);
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 递归调整带 tag="glass" 的控件透明度。
     *
     * mutate() 只是返回可安全修改的副本，不写回 View 就不会生效；
     * 而多个控件共享同一个 drawable 时，直接改原对象又会互相污染。
     * 所以必须 mutate() 之后 setBackground()。
     */
    public static void applyGlass(View v, int glass) {
        if (v == null) return;
        if ("glass".equals(v.getTag()) && v.getBackground() instanceof GradientDrawable) {
            try {
                GradientDrawable src = (GradientDrawable) v.getBackground();
                GradientDrawable gd = (GradientDrawable) src.mutate();
                int alpha = 25 + glass * 2;
                if (alpha < 25) alpha = 25;
                if (alpha > 235) alpha = 235;
                int base = GLASS_BASE;
                if (Build.VERSION.SDK_INT >= 24) {
                    android.content.res.ColorStateList csl = gd.getColor();
                    if (csl != null) base = csl.getDefaultColor();
                }
                gd.setColor(Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base)));
                v.setBackground(gd);
            } catch (Throwable ignored) {
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyGlass(g.getChildAt(i), glass);
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
        if (v instanceof ViewGroup) {
            ViewGroup gp = (ViewGroup) v;
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

    /**
     * 修改 DPI 后重启界面。
     *
     * 直接改 resources.displayMetrics 是无效的（Resources 内部已缓存），
     * 真正的缩放靠 attachBaseContext 里的 createConfigurationContext 实现。
     */
    public static void applyDpi(Activity a) {
        if (a == null) return;
        android.content.Intent i = new android.content.Intent(a, MainActivity.class);
        i.setFlags(0x10000000 | 0x04000000);
        a.startActivity(i);
        a.finish();
    }
}
