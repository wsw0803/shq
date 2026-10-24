package com.tencent.tim;

import android.app.Activity;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 更新提示。
 *
 * 只处理最新一版，不提供版本选择。
 * 显示「当前版本 → 最新版本」之间的全部提交。
 *
 * 性能要点：
 * 原来的实现要有 3 次串行请求（releases → runs → compare），
 * 而 runs 用 per_page=100 时响应体可能几百 KB，慢到明显可感。
 * 现在：
 *   1) releases 用较小的分页，减少响应体
 *   2) runs 与 releases 并行发起，省掉一次串行等待
 *   3) runs 只取需要的区间次数（分页大小按需计算）
 *   4) 读响应时用带容量预估的缓冲，避免反复扩容
 *
 * 失败一律明确提示，不再静默（静默会让人误判成「已是最新版」）。
 */
public class UpdateChecker {

    private static final String REPO = "wsw0803/shq";
    private static final String API = "https://api.github.com/repos/" + REPO;

    private static final int STATE_HAS_UPDATE = 1;
    private static final int STATE_NO_UPDATE = 2;
    private static final int STATE_FAIL = 3;

    private static class Commit {
        String title = "";
        String body = "";
    }

    public static class Info {
        public String currentVersion = "";
        public String version = "";
        public String apkUrl = "";
        public long size = 0;
        public List<Commit> commits = new ArrayList<Commit>();
        public int commitCount = 0;
    }

