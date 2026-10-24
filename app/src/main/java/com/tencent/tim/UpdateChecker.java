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
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 更新提示。
 *
 * 只处理最新一版，不提供版本选择。
 * 重点是显示「当前版本」到「最新版本」之间的全部提交：
 * 中间有几个提交，就列出几条 Summary。
 *
 * 取数流程：
 *   1) Releases 找最新可用版本（跳过 draft / prerelease）
 *   2) 版本号反推 run_number：run = MAJOR*100 + MINOR*10 + PATCH
 *      与 workflow 的 Calculate Version 互为逆运算
 *   3) 用 run_number 在 /actions/runs 里定位两端各自的 head_sha
 *   4) 调 compare API 拿到区间内的提交列表
 *   5) 逐条展示：首行作标题，正文作说明
 *
 * 任一步失败都不会卡住更新本身，只是显示内容降级。
 */
public class UpdateChecker {

    private static final String REPO = "wsw0803/shq";
    private static final String RELEASES_API =
            "https://api.github.com/repos/" + REPO + "/releases?per_page=30";
    private static final String RUNS_API =
            "https://api.github.com/repos/" + REPO + "/actions/runs?per_page=100";
    private static final String COMPARE_API =
            "https://api.github.com/repos/" + REPO + "/compare/";

    /** 一条提交：首行标题 + 正文。 */
    private static class Commit {
        String title = "";
        String body = "";
    }

    /** 最新一版的更新信息。 */
    public static class Info {
        public String currentVersion = "";
        public String version = "";
        public String apkUrl = "";
        public long size = 0;
        /** 区间内的提交，按时间从旧到新。 */
        public List<Commit> commits = new ArrayList<Commit>();
        /** 提交数量。 */
        public int commitCount = 0;
    }

    public static void check(final Activity act) {
        new Thread(new Runnable() {
            public void run() {
                final Info info = findNewer(act);
                if (info == null) return;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        show(act, info);
                    }
                });
            }
        }).start();
    }

    private static Info findNewer(Activity act) {
        String currentVersion;
        try {
            currentVersion = act.getPackageManager()
                    .getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return null;
        }

        // 1) 最新 Release
        org.json.JSONArray releases = getJsonArray(RELEASES_API);
        if (releases == null) return null;

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

        if (bestVer.isEmpty() || apkUrl.isEmpty()) return null;

        Info info = new Info();
        info.currentVersion = currentVersion;
        info.version = bestVer;
        info.apkUrl = apkUrl;
        info.size = apkSize;

        fillCommits(info);
        return info;
    }

    /**
     * 拉取「当前版本 → 最新版本」之间的提交。
     *
     * 版本号 → run_number → head_sha，再用 compare API 取区间提交。
     * 任何一步拿不到就降级：至少显示最新那一条提交。
     */
    private static void fillCommits(Info info) {
        int wantRun = runNumberFromVersion(info.version);
        int curRun = runNumberFromVersion(info.currentVersion);

        Map<Integer, String> shaByRun = new HashMap<Integer, String>();
        Map<Integer, String> msgByRun = new HashMap<Integer, String>();
        collectRuns(shaByRun, msgByRun);

        String headSha = shaByRun.get(wantRun);
        String baseSha = shaByRun.get(curRun);

        // 优先：两端都能定位，取区间提交
        if (headSha != null && baseSha != null && !headSha.equals(baseSha)) {
            List<Commit> list = compareCommits(baseSha, headSha);
            if (list != null && !list.isEmpty()) {
                info.commits = list;
                info.commitCount = list.size();
                return;
            }
        }

        // 降级 1：只取最新那一条提交
        String latestMsg = msgByRun.get(wantRun);
        if (latestMsg != null && !latestMsg.trim().isEmpty()) {
            Commit c = new Commit();
            c.title = firstLine(latestMsg);
            c.body = stripFirstLine(latestMsg).trim();
            info.commits = new ArrayList<Commit>();
            info.commits.add(c);
            info.commitCount = 1;
        }
    }

    /** 遍历运行列表，建立 run_number → head_sha / 提交信息 的映射。 */
    private static void collectRuns(Map<Integer, String> shaByRun, Map<Integer, String> msgByRun) {
        org.json.JSONObject root = getJsonObject(RUNS_API);
        if (root == null) return;
        org.json.JSONArray runs = root.optJSONArray("workflow_runs");
        if (runs == null) return;

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

    /** compare API：取 base..head 之间的提交，按 api 返回顺序（旧→新）。 */
    private static List<Commit> compareCommits(String baseSha, String headSha) {
        org.json.JSONObject root = getJsonObject(COMPARE_API + baseSha + "..." + headSha);
        if (root == null) return null;

        org.json.JSONArray commits = root.optJSONArray("commits");
        if (commits == null) return null;

        List<Commit> out = new ArrayList<Commit>();
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
            // 过滤掉 CI 自动生成的提交，用户看这些没意义
            if (one.title.startsWith("chore: update latest apk")) continue;
            if (one.title.startsWith("chore(core):")) continue;
            out.add(one);
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

    /** 版本号反推 run_number，与 workflow 里 Calculate Version 互为逆运算。 */
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

    private static org.json.JSONArray getJsonArray(String url) {
        String body = httpGet(url);
        if (body == null) return null;
        try {
            return new org.json.JSONArray(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static org.json.JSONObject getJsonObject(String url) {
        String body = httpGet(url);
        if (body == null) return null;
        try {
            return new org.json.JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String httpGet(String url) {
        HttpURLConnection conn = null;
        BufferedReader br = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "shq-updater");
            conn.setConnectTimeout(9000);
            conn.setReadTimeout(9000);
            if (conn.getResponseCode() != 200) return null;
            br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (br != null) {
                try { br.close(); } catch (Exception ignored) {}
            }
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 弹窗：
     *   顶部   —— 从哪个版本升到哪个版本，中间有多少提交
     *   中间   —— 逐条列提交（首行标题 + 正文）
     *   底部   —— 以后再说 / 立即更新
     */
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

        // 顶部：版本区间 + 提交数
        TextView title = new TextView(act);
        title.setText("发现新版本 v" + info.version);
        title.setTextColor(0xFFF5F7FA);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(act);
        if (info.commitCount > 0) {
            sub.setText("v" + info.currentVersion + " → v" + info.version
                    + "，共 " + info.commitCount + " 个提交");
        } else {
            sub.setText("v" + info.currentVersion + " → v" + info.version);
        }
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

        // 中间：逐条提交
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
            // 固定高度上限，避免提交多时弹窗超出屏幕
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
            public void onClick(View v) {
                dlg.dismiss();
            }
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
