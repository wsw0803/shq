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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

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
        final Dialog dlg = new Dialog(c);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(glass(c, 22, true));
        int pad = dp(c, 16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(c);
        title.setText("Token 中心");
        title.setTextColor(0xFFF5F7FA);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        final TextView tip = new TextView(c);
        tip.setTextColor(0xFF8A9BB8);
        tip.setTextSize(11);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(c, 6), 0, dp(c, 8));
        root.addView(tip);

        final List<String> tabs = new ArrayList<String>();
        for (String g : TokenStore.listGameFolders()) {
            tabs.add(g);
        }

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout tabRow = new LinearLayout(c);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(tabRow);
        root.addView(hs);
        LinearLayout actionRow = new LinearLayout(c);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] acts = {"手动添加", "导出到Download", "从Download导入"};
        for (int ai = 0; ai < acts.length; ai++) {
            final int idx = ai;
            TextView b = new TextView(c);
            b.setText(acts[ai]);
            b.setTextSize(11);
            b.setTextColor(0xFF22D3EE);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
            b.setBackground(glass(c, 10, false));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            blp.leftMargin = dp(c, 3);
            blp.rightMargin = dp(c, 3);
            b.setLayoutParams(blp);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (idx == 0) showAddDialog(c, dlg);
                    else if (idx == 1) {
                        int n = TokenStore.exportAll();
                        android.widget.Toast.makeText(c, n > 0 ? ("已导出 " + n + " 个文件到 Download/WEI") : "没有可导出的数据", android.widget.Toast.LENGTH_LONG).show();
                    } else {
                        int n = TokenStore.importAll();
                        android.widget.Toast.makeText(c, n > 0 ? ("已导入 " + n + " 条新记录") : "未发现新记录", android.widget.Toast.LENGTH_LONG).show();
                        dlg.dismiss();
                        show(c, "", cb);
                    }
                }
            });
            actionRow.addView(b);
        }
        root.addView(actionRow);


        final ScrollView scroll = new ScrollView(c);
        final LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(c, 10), 0, 0);
        scroll.addView(box);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        final TextView[] last = new TextView[1];
        final String initial = TextUtils.isEmpty(game) ? (tabs.isEmpty() ? "" : tabs.get(0)) : game;

        for (int i = 0; i < tabs.size(); i++) {
            final String g = tabs.get(i);
            final TextView tb = new TextView(c);
            tb.setText(g);
            tb.setTextSize(12);
            tb.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(c, 8);
            tb.setLayoutParams(lp);
            tb.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (last[0] != null) last[0].setBackground(glass(c, 12, false));
                    tb.setBackground(glass(c, 12, true));
                    last[0] = tb;
                    fill(c, box, tip, cb, dlg, g);
                }
            });
            if (g.equals(initial)) {
                tb.setBackground(glass(c, 12, true));
                last[0] = tb;
            } else {
                tb.setBackground(glass(c, 12, false));
            }
            tabRow.addView(tb);
        }

        if (tabs.isEmpty()) {
            tip.setText("暂无 Token");
            TextView empty = new TextView(c);
            empty.setText("还没有保存任何账号\n抓号后会自动生成 WEI/游戏名.txt\n目录: " + TokenStore.rootDir().getAbsolutePath());
            empty.setTextColor(0xFFB9C6DA);
            empty.setTextSize(12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(pad, pad * 2, pad, pad * 2);
            box.addView(empty);
        } else {
            fill(c, box, tip, cb, dlg, initial);
        }

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
            WindowManager.LayoutParams wp = w.getAttributes();
            wp.width = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.92f);
            wp.height = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.78f);
            w.setAttributes(wp);
        }
        dlg.show();
    }

    private static void fill(final Context c, final LinearLayout box, final TextView tip,
                             final OnPick cb, final Dialog dlg, final String game) {
        box.removeAllViews();
        final List<TokenStore.Entry> list = TokenStore.loadGame(game);
        tip.setText(game + " · " + list.size() + " 个账号" + (cb == null ? "" : " · 点击即登录"));
        if (list.isEmpty()) {
            TextView empty = new TextView(c);
            empty.setText("该游戏暂无账号\n文件: " + TokenStore.gameFile(game).getAbsolutePath());
            empty.setTextColor(0xFFB9C6DA);
            empty.setTextSize(12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(c, 8), dp(c, 40), dp(c, 8), dp(c, 40));
            box.addView(empty);
            return;
        }
        for (TokenStore.Entry e : list) box.addView(card(c, e, cb, dlg));
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

        if (!TextUtils.isEmpty(e.note)) {
            TextView nt = new TextView(c);
            nt.setText("备注: " + e.note);
            nt.setTextColor(0xFFFBBF24);
            nt.setTextSize(11);
            nt.setPadding(0, 0, 0, dp(c, 6));
            card.addView(nt);
        }

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView copy = new TextView(c);
        copy.setText("复制本账号");
        copy.setTextColor(0xFF34D399);
        copy.setTextSize(12);
        copy.setPadding(0, dp(c, 10), dp(c, 30), dp(c, 10));
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { TokenStore.copy(e.game + "账号", segment(e), c); }
        });
        row.addView(copy);

        TextView noteBtn = new TextView(c);
        noteBtn.setText("备注");
        noteBtn.setTextColor(0xFFFBBF24);
        noteBtn.setTextSize(12);
        noteBtn.setPadding(dp(c, 20), dp(c, 10), dp(c, 8), dp(c, 10));
        noteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showNoteDialog(c, e, dlg, cb); }
        });
        row.addView(noteBtn);

        TextView del = new TextView(c);
        del.setText("删除");
        del.setTextColor(0xFFF87171);
        del.setTextSize(12);
        del.setPadding(dp(c, 20), dp(c, 10), dp(c, 8), dp(c, 10));
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmDelete(c, e, dlg); }
        });
        row.addView(del);

        if (cb != null) {
            TextView use = new TextView(c);
            use.setText("用此账号登录 →");
            use.setTextColor(0xFF22D3EE);
            use.setTextSize(12);
            use.setPadding(dp(c, 20), dp(c, 10), 0, dp(c, 10));
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


    private static void showNoteDialog(final Context c, final TokenStore.Entry e, final Dialog parent, final OnPick cb) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(c);
        b.setTitle("设置备注");
        final android.widget.EditText et = new android.widget.EditText(c);
        et.setText(e.note);
        et.setHint("输入备注...");
        et.setTextColor(0xFFE6EEF8);
        et.setHintTextColor(0xFF8A9BB8);
        b.setView(et);
        b.setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface dialog, int which) {
                String newNote = et.getText().toString().trim();
                boolean done = TokenStore.updateNote(e.game, e.accessToken, newNote);
                android.widget.Toast.makeText(c, done ? "备注已保存" : "保存失败", android.widget.Toast.LENGTH_SHORT).show();
                if (done) {
                    e.note = newNote;
                    if (parent != null && parent.isShowing()) parent.dismiss();
                    show(c, e.game, cb);
                }
                if (done && parent != null) parent.dismiss();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    public static String segment(TokenStore.Entry e) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(e.time)) sb.append(e.time).append(" | ");
        if (!TextUtils.isEmpty(e.game)) sb.append(e.game).append(" | ");
        sb.append(TokenStore.rebuildUrl(e));
        if (!TextUtils.isEmpty(e.mode)) sb.append(" | ").append(e.mode);
        return sb.toString();
    }
    private static void showAddDialog(final Context c, final Dialog parent) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(glass(c, 20, true));
        int p = dp(c, 16);
        root.setPadding(p, p, p, p);
        TextView t = new TextView(c);
        t.setText("手动添加 Token");
        t.setTextColor(0xFFF5F7FA);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        root.addView(t);
        final String[] games = TokenStore.listGameFolders().toArray(new String[0]);
        final int[] pick = {0};
        final TextView gameBtn = new TextView(c);
        gameBtn.setText("游戏: " + games[0]);
        gameBtn.setTextColor(0xFF22D3EE);
        gameBtn.setTextSize(13);
        gameBtn.setGravity(Gravity.CENTER);
        gameBtn.setPadding(0, dp(c, 12), 0, dp(c, 12));
        gameBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(c).setTitle("选择游戏")
                    .setItems(games, new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface dd, int w) {
                            pick[0] = w;
                            gameBtn.setText("游戏: " + games[w]);
                        }
                    }).show();
            }
        });
        root.addView(gameBtn);
        final android.widget.EditText et = new android.widget.EditText(c);
        et.setHint("粘贴 access_token 或完整 auth:// 链接");
        et.setTextColor(0xFFE6EEF8);
        et.setHintTextColor(0xFF8A9BB8);
        et.setTextSize(12);
        et.setMinLines(3);
        et.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
        et.setBackground(glass(c, 12, false));
        root.addView(et);
        final android.widget.EditText noteEt = new android.widget.EditText(c);
        noteEt.setHint("备注（可留空）");
        noteEt.setTextColor(0xFFE6EEF8);
        noteEt.setHintTextColor(0xFF8A9BB8);
        noteEt.setTextSize(12);
        noteEt.setSingleLine(true);
        noteEt.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
        noteEt.setBackground(glass(c, 12, false));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(c, 8);
        noteEt.setLayoutParams(nlp);
        root.addView(noteEt);
        TextView ok = new TextView(c);
        ok.setText("保 存");
        ok.setTextColor(0xFF34D399);
        ok.setTextSize(14);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(0, dp(c, 16), 0, dp(c, 8));
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String txt = et.getText().toString().trim();
                if (txt.isEmpty()) { android.widget.Toast.makeText(c, "内容为空", android.widget.Toast.LENGTH_SHORT).show(); return; }
                String raw = txt;
                if (!raw.contains("access_token=")) raw = "access_token=" + raw;
                String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date());
                String note = noteEt.getText().toString().trim();
                String line = time + " | " + games[pick[0]] + " | " + raw + " | 手动" + (note.isEmpty() ? "" : " | " + note);
                boolean done = TokenStore.saveGameEntry(games[pick[0]], line);
                android.widget.Toast.makeText(c, done ? "已保存到 " + games[pick[0]] : ("保存失败: " + TokenStore.lastError), android.widget.Toast.LENGTH_LONG).show();
                if (done) { d.dismiss(); if (parent != null) parent.dismiss(); show(c, games[pick[0]], null); }
            }
        });
        root.addView(ok);
        TextView cancel = new TextView(c);
        cancel.setText("取消");
        cancel.setTextColor(0xFF8A9BB8);
        cancel.setTextSize(12);
        cancel.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { d.dismiss(); } });
        root.addView(cancel);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) { w.setBackgroundDrawableResource(android.R.color.transparent); WindowManager.LayoutParams wp = w.getAttributes(); wp.width = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.86f); w.setAttributes(wp); }
        d.show();
    }

}