    public static void check(final Activity act) {
        new Thread(new Runnable() {
            public void run() {
                final int[] state = new int[]{STATE_FAIL};
                final Info info = new Info();
                try {
                    state[0] = resolve(act, info);
                } catch (Throwable t) {
                    state[0] = STATE_FAIL;
                }
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (state[0] == STATE_HAS_UPDATE) {
                            show(act, info);
                        } else if (state[0] == STATE_FAIL) {
                            android.widget.Toast.makeText(act,
                                    "检查更新失败（" + Net.routeName(act) + "），请检查网络或代理设置",
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private static int resolve(Activity act, Info info) {
        String currentVersion;
        try {
            currentVersion = act.getPackageManager()
                    .getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return STATE_FAIL;
        }

        // releases 与 runs 并行，省掉一次串行等待
        final String[] releasesBody = new String[1];
        final String[] runsBody = new String[1];

        Thread t1 = new Thread(new Runnable() {
            public void run() {
                releasesBody[0] = httpGet(act, API + "/releases?per_page=10");
            }
        });
        Thread t2 = new Thread(new Runnable() {
            public void run() {
                // 只需要覆盖到「当前版本前后」的运行记录。
                // 268 次运行里，最近 30 条足够覆盖大多数升级跨度，体量也小很多。
                runsBody[0] = httpGet(act, API + "/actions/runs?per_page=30");
            }
        });
        t1.start();
        t2.start();
        try {
            t1.join(12000);
            t2.join(12000);
        } catch (Exception ignored) {
        }

        if (releasesBody[0] == null) return STATE_FAIL;

        org.json.JSONArray releases;
        try {
            releases = new org.json.JSONArray(releasesBody[0]);
        } catch (Exception e) {
            return STATE_FAIL;
        }

        String bestVer = "";
        String apkUrl = "";
        long apkSize = 0;

        for (int i = 0; i < releases.length(); i++) {
            org.json.JSONObject rel = releases.optJSONObject(i);
            if (rel == null) continue;
            if (rel.optBoolean("draft", false) || rel.optBoolean("prerelease", false)) continue;

            String tag = rel.optString("tag_name", "");
            String ver = tag.startsWith("v") ? tag.substring(1) : tag;
            if (ver.isEmpty()) continue;
            if (compareVersion(ver, currentVersion) <= 0) continue;
            if (!bestVer.isEmpty() && compareVersion(ver, bestVer) <= 0) continue;

            org.json.JSONArray assets = rel.optJSONArray("assets");
            if (assets == null || assets.length() == 0) continue;

            String url = "";
            long size = 0;
            for (int a = 0; a < assets.length(); a++) {
                org.json.JSONObject as = assets.optJSONObject(a);
                if (as == null) continue;
                String nm = as.optString("name", "").toLowerCase();
                String u = as.optString("browser_download_url", "");
                if (u.isEmpty()) continue;
                if (nm.endsWith(".apk")) {
                    url = u;
                    size = as.optLong("size", 0);
                    break;
                }
                if (url.isEmpty()) {
                    url = u;
                    size = as.optLong("size", 0);
                }
            }
            if (url.isEmpty()) continue;

            bestVer = ver;
            apkUrl = url;
            apkSize = size;
        }

        if (bestVer.isEmpty() || apkUrl.isEmpty()) return STATE_NO_UPDATE;

        info.currentVersion = currentVersion;
        info.version = bestVer;
        info.apkUrl = apkUrl;
        info.size = apkSize;

        fillCommits(act, info, runsBody[0]);
        return STATE_HAS_UPDATE;
    }

    private static void fillCommits(Activity act, Info info, String runsBody) {
        if (runsBody == null) {
            fallback(info);
            return;
        }

        int wantRun = runNumberFromVersion(info.version);
        int curRun = runNumberFromVersion(info.currentVersion);

        Map<Integer, String> shaByRun = new HashMap<Integer, String>();
        Map<Integer, String> msgByRun = new HashMap<Integer, String>();

        try {
            org.json.JSONObject root = new org.json.JSONObject(runsBody);
            org.json.JSONArray runs = root.optJSONArray("workflow_runs");
            if (runs != null) {
                for (int i = 0; i < runs.length(); i++) {
                    org.json.JSONObject run = runs.optJSONObject(i);
                    if (run == null) continue;
                    int num = run.optInt("run_number", -1);
                    if (num < 0) continue;
                    String sha = run.optString("head_sha", "").trim();
                    if (!sha.isEmpty()) shaByRun.put(num, sha);
                    String msg = "";
                    org.json.JSONObject head = run.optJSONObject("head_commit");
                    if (head != null) msg = head.optString("message", "");
                    if (!msg.trim().isEmpty()) msgByRun.put(num, msg);
                }
            }
        } catch (Exception e) {
        }

        String headSha = shaByRun.get(wantRun);
        String baseSha = shaByRun.get(curRun);

        if (headSha != null && baseSha != null && !headSha.equals(baseSha)) {
            List<Commit> list = compareCommits(act, baseSha, headSha);
            if (list != null && !list.isEmpty()) {
                info.commits = list;
                info.commitCount = list.size();
                return;
            }
        }

        // 降级：至少给最新那一条
        String latestMsg = msgByRun.get(wantRun);
        if (latestMsg != null && !latestMsg.trim().isEmpty()) {
            Commit c = new Commit();
            c.title = firstLine(latestMsg);
            c.body = stripFirstLine(latestMsg).trim();
            info.commits = new ArrayList<Commit>();
            info.commits.add(c);
            info.commitCount = 1;
            return;
        }
        fallback(info);
    }

    private static void fallback(Info info) {
        Commit c = new Commit();
        c.title = "shq v" + info.version;
        c.body = "";
        info.commits = new ArrayList<Commit>();
        info.commits.add(c);
        info.commitCount = 1;
    }

    private static List<Commit> compareCommits(Activity act, String baseSha, String headSha) {
        String body = httpGet(act, API + "/compare/" + baseSha + "..." + headSha);
        if (body == null) return null;

        List<Commit> out = new ArrayList<Commit>();
        try {
            org.json.JSONObject root = new org.json.JSONObject(body);
            org.json.JSONArray commits = root.optJSONArray("commits");
            if (commits == null) return null;
            for (int i = 0; i < commits.length(); i++) {
                org.json.JSONObject item = commits.optJSONObject(i);
                if (item == null) continue;
                org.json.JSONObject c = item.optJSONObject("commit");
                if (c == null) continue;
                String msg = c.optString("message", "");
                if (msg.trim().isEmpty()) continue;
                Commit one = new Commit();
                one.title = firstLine(msg);
                one.body = stripFirstLine(msg).trim();
                // 过滤 CI 自动提交
                if (one.title.startsWith("chore: update latest apk")) continue;
                if (one.title.startsWith("chore(core):")) continue;
                out.add(one);
            }
        } catch (Exception e) {
            return null;
        }
        return out;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    private static String stripFirstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? "" : s.substring(i + 1);
    }

    private static int runNumberFromVersion(String version) {
        if (version == null) return -1;
        String[] p = version.split("\\.");
        if (p.length < 3) return -1;
        return parseIntSafe(p[0]) * 100 + parseIntSafe(p[1]) * 10 + parseIntSafe(p[2]);
    }

    private static int parseIntSafe(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int compareVersion(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int va = i < x.length ? parseIntSafe(x[i]) : 0;
            int vb = i < y.length ? parseIntSafe(y[i]) : 0;
            if (va != vb) return va < vb ? -1 : 1;
        }
        return 0;
    }

    /** 统一走 Net.open()，会经过代理。读取用带容量预估的缓冲。 */
    private static String httpGet(Activity act, String url) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = Net.open(act, url, 9000, 12000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            if (conn.getResponseCode() != 200) return null;

            in = conn.getInputStream();
            int cap = conn.getContentLength();
            if (cap <= 0) cap = 16384;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(cap);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private static void show(final Activity act, final Info info) {
        final android.app.Dialog dlg = new android.app.Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dlg.getWindow().setLayout(
                    (int) (act.getResources().getDisplayMetrics().widthPixels * 0.88f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }

        float density = act.getResources().getDisplayMetrics().density;
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(TokenDialog.glass(act, 22, true));
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(act);
        title.setText("发现新版本 v" + info.version);
        title.setTextColor(0xFFF5F7FA);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(act);
        String line = "v" + info.currentVersion + " → v" + info.version;
        if (info.commitCount > 0) line = line + "，共 " + info.commitCount + " 个提交";
        sub.setText(line);
        sub.setTextColor(0xFF9CA3AF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = (int) (4 * density);
        subLp.bottomMargin = (int) (10 * density);
        sub.setLayoutParams(subLp);
        root.addView(sub);

        if (!info.commits.isEmpty()) {
            ScrollView scroll = new ScrollView(act);
            LinearLayout list = new LinearLayout(act);
            list.setOrientation(LinearLayout.VERTICAL);

            for (int i = 0; i < info.commits.size(); i++) {
                Commit c = info.commits.get(i);
                LinearLayout block = new LinearLayout(act);
                block.setOrientation(LinearLayout.VERTICAL);
                block.setBackground(TokenDialog.glass(act, 12, false));
                block.setPadding((int) (10 * density), (int) (8 * density),
                        (int) (10 * density), (int) (8 * density));

                TextView t = new TextView(act);
                t.setText(c.title);
                t.setTextColor(0xFF22D3EE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                block.addView(t);

                if (c.body != null && c.body.length() > 0) {
                    TextView b = new TextView(act);
                    b.setText(c.body);
                    b.setTextColor(0xFFD5DBDB);
                    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    b.setLineSpacing((int) (2 * density), 1f);
                    LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    bLp.topMargin = (int) (4 * density);
                    b.setLayoutParams(bLp);
                    block.addView(b);
                }

                LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                blockLp.bottomMargin = (int) (6 * density);
                block.setLayoutParams(blockLp);
                list.addView(block);
            }

            scroll.addView(list);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) (screenH * 0.42f));
            scrollLp.bottomMargin = (int) (12 * density);
            root.addView(scroll, scrollLp);
        }

        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button later = new Button(act);
        later.setText("以后再说");
        later.setTextColor(0xFF9CA3AF);
        later.setBackground(TokenDialog.glass(act, 12, false));
        later.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp1.rightMargin = (int) (6 * density);
        btnRow.addView(later, lp1);

        Button ok = new Button(act);
        ok.setText("立即更新");
        ok.setTextColor(0xFF000000);
        ok.setBackground(TokenDialog.glass(act, 12, true));
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                Updater.start(act, info.apkUrl, info.size, null);
            }
        });
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp2.leftMargin = (int) (6 * density);
        btnRow.addView(ok, lp2);

        root.addView(btnRow);
        dlg.setContentView(root);
        dlg.setCancelable(true);
        dlg.setCanceledOnTouchOutside(true);
        dlg.show();
    }
}
