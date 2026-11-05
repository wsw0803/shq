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
        View content0 = a.findViewById(android.R.id.content);
        applyTypography(content0);
        // 暗色下亮字有光晕，粗体显得更糊：自动细一档
        if (!light) lightenTextWeight(content0);
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
     * 全局玻璃样式：透明度按 naozhong 的公式推算，圆角可调。
     *
     * 不再依赖逐个给控件打 tag —— 只要背景是「白色半透明」或「深色半透明」
     * 的形状，就自动按玻璃处理；新增控件、自建弹窗、悬浮窗都自动吃到同一套。
     * 显式 tag 仍然支持，用来覆盖自动判断：
     *   tag="glass"        强制按玻璃处理
     *   tag="glass_corner" 只跟随圆角与描边，不改填充（深色蒙层、彩色块用）
     *
     * mutate() 只是返回可安全修改的副本，不写回 View 就不会生效；
     * 而多个控件共享同一个 drawable 时，直接改原对象又会互相污染。
     * 所以必须 mutate() 之后 setBackground()。
     *
     * 配色规则（对齐 naozhong）：
     * - 白色底 → 填充 alpha = 暗色 0.02+op*0.28 / 浅色 0.04+op*0.66，
     *   描边 alpha = 暗色 0.04+op*0.26 / 浅色 0.12+op*0.50
     * - 深色半透明底（日志蒙层）→ 保留自己的颜色，只跟随圆角
     * - 彩色底（主按钮）→ 保留原色，只跟随圆角，避免被刷成白色玻璃
     */
    public static void applyGlass(View v, int glass) {
        applyGlass(v, glass, false, CORNER_DEFAULT);
    }

    public static void applyGlass(View v, int glass, boolean light, int cornerDp) {
        if (v == null) return;
        Drawable bgDrawable = v.getBackground();
        if (bgDrawable instanceof GradientDrawable && isGlassTarget(v, (GradientDrawable) bgDrawable)) {
            try {
                GradientDrawable src = (GradientDrawable) bgDrawable;
                GradientDrawable gd = (GradientDrawable) src.mutate();
                float density = v.getResources().getDisplayMetrics().density;
                boolean cornerOnly = "glass_corner".equals(v.getTag());

                float op = Math.max(0, Math.min(100, glass)) / 100f;
                gd.setCornerRadius(Math.max(0, cornerDp) * density);

                android.content.res.ColorStateList csl = gd.getColor();
                if (csl != null) {
                    int base = csl.getDefaultColor();
                    int r = Color.red(base), g = Color.green(base), b = Color.blue(base);
                    if (isWhiteish(base) && !cornerOnly) {
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
                        // 深色蒙层 / 彩色块：颜色不动，只让圆角跟随
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

    private static boolean isWhiteish(int c) {
        return Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(c) > 200;
    }

    /** 自动判断是否该按玻璃处理：显式 tag 优先，其次看背景色是否是半透明白/半透明深色。 */
    private static boolean isGlassTarget(View v, GradientDrawable src) {
        if ("glass".equals(v.getTag()) || "glass_corner".equals(v.getTag())) return true;
        try {
            android.content.res.ColorStateList csl = src.getColor();
            if (csl == null) return false;
            int c = csl.getDefaultColor();
            int a = Color.alpha(c);
            // 全透明、全不透明都不算玻璃：前者看不见，后者通常是有意铺底的实心块
            if (a == 0 || a == 255) return false;
            if (isWhiteish(c)) return true;
            return Color.red(c) < 48 && Color.green(c) < 48 && Color.blue(c) < 48;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 给自建布局 / 悬浮窗套用全局玻璃。
     * 悬浮窗、PopupWindow、Dialog 的内容视图都走这里，后续新增的 UI 不用再单独处理。
     */
    public static void applyWindow(View root) {
        if (root == null) return;
        applyWindow(root.getContext(), root);
    }

    public static void applyWindow(Context c, View root) {
        if (c == null || root == null) return;
        SharedPreferences p = prefs(c);
        applyGlass(root, p.getInt(KEY_GLASS, 40),
                isLightMode(c), p.getInt(KEY_CORNER, CORNER_DEFAULT));
    }

    /** 自建 Dialog 统一入口：显示前后都调一次即可。 */
    public static void applyDialog(android.app.Dialog d) {
        if (d == null) return;
        try {
            android.view.Window w = d.getWindow();
            if (w == null) return;
            applyWindow(w.getDecorView());
        } catch (Throwable ignored) {
        }
    }

    public static boolean isLightMode(Activity a) {
        int th = prefs(a).getInt(KEY_THEME, 0);
        if (th == 1) return true;
        if (th == 2) return false;
        return (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
    }

    /** Context 版：悬浮窗 / 弹窗里没有 Activity 时用。 */
    public static boolean isLightMode(Context c) {
        if (c == null) return false;
        int th = prefs(c).getInt(KEY_THEME, 0);
        if (th == 1) return true;
        if (th == 2) return false;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
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
     * DPI 改动后就地重建当前页面。
     *
     * 原来的实现是 startActivity(MainActivity) + finish()，
     * 结果改一下 DPI 就被踢回主页，设置页上的改动前功尽弃。
     * 密度是在 attachBaseContext 里用 createConfigurationContext 套上去的，
     * 所以重建当前页即可生效，不需要切页面。
     */
    public static void applyDpi(Activity a) {
        if (a == null) return;
        try {
            if (!a.isFinishing()) a.recreate();
        } catch (Throwable t) {
            // 极端情况下重建失败，退回原来的做法，至少让新密度能生效
            try {
                android.content.Intent i = new android.content.Intent(a, MainActivity.class);
                i.setFlags(0x10000000 | 0x04000000);
                a.startActivity(i);
                a.finish();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 密度与设置不一致时就地重建。
     *
     * 页面是在 onResume 前用旧密度创建的，改完 DPI 后返回栈里的
     * MainActivity 还是旧尺寸。这里每次回到前台都校一次，
     * 不一致才重建，正常情况零开销、也不会反复重建。
     */
    public static void syncDpi(Activity a) {
        if (a == null || a.isFinishing()) return;
        try {
            SharedPreferences p = prefs(a);
            int pct = p.getInt(KEY_DPI, 100);
            if (pct < 50) pct = 50;
            if (pct > 200) pct = 200;
            int baseDpi = p.getInt(KEY_BASE_DPI, 0);
            if (baseDpi <= 0) return;
            int want = (int) (baseDpi * (pct / 100f));
            int cur = a.getResources().getDisplayMetrics().densityDpi;
            // 留 4 dpi 容差，避免取整误差导致反复重建
            if (Math.abs(cur - want) > 4) a.recreate();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 排版保护：数字统一用等宽字形（tnum），刷新时宽度不变、不会左右抖动；
     * 再用 balanced 断行策略避免出现单字孤行。
     */
    public static void applyTypography(View v) {
        if (v == null) return;
        if (v instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) v;
            try {
                t.setFontFeatureSettings("tnum");
            } catch (Throwable ignored) {
            }
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    t.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);
                    t.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
                } catch (Throwable ignored) {
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyTypography(g.getChildAt(i));
        }
    }

    /**
     * 暗色模式文字细一档。
     *
     * 亮字在暗底上会有光晕扩散（halation），同样的字重在暗色下看起来更粗更糊。
     * 所以只把粗体降一档到常规，常规不再降，避免糊成一团。
     */
    public static void lightenTextWeight(View v) {
        if (v == null) return;
        if (v instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) v;
            try {
                android.graphics.Typeface tf = t.getTypeface();
                if (tf != null && tf.isBold()) {
                    t.setTypeface(android.graphics.Typeface.create(tf, android.graphics.Typeface.NORMAL),
                            android.graphics.Typeface.NORMAL);
                }
            } catch (Throwable ignored) {
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) lightenTextWeight(g.getChildAt(i));
        }
    }

    /**
     * 行内图标下移 1/8 字高。
     *
     * 图标与文字按几何中心对齐时，视觉上会偏高（文字重心在基线以上），
     * 下移 1/8 个字号刚好落在视觉中线。
     */
    public static void alignInlineIcon(View icon, float textSizeSp) {
        if (icon == null) return;
        try {
            ViewGroup.LayoutParams lp = icon.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                float scaled = icon.getResources().getDisplayMetrics().scaledDensity;
                ((ViewGroup.MarginLayoutParams) lp).topMargin = (int) (textSizeSp * scaled / 8f);
                icon.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 流式渐显：新内容从轻微高斯模糊 + 半透明过渡到清晰。
     * 只改视觉，不阻塞追加；API 31 以下自动退化为纯淡入。
     */
    public static void fadeInBlur(final View v) {
        if (v == null) return;
        try {
            v.setAlpha(0.55f);
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(170);
            anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    try {
                        float f = (Float) a.getAnimatedValue();
                        v.setAlpha(0.55f + 0.45f * f);
                        if (Build.VERSION.SDK_INT >= 31) {
                            float blur = (1f - f) * 5f;
                            v.setRenderEffect(blur <= 0.05f ? null
                                    : android.graphics.RenderEffect.createBlurEffect(
                                    blur, blur, android.graphics.Shader.TileMode.CLAMP));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            anim.start();
        } catch (Throwable ignored) {
        }
    }
}
