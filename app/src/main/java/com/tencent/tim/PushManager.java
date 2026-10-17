package com.tencent.tim;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class PushManager {

    public static final String PREF = "push_prefs";
    public static final String KEY_TOKEN = "gh_token";
    public static final String KEY_REPO = "repo";
    public static final String KEY_BRANCH = "branch";
    public static final String KEY_DIR = "dir";

    public static final String DEFAULT_REPO = "wsw0803/shq";
    public static final String DEFAULT_BRANCH = "main";
    public static final String DEFAULT_DIR = "WEI";

    public interface Log {
        void onLog(String line);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String token(Context c) {
        String t = prefs(c).getString(KEY_TOKEN, "");
        return t == null ? "" : t.trim();
    }

    public static void setToken(Context c, String t) {
        prefs(c).edit().putString(KEY_TOKEN, t == null ? "" : t.trim()).apply();
    }

    public static String repo(Context c) {
        String r = prefs(c).getString(KEY_REPO, "");
        return (r == null || r.trim().isEmpty()) ? DEFAULT_REPO : r.trim();
    }

    public static String branch(Context c) {
        String b = prefs(c).getString(KEY_BRANCH, "");
        return (b == null || b.trim().isEmpty()) ? DEFAULT_BRANCH : b.trim();
    }

    public static String remoteDir(Context c) {
        String d = prefs(c).getString(KEY_DIR, "");
        return (d == null || d.trim().isEmpty()) ? DEFAULT_DIR : d.trim();
    }

    private static final java.util.regex.Pattern TOK = java.util.regex.Pattern.compile("gh[pousr]_[A-Za-z0-9]{10,}");

    public static String scrub(String s) {
        if (s == null) return "";
        java.util.regex.Matcher m = TOK.matcher(s);
        StringBuffer sb = new StringBuffer();
        boolean hit = false;
        while (m.find()) {
            hit = true;
            String t = m.group();
            String r = t.length() <= 8 ? "****" : t.substring(0, 8) + "****";
            m.appendReplacement(sb, r);
        }
        if (!hit) return s;
        m.appendTail(sb);
        return sb.toString();
    }

    public static String mask(String tk) {
        if (tk == null || tk.trim().isEmpty()) return "未设置";
        String t = tk.trim();
        if (t.length() <= 8) return "****";
        return t.substring(0, 8) + "****";
    }

    public static void pushAll(Context ctx, Log log) {
        String tk = token(ctx);
        if (tk.isEmpty()) {
            log.onLog("推送" + " 未设置 Token，请先设置");
            return;
        }
        final String rp = repo(ctx);
        final String br = branch(ctx);
        final String dr = remoteDir(ctx);
        File[] files = TokenStore.rootDir().listFiles((d, n) -> n.endsWith(".txt"));
        if (files == null || files.length == 0) {
            log.onLog("[推送] 没有可上传的数据文件");
            return;
        }
        log.onLog("[推送] 仓库: " + rp + " 分支: " + br + " 目录: " + dr);
        log.onLog("[推送] Token: " + mask(tk));
        int ok = 0;
        int fail = 0;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String remote = dr + "/" + f.getName();
            try {
                boolean r = pushOne(tk, rp, br, remote, f);
                log.onLog("[推送] " + f.getName() + (r ? " 成功" : " 失败"));
                if (r) ok++; else fail++;
            } catch (Exception e) {
                log.onLog("[推送] " + f.getName() + " 失败: " + e.getMessage());
                fail++;
            }
        }
        log.onLog("[推送] 完成，成功 " + ok + " 个，失败 " + fail + " 个");
    }

    private static boolean pushOne(String token, String repo, String branch, String remotePath, File f) throws Exception {
        String encoded = URLEncoder.encode(remotePath, "UTF-8").replace("+", "%20");
        String api = "https://api.github.com/repos/" + repo + "/contents/" + encoded;
        String sha = null;

        HttpURLConnection g = (HttpURLConnection) new URL(api + "?ref=" + URLEncoder.encode(branch, "UTF-8")).openConnection();
        g.setRequestMethod("GET");
        g.setConnectTimeout(15000);
        g.setReadTimeout(15000);
        g.setRequestProperty("Authorization", "token " + token);
        g.setRequestProperty("Accept", "application/vnd.github+json");
        if (g.getResponseCode() == 200) {
            JSONObject j = new JSONObject(readAll(g.getInputStream()));
            String s = j.optString("sha", "");
            if (!s.isEmpty()) sha = s;
        }
        g.disconnect();

        JSONObject req = new JSONObject();
        req.put("message", "chore: update " + f.getName());
        req.put("content", Base64.encodeToString(readFile(f), Base64.NO_WRAP));
        if (sha != null) req.put("sha", sha);
        req.put("branch", branch);

        HttpURLConnection p = (HttpURLConnection) new URL(api).openConnection();
        p.setRequestMethod("PUT");
        p.setDoOutput(true);
        p.setConnectTimeout(15000);
        p.setReadTimeout(15000);
        p.setRequestProperty("Authorization", "token " + token);
        p.setRequestProperty("Accept", "application/vnd.github+json");
        p.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        OutputStream os = p.getOutputStream();
        os.write(req.toString().getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = p.getResponseCode();
        p.disconnect();
        return code == 200 || code == 201;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readFile(File f) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
