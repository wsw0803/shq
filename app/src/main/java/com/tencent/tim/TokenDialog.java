package com.tencent.tim;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.KeyEvent;
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

    public static GradientDrawable glass(Context c, int radiusDp, boolean accent) { int g = Ui.prefs(c).getInt(Ui.KEY_GLASS, 40); int a = (int) (g * 1.6f); if (a > 170) a = 170; if (a < 60) a = 60; GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.RECTANGLE); d.setCornerRadius(radiusDp * c.getResources().getDisplayMetrics().density); d.setColor(Color.argb(a, 14, 22, 36)); d.setStroke((int) c.getResources().getDisplayMetrics().density, accent ? 0x9922D3EE : 0x40FFFFFF); return d; }
    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }


            private static final java.util.List<String[]> _stage = new java.util.ArrayList<String[]>();

    public static void showChooseGameForTokens(final Context c, final TokenStore.ImportResult res) {
        if (c == null) return;
        if (res == null) { show(c, "", null); return; }
        _stage.clear();
        showChooseNext(c, res, res.unknownLines);
    }

    private static void showChooseNext(final Context c, final TokenStore.ImportResult res, final java.util.List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) { finishImport(c, res); return; }
        final java.util.List<String> rest0 = new java.util.ArrayList<String>(tokens);
        final String line0 = rest0.get(0);
        final int total = rest0.size();
        final int autoCnt = res == null ? 0 : res.resolved.size();
        final TokenStore.Entry pe0 = TokenStore.parseLine(line0, "");
        String preview = (pe0 == null) ? line0 : pe0.accessToken;
        
        java.util.List<String> gl = new java.util.ArrayList<String>(TokenStore.importGames());
        for (String ag : TokenStore.allGames()) { if (ag != null && ag.length() > 0 && !gl.contains(ag)) gl.add(ag); }
        if (!gl.contains("其他")) gl.add("其他");
        final String[] games = gl.toArray(new String[0]);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(glass(c, 22, true));
        int p = dp(c, 16);
        root.setPadding(p, p, p, p);
        TextView title = new TextView(c);
        title.setText("逐条选择游戏标签");
        title.setTextColor(0xFFF5F7FA);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView tip = new TextView(c);
        tip.setText("自动识别 " + autoCnt + " 条，需手动归类 " + total + " 条\n已归类 " + _stage.size() + " 条，当前: " + preview + "\n（全部选完并确认后才会写入，中途退出不会保存）");
        tip.setTextColor(0xFF8A9BB8);
        tip.setTextSize(11);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(c, 8), 0, dp(c, 12));
        root.addView(tip);
        TextView tokView = new TextView(c);
        tokView.setText("Token: " + preview);
        tokView.setTextColor(0xFF34D399);
        tokView.setTextSize(10); tokView.setTextIsSelectable(true);
        tokView.setGravity(Gravity.CENTER);
        tokView.setPadding(0, 0, 0, dp(c, 10));
        root.addView(tokView);
        final Dialog dlg = new Dialog(c);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    _stage.clear();
                    dlg.dismiss();
                    android.widget.Toast.makeText(c, "已取消，本次导入未写入任何内容", android.widget.Toast.LENGTH_SHORT).show();
                    show(c, "", null);
                    return true;
                }
                return false;
            }
        });
        LinearLayout cols = new LinearLayout(c);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout colA = new LinearLayout(c);
        colA.setOrientation(LinearLayout.VERTICAL);
        LinearLayout colB = new LinearLayout(c);
        colB.setOrientation(LinearLayout.VERTICAL);
        cols.addView(colA, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        cols.addView(colB, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        for (int i = 0; i < games.length; i++) {
            final String g = games[i];
            TextView btn = new TextView(c);
            btn.setText(g);
            btn.setTextColor(0xFF22D3EE);
            btn.setTextSize(15);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(0, dp(c, 14), 0, dp(c, 14));
            btn.setBackground(glass(c, 12, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(c, 4);
            lp.rightMargin = dp(c, 4);
            lp.bottomMargin = dp(c, 8);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String aid2 = TokenStore.appidOfGame(g);
                    if (aid2 == null) aid2 = "";
                    if (aid2.isEmpty()) { String _u = (pe0 == null) ? null : TokenStore.appidOfUrl(pe0.raw); if (_u != null) aid2 = _u; }
                    if (aid2.isEmpty()) aid2 = firstAppid(line0);
                    if (aid2.isEmpty()) { askAppidForOne2(c, dlg, g, res, rest0); return; }
                    String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date());
                    String keep = (pe0 == null || pe0.raw.isEmpty()) ? line0 : TokenStore.joinLine(pe0.note, pe0.time.isEmpty() ? now : pe0.time, aid2, pe0.raw.replace("|", "%7C"), pe0.mode);
                    _stage.add(new String[]{g, keep});
                    dlg.dismiss();
                    showChooseNext(c, res, new java.util.ArrayList<String>(rest0.subList(1, rest0.size())));
                }
            });
            if (i % 2 == 0) colA.addView(btn); else colB.addView(btn);
        }
        root.addView(cols);
        TextView skip = new TextView(c);
        skip.setText("跳过这条");
        skip.setTextColor(0xFFFBBF24);
        skip.setTextSize(12);
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(0, dp(c, 12), 0, 0);
        skip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                showChooseNext(c, res, new java.util.ArrayList<String>(rest0.subList(1, rest0.size())));
            }
        });
        root.addView(skip);
        TextView cancel = new TextView(c);
        cancel.setText("放弃本次导入");
        cancel.setTextColor(0xFFF87171);
        cancel.setTextSize(12);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(c, 8), 0, 0);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                _stage.clear();
                dlg.dismiss();
                android.widget.Toast.makeText(c, "已取消，未写入任何内容", android.widget.Toast.LENGTH_SHORT).show();
                show(c, "", null);
            }
        });
        root.addView(cancel);
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams wp = w.getAttributes();
            wp.width = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.94f);
            wp.height = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.80f);
            w.setAttributes(wp);
        }
        dlg.show();
    }
    private static void askAppidForOne2(final Context c, final Dialog parent, final String g, final TokenStore.ImportResult res, final java.util.List<String> tokens) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setBackground(glass(c, 20, true));
        int p2 = dp(c, 16);
        r.setPadding(p2, p2, p2, p2);
        TextView t = new TextView(c);
        t.setText("请输入「" + g + "」的 appid（仅当前这条）");
        t.setTextColor(0xFFF5F7FA);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        r.addView(t);
        final android.widget.EditText e = new android.widget.EditText(c);
        e.setHint("纯数字 appid");
        e.setTextColor(0xFFE6EEF8);
        e.setHintTextColor(0xFF8A9BB8);
        e.setTextSize(13);
        e.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
        e.setBackground(glass(c, 12, false));
        r.addView(e);
        TextView ok = new TextView(c);
        ok.setText("确定并继续");
        ok.setTextColor(0xFF34D399);
        ok.setTextSize(14);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(0, dp(c, 14), 0, dp(c, 6));
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String aid = e.getText().toString().trim();
                if (aid.isEmpty()) { android.widget.Toast.makeText(c, "appid 不能为空", android.widget.Toast.LENGTH_SHORT).show(); return; }
                String line0 = tokens.get(0);
                TokenStore.Entry pe = TokenStore.parseLine(line0, "");
                String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date());
                String keep = (pe == null || pe.raw.isEmpty()) ? line0 : TokenStore.joinLine(pe.note, pe.time.isEmpty() ? now : pe.time, aid, pe.raw.replace("|", "%7C"), pe.mode);
                _stage.add(new String[]{g, keep});
                d.dismiss();
                if (parent != null && parent.isShowing()) parent.dismiss();
                showChooseNext(c, res, new java.util.ArrayList<String>(tokens.subList(1, tokens.size())));
            }
        });
        r.addView(ok);
        TextView no = new TextView(c);
        no.setText("取消");
        no.setTextColor(0xFF8A9BB8);
        no.setTextSize(12);
        no.setGravity(Gravity.CENTER);
        no.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); }
        });
        r.addView(no);
        d.setContentView(r);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams wp = w.getAttributes();
            wp.width = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.86f);
            w.setAttributes(wp);
        }
        d.show();
    }

    private static void finishImport(final Context c, final TokenStore.ImportResult res) {
        java.util.List<String[]> all = new java.util.ArrayList<String[]>();
        if (res != null && res.resolved != null) all.addAll(res.resolved);
        all.addAll(_stage);
        _stage.clear();
        if (all.isEmpty()) {
            android.widget.Toast.makeText(c, "没有可保存的 Token", android.widget.Toast.LENGTH_SHORT).show();
            show(c, "", null);
            return;
        }
        int[] st = TokenStore.saveBatch(all);
        android.widget.Toast.makeText(c, "导入完成：新增 " + st[0] + " 条，已存在 " + st[1] + " 条" + (st[2] > 0 ? ("，失败 " + st[2] + " 条") : ""), android.widget.Toast.LENGTH_LONG).show();
        show(c, "", null);
    }

    public static void showChooseGameForTokens(final Context c, final java.util.List<String> tokens, final int added) {
        TokenStore.ImportResult r = new TokenStore.ImportResult();
        if (tokens != null) r.unknownLines.addAll(tokens);
        showChooseGameForTokens(c, r);
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
        for (String g : TokenStore.sortedGames()) {
            if (!TokenStore.loadGame(g).isEmpty()) tabs.add(g);
        }

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout tabRow = new LinearLayout(c);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(tabRow);
        root.addView(hs);
        LinearLayout actionRow = new LinearLayout(c);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] acts = {"手动添加", "导出到Download", "导入文件"};
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
                        android.widget.Toast.makeText(c, n > 0 ? ("已导出 " + n + " 条 Token 汇总到 Download/WEI/数据.txt") : "没有可导出的数据", android.widget.Toast.LENGTH_LONG).show();
                    } else {
                        if (c instanceof MainActivity) {
                            ((MainActivity) c).startImportFilePicker();
                        } else {
                            android.widget.Toast.makeText(c, "无法启动文件选择器", android.widget.Toast.LENGTH_SHORT).show();
                        }
                        dlg.dismiss();
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
        String tempInit = game;
        if (TextUtils.isEmpty(tempInit) || TokenStore.loadGame(tempInit).isEmpty()) {
            for (String tg : tabs) {
                if (!TokenStore.loadGame(tg).isEmpty()) {
                    tempInit = tg;
                    break;
                }
            }
        }
        if (TextUtils.isEmpty(tempInit) && !tabs.isEmpty()) {
            tempInit = tabs.get(0);
        }
        final String initial = tempInit;

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
            public void onClick(View v) { confirmDelete(c, e, dlg, cb); }
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

    private static void confirmDelete(final Context c, final TokenStore.Entry e, final Dialog parent, final OnPick cb) {
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
                boolean done = TokenStore.deleteEntryByLine(e.game, e.line);
                d.dismiss();
                android.widget.Toast.makeText(c, done ? "已删除" : "删除失败", android.widget.Toast.LENGTH_SHORT).show();
                if (done) {
                    if (parent != null) parent.dismiss();
                    show(c, TokenStore.nextGameWithAccounts(e.game), cb);
                }
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
            }
        });
        b.setNegativeButton("取消", null);
        android.app.AlertDialog ad = b.show(); if (ad.getWindow() != null) ad.getWindow().setBackgroundDrawable(glass(c, 22, true));
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
        final String[] games = TokenStore.importGames().toArray(new String[0]);
        final int[] pick = {0};
        final TextView gameBtn = new TextView(c);
        gameBtn.setText("游戏: " + games[0]);
        gameBtn.setTextColor(0xFF22D3EE);
        gameBtn.setTextSize(13);
        gameBtn.setGravity(Gravity.CENTER);
        gameBtn.setPadding(0, dp(c, 12), 0, dp(c, 12));
        gameBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final Dialog gd = new Dialog(c);
                gd.requestWindowFeature(Window.FEATURE_NO_TITLE);
                LinearLayout gl = new LinearLayout(c);
                gl.setOrientation(LinearLayout.VERTICAL);
                gl.setBackground(glass(c, 20, true));
                int gp = dp(c, 16);
                gl.setPadding(gp, gp, gp, gp);
                TextView gt = new TextView(c);
                gt.setText("选择游戏");
                gt.setTextColor(0xFFF5F7FA);
                gt.setTextSize(16);
                gt.setGravity(Gravity.CENTER);
                gt.setPadding(0, 0, 0, dp(c, 12));
                gl.addView(gt);
                int gi = 0;
                while (gi != games.length) {
                    final int w = gi;
                    TextView gb = new TextView(c);
                    gb.setText(games[w]);
                    gb.setTextColor(0xFF22D3EE);
                    gb.setTextSize(14);
                    gb.setGravity(Gravity.CENTER);
                    gb.setPadding(0, dp(c, 12), 0, dp(c, 12));
                    gb.setBackground(glass(c, 12, false));
                    LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    glp.bottomMargin = dp(c, 8);
                    gb.setLayoutParams(glp);
                    gb.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            pick[0] = w;
                            gameBtn.setText("游戏: " + games[w]);
                            gd.dismiss();
                        }
                    });
                    gl.addView(gb);
                    gi++;
                }
                gd.setContentView(gl);
                Window gw = gd.getWindow();
                if (gw != null) {
                    gw.setBackgroundDrawableResource(android.R.color.transparent);
                    WindowManager.LayoutParams gwp = gw.getAttributes();
                    gwp.width = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.78f);
                    gw.setAttributes(gwp);
                }
                gd.show();
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
                TokenStore.Entry pe = TokenStore.parseAnyInput(txt);
                if (pe == null || pe.accessToken == null || pe.accessToken.length() < 4) { android.widget.Toast.makeText(c, "未识别到有效 token", android.widget.Toast.LENGTH_LONG).show(); return; }
                if (pe.accessToken.matches("[0-9]+") || pe.accessToken.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}.*")) { android.widget.Toast.makeText(c, "未识别到有效 token", android.widget.Toast.LENGTH_LONG).show(); return; }
                String _now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date());
                String time = (pe.time != null && pe.time.length() > 0) ? pe.time : _now;
                String note = noteEt.getText().toString().trim();
                String game = games[pick[0]];
                String aid = pe.appid;
                if (aid == null || aid.isEmpty()) aid = TokenStore.appidOfGame(game);
                String rawSafe = pe.raw.replace("|", "%7C");
                String mode = (pe.mode == null || pe.mode.isEmpty()) ? "capture" : pe.mode;
                boolean aidOk = aid != null && aid.length() > 0;
                if (aidOk) { for (int _i = 0; _i < aid.length(); _i++) if (!Character.isDigit(aid.charAt(_i))) { aidOk = false; break; } }
                if (!aidOk) {
                    d.dismiss();
                    if (parent != null) parent.dismiss();
                    java.util.List<String> one = new java.util.ArrayList<String>();
                    one.add(TokenStore.joinLine(note, time, "", rawSafe, mode));
                    showChooseGameForTokens(c, one, 0);
                    return;
                }
                String line = TokenStore.joinLine(note, time, aid, rawSafe, mode);
                int _rc = TokenStore.addEntry(game, line);
                android.widget.Toast.makeText(c, _rc == 1 ? ("已保存到 " + game) : (_rc == 0 ? ("该 Token 已存在于 " + game) : ("保存失败: " + TokenStore.lastError)), android.widget.Toast.LENGTH_LONG).show();
                if (_rc >= 0) { d.dismiss(); if (parent != null) parent.dismiss(); show(c, game, null); }
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

    private static String firstAppid(String line) { if (line == null) return ""; String[] a = line.split(String.valueOf((char)124)); for (String s2 : a) { String t = s2.trim(); if (t.length() >= 6) { boolean nn = true; for (int i = 0; i < t.length(); i++) if (!Character.isDigit(t.charAt(i))) { nn = false; break; } if (nn) return t; } } return ""; }
    private static void askAppid(final Context c, final Dialog dlg, final String g, final java.util.List<String> tokens) { final Dialog d = new Dialog(c); d.requestWindowFeature(Window.FEATURE_NO_TITLE); LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.VERTICAL); r.setBackground(glass(c, 20, true)); int p2 = dp(c, 16); r.setPadding(p2, p2, p2, p2); TextView t = new TextView(c); t.setText("请输入「" + g + "」的 appid"); t.setTextColor(0xFFF5F7FA); t.setTextSize(15); t.setGravity(Gravity.CENTER); r.addView(t); final android.widget.EditText e = new android.widget.EditText(c); e.setHint("纯数字 appid"); e.setTextColor(0xFFE6EEF8); e.setHintTextColor(0xFF8A9BB8); e.setTextSize(13); e.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10)); e.setBackground(glass(c, 12, false)); r.addView(e); TextView ok = new TextView(c); ok.setText("保存"); ok.setTextColor(0xFF34D399); ok.setTextSize(14); ok.setGravity(Gravity.CENTER); ok.setPadding(0, dp(c, 14), 0, dp(c, 6)); ok.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { String aid = e.getText().toString().trim(); if (aid.isEmpty()) return; int n2 = 0; for (String line : tokens) { TokenStore.Entry pe = TokenStore.parseLine(line, ""); String keep = (pe == null || pe.raw.isEmpty()) ? line : TokenStore.joinLine(pe.note, pe.time.isEmpty() ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date()) : pe.time, aid, pe.raw.replace("|", "%7C"), pe.mode); { int _r3 = TokenStore.addEntry(g, keep); if (_r3 == 1) n2++; } } android.widget.Toast.makeText(c, "已保存 " + n2 + " 条到 " + g, android.widget.Toast.LENGTH_SHORT).show(); d.dismiss(); dlg.dismiss(); show(c, g, null); } }); r.addView(ok); TextView no = new TextView(c); no.setText("取消"); no.setTextColor(0xFF8A9BB8); no.setTextSize(12); no.setGravity(Gravity.CENTER); no.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { d.dismiss(); } }); r.addView(no); d.setContentView(r); Window w = d.getWindow(); if (w != null) { w.setBackgroundDrawableResource(android.R.color.transparent); WindowManager.LayoutParams wp = w.getAttributes(); wp.width = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.86f); w.setAttributes(wp); } d.show(); }
}
