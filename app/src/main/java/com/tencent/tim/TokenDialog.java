package com.tencent.tim;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class TokenDialog {

    public interface OnPick {
        void onPick(TokenStore.Entry e);
    }

    public static GradientDrawable glass(Context c, int radiusDp, boolean accent) {
        int g = Ui.prefs(c).getInt(Ui.KEY_GLASS, 40);
        int a = (int) (g * 1.1f);
        if (a > 130) a = 130;
        if (a < 18) a = 18;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        float r = radiusDp * c.getResources().getDisplayMetrics().density;
        d.setCornerRadius(r);
        d.setColor(Color.argb(a, 255, 255, 255));
        d.setStroke((int) (1 * c.getResources().getDisplayMetrics().density),
                accent ? 0x9922D3EE : 0x40FFFFFF);
        return d;
    }

    public static void show(Context c, String game, OnPick pick) {
        if (c == null) return;
        final OnPick cb = pick;
        List<TokenStore.Entry> list;
        if (TextUtils.isEmpty(game)) list = TokenStore.loadAll();
        else list = TokenStore.loadGame(game);

        ScrollView scroll = new ScrollView(c);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 14);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        if (list.isEmpty()) {
            TextView empty = new TextView(c);
            empty.setText("暂无 Token\n目录: " + TokenStore.rootDir().getAbsolutePath());
            empty.setTextColor(0xFFB9C6DA);
            empty.setTextSize(12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(pad, pad * 2, pad, pad * 2);
            root.addView(empty);
        } else {
            String lastGame = null;
            for (int i = 0; i < list.size(); i++) {
                TokenStore.Entry e = list.get(i);
                if (!e.game.equals(lastGame)) {
                    lastGame = e.game;
                    TextView head = new TextView(c);
                    head.setText("◆ " + (TextUtils.isEmpty(e.game) ? "未分类" : e.game));
                    head.setTextColor(0xFF22D3EE);
                    head.setTextSize(13);
                    head.setPadding(dp(c, 4), dp(c, 10), 0, dp(c, 6));
                    root.addView(head);
                }
                root.addView(item(c, e, cb));
            }
        }

        new AlertDialog.Builder(c)
                .setTitle(TextUtils.isEmpty(game) ? "全部 Token" : game + " Token")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .show();
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    private static View item(final Context c, final TokenStore.Entry e, final OnPick cb) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glass(c, 16, false));
        int p = dp(c, 12);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 10);
        card.setLayoutParams(lp);

        TextView title = new TextView(c);
        title.setText(e.time + (TextUtils.isEmpty(e.mode) ? "" : "  ·  " + e.mode));
        title.setTextColor(0xFFF5F7FA);
        title.setTextSize(11);
        card.addView(title);

        card.addView(row(c, "access", e.accessToken));
        card.addView(row(c, "openid", e.openid));
        card.addView(row(c, "pay", e.payToken));

        if (cb != null) {
            TextView act = new TextView(c);
            act.setText("使用此 Token 登录  →");
            act.setTextColor(0xFF34D399);
            act.setTextSize(12);
            act.setPadding(0, dp(c, 8), 0, 0);
            act.setOnClickListener(v -> cb.onPick(e));
            card.addView(act);
        }
        return card;
    }

    private static View row(final Context c, final String label, final String value) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(c, 5), 0, 0);

        TextView k = new TextView(c);
        k.setText(label);
        k.setTextColor(0xFF8A9BB8);
        k.setTextSize(10);
        k.setWidth(dp(c, 56));
        box.addView(k);

        TextView v = new TextView(c);
        v.setText(TextUtils.isEmpty(value) ? "-" : value);
        v.setTextColor(0xFFE6EEF8);
        v.setTextSize(10);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        v.setLayoutParams(lp);
        box.addView(v);

        TextView copy = new TextView(c);
        copy.setText("复制");
        copy.setTextColor(0xFF22D3EE);
        copy.setTextSize(10);
        copy.setPadding(dp(c, 8), dp(c, 2), 0, dp(c, 2));
        copy.setOnClickListener(view -> {
            if (TextUtils.isEmpty(value)) {
                Toast.makeText(c, label + " 为空", Toast.LENGTH_SHORT).show();
            } else {
                TokenStore.copy(label, value, c);
            }
        });
        box.addView(copy);
        return box;
    }
}
