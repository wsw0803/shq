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
    public static final String ROOT_NAME = "维";
    public static final String DATA_NAME = "保存数据";
    public static final String FILE_NAME = "数据.txt";

    public static File rootDir() {
        return new File(Environment.getExternalStorageDirectory(), ROOT_NAME + "/" + DATA_NAME);
    }

    public static File gameDir(String game) {
        return new File(rootDir(), game);
    }

    public static File gameFile(String game) {
        return new File(gameDir(game), FILE_NAME);
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
            e.game = parts[1].trim();
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

    public static List<Entry> loadAll() {
        List<Entry> all = new ArrayList<>();
        for (String g : GAMES) all.addAll(loadGame(g));
        return all;
    }

    public static boolean saveGameEntry(String game, String line) {
        try {
            File dir = gameDir(game);
            if (!dir.exists() && !dir.mkdirs()) return false;
            File f = gameFile(game);
            String token = grab(line, "access_token=([^&\\s]+)");
            if (!token.isEmpty() && f.exists()) {
                Scanner sc = new Scanner(f, "UTF-8");
                while (sc.hasNextLine()) {
                    if (sc.nextLine().contains(token)) { sc.close(); return false; }
                }
                sc.close();
            }
            FileWriter w = new FileWriter(f, true);
            w.append(line);
            if (!line.endsWith("\n")) w.append("\n");
            w.flush();
            w.close();
            return true;
        } catch (Exception e) {
            return false;
        }
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
