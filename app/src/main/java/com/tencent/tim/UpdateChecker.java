package com.tencent.tim;

import android.app.Activity;
import android.content.pm.PackageManager;
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

/**
 * 更新提示。
 *
 * 只处理「最新一版」，不再提供版本选择。
 * 显示的是 GitHub Actions 该次运行的标题与 Summary（提交信息的标题与正文），
 * 而不是构建版本号。
 *
 * 数据来源：
 *   1) Releases 拿最新可用的 APK 地址与大小
 *   2) workflow 的版本号由 GITHUB_RUN_NUMBER 推导：
 *        MAJOR = COUNT / 100, MINOR = (COUNT % 100) / 10, PATCH = COUNT % 10
 *      反推 run_number 后去 /actions/runs 查 display_title + head_commit.message
 */
public class UpdateChecker {

    private static final String RELEASES_API =
            "https://api.github.com/repos/wsw0803/shq/releases?per_page=30";
    private static final String RUNS_API =
            "https://api.github.com/repos/wsw0803/shq/actions/runs?per_page=60";

    /** 最新一版的更新信息。 */
    public static class Info {
        public String version = "";
        /** Actions 运行标题（提交信息首行）。 */
        public String title = "";
        /** Actions 运行 Summary（提交信息正文）。 */
        public String summary = "";
        public String apkUrl = "";
        public long size = 0;
    }

    /** 检查是否有比当前更新的版本，有则弹窗；没有则什么都不做。 */
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

        // 1) 找最新的 Release（跳过草稿与预发布）
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
        info.version = bestVer;
        info.apkUrl = apkUrl;
        info.size = apkSize;

        // 2) 用版本号反推 run_number，取该次运行的标题与提交正文
        fillFromActions(info);

        // 兜底：拿不到就先显示版本号，避免空白
        if (info.title.isEmpty()) info.title = "shq v" + bestVer;
        return info;
    }

    /**
     * 版本号反推 run_number：run = MAJOR*100 + MINOR*10 + PATCH。
     * 与 workflow 里的 Calculate Version 步骤互为逆运算。
     */
    private static int runNumberFromVersion(String version) {
        if (version == null) return -1;
        String[] p = version.split("\\.");
        if (p.length < 3) return -1;
        return parseIntSafe(p[0]) * 100 + parseIntSafe(p[1]) * 10 + parseIntSafe(p[2]);
    }

    private static void fillFromActions(Info info) {
        int want = runNumberFromVersion(info.version);
        if (want < 0) return;

        org.json.JSONObject root = getJsonObject(RUNS_API);
        if (root == null) return;
        org.json.JSONArray runs = root.optJSONArray("workflow_runs");
        if (runs == null) return;

        for (int i = 0; i < runs.length(); i++) {
            org.json.JSONObject run = runs.optJSONObject(i);
            if (run == null) continue;
            if (run.optInt("run_number", -1) != want) continue;

            String title = run.optString("display_title", "").trim();
            String msg = "";
            org.json.JSONObject head = run.optJSONObject("head_commit");
            if (head != null) msg = head.optString("message", "");

            if (title.isEmpty() && !msg.isEmpty()) {
                title = firstLine(msg);
            }
            info.title = title;
            // Summary 取提交信息里去掉首行的部分
            info.summary = stripFirstLine(msg).trim();
            return;
        }
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
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
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
     * 弹窗：标题是 Actions 运行标题，正文是该次提交的 Summary。
     * 只有一个「立即更新」按钮（附「以后再说」），不强制更新。
     */
    private static void show(final Activity act, final Info info) {
        final android.app.Dialog dlg = new android.app.Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dlg.getWindow().setLayout(
                    (int) (act.getResources().getDisplayMetrics().widthPixels * 0.86f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }

        float density = act.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(TokenDialog.glass(act, 22, true));
        root.setPadding(pad, pad, pad, pad);

        // 标题：Actions 运行标题
        TextView title = new TextView(act);
        title.setText(info.title);
        title.setTextColor(0xFFF5F7FA);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        // 版本号单独小字标注，不与标题混在一起
        TextView ver = new TextView(act);
        ver.setText("v" + info.version);
        ver.setTextColor(0xFF9CA3AF);
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ver.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        verLp.topMargin = (int) (4 * density);
        verLp.bottomMargin = (int) (10 * density);
        ver.setLayoutParams(verLp);
        root.addView(ver);

        // Summary：可滚动，避免正文过长撑爆弹窗
        if (info.summary != null && info.summary.length() > 0) {
            ScrollView scroll = new ScrollView(act);
            TextView body = new TextView(act);
            body.setText(info.summary);
            body.setTextColor(0xFFD5DBDB);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            body.setLineSpacing((int) (2 * density), 1f);
            body.setPadding((int) (10 * density), (int) (10 * density),
                    (int) (10 * density), (int) (10 * density));
            body.setBackground(TokenDialog.glass(act, 12, false));
            scroll.addView(body);

            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
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
