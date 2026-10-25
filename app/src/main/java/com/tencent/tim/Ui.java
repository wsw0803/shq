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
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.WeakHashMap;

/**
 * 界面外观：DPI、玻璃、亮度、背景图、主题。
 *
 * 本次修复（2026-10-25）：
 *
 * 1) 主题字体颜色不再「只能变暗，不能还原」。
 *    tint() 原来是单向的：只在浅色主题下把过亮的文字压暗，从不还原原值。
 *    切回深色主题后文字仍是那层被压暗的颜色，深底深字几乎看不见。
 *    现在每个 TextView 的原值记在 TEXT_STATE 里，深色主题一律还原。
 *
 * 2) 强调色不再被拍平。
 *    原来按 r+g+b 的和判断「亮不亮」，于是 #FBBF24（琥珀）、#34D399（绿）、
 *    #22D3EE（青）都被判成过亮并改成灰色，浅色主题下强调色全丢。
 *    现在先看饱和度：低饱和度当中性色压暗，高饱和度保留色相只压低明度。
 *
 * 3) 为亮底设计的深色文字不再被改。
 *    主按钮上的 #04121A 这类本就是深色的字，浅色主题下必须保持原样，
 *    否则会在亮青色按钮上变成灰字，反而更难读。
 *
 * 4) 控件自己改过的颜色会被尊重。
 *    存储状态那种运行时把文字改成红/绿的控件，不会被 tint 覆盖回原值。
 *
 * 5) 浅色主题下白色玻璃换成高不透明度白卡片。
 *    原来是 #20FFFFFF 的白玻璃，压在浅色渐变上等于没有卡片；
 *    强调色按钮（青/绿系）不改色相。
 *
 * 6) 窗口背景跟随主题，切页动画不再闪一层深色底。
 */
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

    /** 浅色主题下的中性文字：近白 → 墨色；中间调 → 深蓝灰；低亮 → 稍浅的蓝灰。 */
    private static final int LIGHT_INK = 0xFF121C2A;
    private static final int LIGHT_MID = 0xFF3B4C63;
    private static final int LIGHT_SOFT = 0xFF54677F;

    /**
     * 每个 TextView 的文字颜色状态：[0] = 原值，[1] = 上一次由 tint 写入的值。
     *
     * 必须是 WeakMap：Activity 会反复重建，强引用会一直攒着旧 View。
     * 用 View 自身当 key 也保证不会把颜色记错到别的控件上。
     */
    private static final WeakHashMap<TextView, int[]> TEXT_STATE = new WeakHashMap<>();

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
        final boolean light = isLightMode(a);
        final int bg = light ? R.drawable.bg_light : bgRes(p.getInt(KEY_BG, 0));

        // 窗口背景也跟随主题：只改根布局的话，切页动画与半透明区域仍会露出深色底
        try {
            a.getWindow().setBackgroundDrawableResource(bg);
        } catch (Throwable ignored) {
        }

        View glassLayer = a.findViewById(R.id.bg_glass);
        if (glassLayer != null) {
            glassLayer.setBackgroundColor(light ? Color.argb(20, 255, 255, 255)
                    : Color.argb(36, 255, 255, 255));
        }
        applyGlass(a.findViewById(android.R.id.content), p.getInt(KEY_GLASS, 40), light);
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
            if (root != null) {
                root.setBackgroundResource(bg);
            }
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

    public static void applyGlass(View v, int glass) {
        applyGlass(v, glass, false);
    }

    /**
     * 递归调整带 tag="glass" 的控件透明度。
     *
     * mutate() 只是返回可安全修改的副本，不写回 View 就不会生效；
     * 而多个控件共享同一个 drawable 时，直接改原对象又会互相污染。
     * 所以必须 mutate() 之后 setBackground()。
     *
     * 浅色主题下，底色是白色系（玻璃卡片、次要按钮）的控件必须提高不透明度，
     * 否则 #20FFFFFF 的白玻璃压在浅色渐变上等于没有卡片。强调色按钮不动色相。
     */
    public static void applyGlass(View v, int glass, boolean light) {
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
                boolean whitish = Color.red(base) > 228 && Color.green(base) > 228
                        && Color.blue(base) > 228;
                if (light && whitish) {
                    // 白卡片：给足不透明度，配深色文字正好
                    alpha = 120 + (int) (glass * 1.1f);
                    if (alpha < 120) alpha = 120;
                    if (alpha > 240) alpha = 240;
                    float dens = v.getResources().getDisplayMetrics().density;
                    gd.setStroke(Math.max(1, (int) dens), 0x1F000000);
                }
                gd.setColor(Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base)));
                v.setBackground(gd);
            } catch (Throwable ignored) {
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyGlass(g.getChildAt(i), glass, light);
        }
    }

    public static boolean isLightMode(Activity a) {
        int th = prefs(a).getInt(KEY_THEME, 0);
        if (th == 1) return true;
        if (th == 2) return false;
        return (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 按主题给文字上色：浅色主题转换成深色系，深色主题还原成原值。
     *
     * 原实现只做了「浅色 → 压暗」这一半，且判断用的是 r+g+b 之和，
     * 结果切回深色后文字再也回不去，强调色也被一并拍成灰色。
     * 现在原值记在 TEXT_STATE 里，两个方向都能正确走；
     * 控件自己改过颜色（状态变红/变绿）时，把新颜色当成新的原值，不再被覆盖。
     */
    public static void tint(View v, boolean light) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            int now = t.getCurrentTextColor();
            int[] st = TEXT_STATE.get(t);
            if (st == null) {
                st = new int[]{now, now};
                TEXT_STATE.put(t, st);
            } else if (now != st[1]) {
                // 控件自己换过色（例如状态文本变绿/变红），以新颜色为准
                st[0] = now;
            }
            int target = light ? lightText(st[0]) : st[0];
            if (target != now) {
                t.setTextColor(target);
            }
            st[1] = target;
        }
        if (v instanceof ViewGroup) {
            ViewGroup gp = (ViewGroup) v;
            for (int i = 0; i < gp.getChildCount(); i++) tint(gp.getChildAt(i), light);
        }
    }

    /**
     * 把一个「为深色背景设计」的文字颜色，换算成浅色背景上可读的颜色。
     *
     * 已经是深色的（主按钮上的 #04121A 之类）原样返回 —— 它本来就是压在亮色上的；
     * 低饱和度的近白/灰蓝 → 换成同层级的中性深色，保住主/次/弱三级层次；
     * 高饱和度的青/绿/红/紫/琥珀 → 保留色相，只压低明度，强调色不失真。
     */
    private static int lightText(int c) {
        int a = Color.alpha(c);
        if (a == 0) return c;
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        float v = hsv[2];
        float s = hsv[1];
        if (s <= 0.35f) {
            if (v <= 0.55f) return c;
            float lightness = v * (1f - s / 2f);
            if (lightness >= 0.80f) return withAlpha(a, LIGHT_INK);
            if (lightness >= 0.55f) return withAlpha(a, LIGHT_MID);
            return withAlpha(a, LIGHT_SOFT);
        }
        if (v <= 0.62f) return c;
        hsv[1] = Math.min(1f, s + 0.05f);
        hsv[2] = 0.62f;
        return Color.HSVToColor(a, hsv);
    }

    private static int withAlpha(int alpha, int rgb) {
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
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
