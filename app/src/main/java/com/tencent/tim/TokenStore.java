package com.tencent.tim;

import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TokenStore {

    public static final String[] GAMES = {"和平", "王者", "三角洲", "无畏契约"};
    public static final String[] APPIDS = {"1106467070", "1104466820", "1110543085", "1111677210"};
    public static final String ROOT_NAME = "WEI";
    public static final String DATA_NAME = "";
    public static final String FILE_NAME = "数据.txt";
    public static String lastError = "";

    public static File rootDir() {
        return new File(Environment.getExternalStorageDirectory(), ROOT_NAME);
    }

    public static File gameDir(String game) {
        return rootDir();
    }

    public static File gameFile(String game) {
        return new File(rootDir(), game + ".txt");
    }

    public static String appidOfGame(String game) {
        for (int i = 0; i < GAMES.length; i++) {
            if (GAMES[i].equals(game)) return APPIDS[i];
        }
        return APPIDS[0];
    }

    public static String gameOfAppid(String appid) {
        if (appid == null) return null;
        for (int i = 0; i < APPIDS.length; i++) {
            if (APPIDS[i].equals(appid)) return GAMES[i];
        }
        return null;
    }

    public static String appidOfUrl(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("client_id=([0-9]+)").matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("pt_3rd_aid=([0-9]+)").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    public static String gameOfUrl(String url) {
        String g = gameOfAppid(appidOfUrl(url));
        return g == null ? "" : g;
    }

    public static class Entry {
        public String time = "";
        public String game = "";
        public String raw = "";
        public String mode = "";
        public String accessToken = "";
        public String openid = "";
        public String payToken = "";
        public String expiresIn = "";

        public String shortToken() {
            if (accessToken.length() <= 12) return accessToken;
            return accessToken.substring(0, 5) + "..." + accessToken.substring(accessToken.length() - 5);
        }
    }

    private static String grab(String s, String regex) {
        if (s == null) return "";
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : "";
    }

    public static Entry parseLine(String line, String game) {
        if (line == null) return null;
        line = line.trim();
        if (line.isEmpty()) return null;
        Entry e = new Entry();
        e.game = game == null ? "" : game;
        String[] parts = line.split("\\|");
        if (parts.length >= 4) {
            e.time = parts[0].trim();
            e.raw = parts[2].trim();
            e.mode = parts[3].trim();
        } else if (parts.length >= 2) {
            e.time = parts[0].trim();
            e.raw = parts[1].trim();
        } else {
            e.raw = line;
        }
        e.accessToken = grab(e.raw, "access_token=([^&\\s]+)");
        e.openid = grab(e.raw, "openid=([^&\\s]+)");
        e.payToken = grab(e.raw, "pay_token=([^&\\s]+)");
        e.expiresIn = grab(e.raw, "expires_in=([^&\\s]+)");
        return e;
    }

    public static List<Entry> loadGame(String game) {
        List<Entry> list = new ArrayList<>();
        File f = gameFile(game);
        if (!f.exists()) return list;
        try {
            Scanner sc = new Scanner(f, "UTF-8");
            while (sc.hasNextLine()) {
                Entry e = parseLine(sc.nextLine(), game);
                if (e != null && !e.accessToken.isEmpty()) list.add(e);
            }
            sc.close();
        } catch (Exception ignored) {}
        return list;
    }

    public static java.util.List<String> listGameFolders() {
        java.util.List<String> names = new ArrayList<String>();
        for (String g : GAMES) names.add(g);
        if (!names.contains("其他")) names.add("其他");
        return names;
    }

    public static String gameOfPackage(String t) {
        if (t == null) return "";
        String s = t.toLowerCase();
        if (s.contains("pubg")) return "和平";
        if (s.contains("sgame")) return "王者";
        if (s.contains("dfm") || s.contains("deltaforce")) return "三角洲";
        if (s.contains("codev") || s.contains("valorant")) return "无畏契约";
        return "";
    }

    public static String detectGameByRoot() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes("dumpsys activity recents\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) { String g = gameOfPackage(ln); if (!g.isEmpty()) { br.close(); p.waitFor(); return g; } }
            br.close();
            p.waitFor();
            return gameOfPackage(sb.toString());
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean rootAppend(File f, String line) {
        try {
            String txt = line.endsWith("\n") ? line : line + "\n";
            String payload = android.util.Base64.encodeToString(txt.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            String dir = f.getParentFile().getAbsolutePath();
            String path = f.getAbsolutePath();
            String cmd = "mkdir -p '" + dir + "'; echo " + payload + " | base64 -d >> '" + path + "'; chmod 666 '" + path + "'";
            Process p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            int code = p.waitFor();
            if (code != 0) lastError = "root 退出码 " + code; return code == 0;
        } catch (Exception e) {
            return RootUtil.append(f, line);
        }
    }

    public static List<Entry> loadAll() {
        List<Entry> all = new ArrayList<>();
        for (String g : listGameFolders()) all.addAll(loadGame(g));
        return all;
    }

    public static boolean saveGameEntry(String game, String line) {
        if (game == null || game.isEmpty() || "未知游戏".equals(game)) game = "其他";
        File f = gameFile(game);
        String token = grab(line, "access_token=([^&\\s]+)");
        if (!token.isEmpty() && f.exists()) {
            try {
                Scanner sc = new Scanner(f, "UTF-8");
                while (sc.hasNextLine()) {
                    if (sc.nextLine().contains(token)) { sc.close(); lastError = "该账号已存在，未重复写入"; return false; }
                }
                sc.close();
            } catch (Exception ignored) {}
        }
        try {
            File dir = gameDir(game);
            if (!dir.exists()) dir.mkdirs();
            FileWriter w = new FileWriter(f, true);
            w.append(line);
            if (!line.endsWith("\n")) w.append("\n");
            w.flush();
            w.close();
            if (f.exists()) return true;
        } catch (Exception ignored) {}
        return RootUtil.append(f, line);
    }

    public static String rebuildUrl(Entry e) {
        if (e.raw != null && e.raw.contains("access_token=")) return e.raw;
        StringBuilder sb = new StringBuilder("auth://tauth.qq.com/?#");
        sb.append("access_token=").append(e.accessToken);
        if (!e.expiresIn.isEmpty()) sb.append("&expires_in=").append(e.expiresIn);
        sb.append("&openid=").append(e.openid);
        sb.append("&pay_token=").append(e.payToken);
        return sb.toString();
    }

    public static boolean deleteEntry(String game, String token) {
        try {
            File f = gameFile(game);
            if (!f.exists()) return false;
            java.util.List<String> keep = new java.util.ArrayList<String>();
            Scanner sc = new Scanner(f, "UTF-8");
            while (sc.hasNextLine()) {
                String ln = sc.nextLine();
                if (ln.contains(token)) continue;
                keep.add(ln);
            }
            sc.close();
            FileWriter w = new FileWriter(f, false);
            for (int i = 0; i < keep.size(); i++) {
                w.append(keep.get(i));
                w.append("\n");
            }
            w.flush();
            w.close();
            return true;
        } catch (Exception e) {
            return RootUtil.append(f, line);
        }
    }

    public static void copy(String label, String text, android.content.Context c) {
        if (c == null) return;
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) c.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
            android.widget.Toast.makeText(c, "已复制" + label, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }
}
