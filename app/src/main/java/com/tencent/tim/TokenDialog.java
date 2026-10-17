package com.tencent.tim;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TokenDialog {

    public interface OnPick {
        void onPick(TokenStore.Entry e);
    }

    public static GradientDrawable glass(Context c, int radiusDp, boolean accent) {
        int g = Ui.prefs(c).getInt(Ui.KEY_GLASS, 40);
        int a = (int) (g * 1.6f);
        if (a > 170) a = 170;
        if (a < 60) a = 60;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusDp * c.getResources().getDisplayMetrics().density);
        d.setColor(Color.argb(a, 14, 22, 36));
        d.setStroke((int) c.getResources().getDisplayMetrics().density, accent ? 0x9922D3EE : 0x40FFFFFF);
        return d;
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    public static void show(final Context c, String game, final OnPick pick) {
        if (c == null) return;
        final OnPick cb = pick;
        Map<String, List<TokenStore.Entry>> groups = new LinkedHashMap<String, List<TokenStore.Entry>>();
        if (!TextUtils.isEmpty(game)) {
            List<TokenStore.Entry> one = TokenStore.loadGame(game);
            if (!one.isEmpty()) groups.put(game, one);
        } else {
            for (String g : TokenStore.listGameFolders()) {
                List<TokenStore.Entry> l = TokenStore.loadGame(g);
                if (!l.isEmpty()) groups.put(g, l);
            }
        }
        int total = 0;
        for (List<TokenStore.Entry> l : groups.values()) total += l.size();

        final Dialog dlg = new Dialog(c);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(glass(c, 22, true));
        int pad = dp(c, 16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(c);
        title.setText(TextUtils.isEmpty(game) ? "Token 列表" : game + " Token");
        title.setTextColor(0xFFF5F7FA);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView tip = new TextView(c);
        tip.setText(total + " 个账号 · 每个账号一段 · " + (cb == null ? "单独复制" : "点击即登录"));
        tip.setTextColor(0xFF8A9BB8);
        tip.setTextSize(11);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(c, 6), 0, dp(c, 12));
        root.addView(tip);

        ScrollView scroll = new ScrollView(c);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(box);

        if (total == 0) {
            TextView empty = new TextView(c);
            empty.setText("暂无 Token\n" + TokenStore.rootDir().getAbsolutePath());
            empty.setTextColor(0xFFB9C6DA);
            empty.setTextSize(12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(pad, pad * 2, pad, pad * 2);
            box.addView(empty);
        } else {
            for (Map.Entry<String, List<TokenStore.Entry>> g : groups.entrySet()) {
                TextView head = new TextView(c);
                head.setText("◆ " + g.getKey() + "  (" + g.getValue().size() + ")");
                head.setTextColor(0xFF22D3EE);
                head.setTextSize(13);
                head.setPadding(dp(c, 4), dp(c, 10), 0, dp(c, 6));
                box.addView(head);
                for (TokenStore.Entry e : g.getValue()) box.addView(card(c, e, cb, dlg));
            }
        }
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView close = new TextView(c);
        close.setText("关 闭");
        close.setTextColor(0xFF22D3EE);
        close.setTextSize(13);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(c, 12), 0, dp(c, 4));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        root.addView(close);

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.92f);
            lp.height = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.72f);
            w.setAttributes(lp);
        }
        dlg.show();
    }

    private static View card(final Context c, final TokenStore.Entry e, final OnPick cb, final Dialog dlg) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glass(c, 14, false));
        int p = dp(c, 12);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 10);
        card.setLayoutParams(lp);

        TextView head = new TextView(c);
        head.setText(TextUtils.isEmpty(e.time) ? "未记录时间" : e.time);
        head.setTextColor(0xFF34D399);
        head.setTextSize(11);
        card.addView(head);

        TextView tk = new TextView(c);
        tk.setText("access_token  " + e.shortToken());
        tk.setTextColor(0xFFE6EEF8);
        tk.setTextSize(10);
        tk.setPadding(0, dp(c, 4), 0, dp(c, 6));
        card.addView(tk);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView copy = new TextView(c);
        copy.setText("复制本账号");
        copy.setTextColor(0xFF34D399);
        copy.setTextSize(12);
        copy.setPadding(0, dp(c, 12), dp(c, 40), dp(c, 12));
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { TokenStore.copy(e.game + "账号", segment(e), c); }
        });
        row.addView(copy);
        TextView del = new TextView(c);
        del.setText("删除");
        del.setTextColor(0xFFF87171);
        del.setTextSize(12);
        del.setPadding(dp(c, 40), dp(c, 12), dp(c, 8), dp(c, 12));
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmDelete(c, e, dlg); }
        });
        row.addView(del);

        if (cb != null) {
            TextView use = new TextView(c);
            use.setText("用此账号登录 →");
            use.setTextColor(0xFF22D3EE);
            use.setTextSize(12);
            use.setPadding(0, dp(c, 12), 0, dp(c, 12));
            use.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { dlg.dismiss(); cb.onPick(e); }
            });
            row.addView(use);
        }
        card.addView(row);
        return card;
    }

    private static void confirmDelete(final Context c, final TokenStore.Entry e, final Dialog parent) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(glass(c, 20, true));
        int p = dp(c, 18);
        root.setPadding(p, p, p, p);
        TextView t = new TextView(c);
        t.setText("确认删除这个账号？");
        t.setTextColor(0xFFF5F7FA);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        root.addView(t);
        TextView sub = new TextView(c);
        sub.setText((e.game == null ? "" : e.game) + "  " + e.shortToken() + "\n删除后不可恢复");
        sub.setTextColor(0xFF8A9BB8);
        sub.setTextSize(11);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(c, 8), 0, dp(c, 16));
        root.addView(sub);
        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = new TextView(c);
        cancel.setText("取消");
        cancel.setTextColor(0xFF8A9BB8);
        cancel.setTextSize(13);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(c, 10), 0, dp(c, 10));
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); }
        });
        TextView ok = new TextView(c);
        ok.setText("确认删除");
        ok.setTextColor(0xFFF87171);
        ok.setTextSize(13);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(0, dp(c, 10), 0, dp(c, 10));
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean done = TokenStore.deleteEntry(e.game, e.accessToken);
                d.dismiss();
                android.widget.Toast.makeText(c, done ? "已删除" : "删除失败", android.widget.Toast.LENGTH_SHORT).show();
                if (done && parent != null) parent.dismiss();
            }
        });
        btns.addView(cancel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(ok, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(btns);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams wp = w.getAttributes();
            wp.width = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.78f);
            w.setAttributes(wp);
        }
        d.show();
    }

    public static String segment(TokenStore.Entry e) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(e.time)) sb.append(e.time).append(" | ");
        if (!TextUtils.isEmpty(e.game)) sb.append(e.game).append(" | ");
        sb.append(TokenStore.rebuildUrl(e));
        if (!TextUtils.isEmpty(e.mode)) sb.append(" | ").append(e.mode);
        return sb.toString();
    }
}
