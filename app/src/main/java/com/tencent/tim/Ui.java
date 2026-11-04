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

    /** 玻璃圆角，单位 dp（naozhong 默认 22）。 */
    public static final String KEY_CORNER = "ui_corner";
    public static final int CORNER_DEFAULT = 22;

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
            // naozhong 的玻璃是「半透明白叠在渐变+光斑上」，没有全局白纱罩；
            // 这里置成全透明，否则光斑会被冲淡、玻璃透不出底色
            glassLayer.setBackgroundColor(Color.TRANSPARENT);
        }
        boolean light = isLightMode(a);
        int corner = p.getInt(KEY_CORNER, CORNER_DEFAULT);
        applyGlass(a.findViewById(android.R.id.content), p.getInt(KEY_GLASS, 40), light, corner);
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
     * 递归调整带 tag="glass" 的控件：透明度按 naozhong 的公式推算，圆角可调。
     *
     * mutate() 只是返回可安全修改的副本，不写回 View 就不会生效；
     * 而多个控件共享同一个 drawable 时，直接改原对象又会互相污染。
     * 所以必须 mutate() 之后 setBackground()。
     *
     * 配色规则（对齐 naozhong）：
     * - 白色底 → 填充 alpha = 暗色 0.02+op*0.28 / 浅色 0.04+op*0.66，
     *   描边 alpha = 暗色 0.04+op*0.26 / 浅色 0.12+op*0.50
     * - 渐变色底或彩色底（主按钮、日志蒙层）→ 配色不动，只跟随圆角，
     *   否则蓝色主按钮会被刷成白色玻璃
     */
    public static void applyGlass(View v, int glass) {
        applyGlass(v, glass, false, CORNER_DEFAULT);
    }

    public static void applyGlass(View v, int glass, boolean light, int cornerDp) {
        if (v == null) return;
        if ("glass".equals(v.getTag()) && v.getBackground() instanceof GradientDrawable) {
            try {
                GradientDrawable src = (GradientDrawable) v.getBackground();
                GradientDrawable gd = (GradientDrawable) src.mutate();
                float density = v.getResources().getDisplayMetrics().density;

                float op = Math.max(0, Math.min(100, glass)) / 100f;
                gd.setCornerRadius(Math.max(0, cornerDp) * density);

                android.content.res.ColorStateList csl = gd.getColor();
                if (csl != null) {
                    int base = csl.getDefaultColor();
                    int r = Color.red(base), g = Color.green(base), b = Color.blue(base);
                    boolean whiteish = r > 200 && g > 200 && b > 200;
                    if (whiteish) {
                        int fill = light
                                ? (int) ((0.04f + op * 0.66f) * 255f)
                                : (int) ((0.02f + op * 0.28f) * 255f);
                        int border = light
                                ? (int) ((0.12f + op * 0.50f) * 255f)
                                : (int) ((0.04f + op * 0.26f) * 255f);
                        fill = Math.max(0, Math.min(255, fill));
                        border = Math.max(0, Math.min(255, border));
                        gd.setColor(Color.argb(fill, 255, 255, 255));
                        gd.setStroke(Math.max(1, (int) density), Color.argb(border, 255, 255, 255));
                    } else {
                        // 深色蒙层（日志区）保留自己的颜色，只让圆角跟随
                        gd.setColor(Color.argb(Color.alpha(base), r, g, b));
                    }
                }
                v.setBackground(gd);
            } catch (Throwable ignored) {
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyGlass(g.getChildAt(i), glass, light, cornerDp);
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
                if (r + g + b > 480) t.setTextColor(0xFF0A0F1A);
                else if (r + g + b > 300) t.setTextColor(0xFF46536B);
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
