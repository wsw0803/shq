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
    public static android.content.Context appContext = null;
    public static void init(android.content.Context c) { if (c != null) appContext = c.getApplicationContext(); }

    public static File rootDir() {
        File dir = new File(appContext.getFilesDir(), ROOT_NAME);
        try { if (!dir.exists()) dir.mkdirs(); } catch (Throwable ignored) {}
        return dir;
    }

    public static File downloadDir() {
        File d = new File(Environment.getExternalStorageDirectory(), "Download");
        try { if (!d.exists()) d.mkdirs(); } catch (Throwable ignored) {}
        return d;
    }

    public static File gameDir(String game) {
        return rootDir();
    }

    public static File gameFile(String game) {
        return new File(rootDir(), game + ".txt");
    }

    public static String appidOfGame(String game) {
        for (String[] g : cloudGames()) { if (g[0].equals(game)) return g[1]; }
        return APPIDS[0];
    }

    public static String gameOfAppid(String appid) {
        if (appid == null) return null;
        for (String[] g : cloudGames()) { if (g[1].equals(appid)) return g[0]; }
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
        public String note = "";

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
            if (e.game.isEmpty()) e.game = parts[1].trim();
            e.mode = parts[3].trim();
            if (parts.length >= 5) { StringBuilder nb = new StringBuilder(); for (int k = 4; k < parts.length; k++) { if (k > 4) nb.append((char)124); nb.append(parts[k]); } e.note = nb.toString().trim(); }
        } else if (parts.length >= 2) {
            e.time = parts[0].trim();
            e.raw = parts[1].trim();
        } else {
            e.raw = line;
        }
        e.accessToken = grab(e.raw, "access_token=([^&\\s]+)");
        if (e.accessToken.isEmpty() && !e.raw.isEmpty() && !e.raw.contains("=")) e.accessToken = e.raw;
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

    public static java.util.List<String[]> cloudGames() {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < GAMES.length; i++) { if (seen.add(APPIDS[i])) out.add(new String[]{GAMES[i], APPIDS[i]}); }
        try {
            android.content.SharedPreferences sp = appContext.getSharedPreferences("myPrefs", android.content.Context.MODE_PRIVATE);
            String synced = sp.getString("synced_data", "");
            for (String line : synced.split(";")) {
                int k = line.indexOf("|");
                if (k <= 0) continue;
                String name = line.substring(0, k).trim();
                String appid = line.substring(k + 1).trim();
                if (name.isEmpty() || appid.isEmpty()) continue;
                if (seen.add(appid)) out.add(new String[]{name, appid});
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static java.util.List<String> cloudOnlyGames() {
        java.util.List<String> names = new java.util.ArrayList<String>();
        try {
            android.content.SharedPreferences sp = appContext.getSharedPreferences("myPrefs", android.content.Context.MODE_PRIVATE);
            String synced = sp.getString("synced_data", "");
            for (String line : synced.split(";")) {
                int k = line.indexOf("|");
                if (k <= 0) continue;
                String name = line.substring(0, k).trim();
                String appid = line.substring(k + 1).trim();
                if (name.isEmpty() || appid.isEmpty()) continue;
                if (!names.contains(name)) names.add(name);
            }
        } catch (Throwable ignored) {}
        if (names.isEmpty()) { for (String g : GAMES) names.add(g); }
        return names;
    }
    public static java.util.List<String> allGames() {
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (String[] g : cloudGames()) { if (!names.contains(g[0])) names.add(g[0]); }
        return names;
    }
    public static java.util.List<String> listGameFolders() {
        java.util.List<String> out = new ArrayList<String>();
        for (String g : allGames()) { if (!loadGame(g).isEmpty()) out.add(g); }
        return out;
    }

    public static String nextGameWithAccounts(String current) {
        if (current != null && !current.isEmpty() && !loadGame(current).isEmpty()) return current;
        java.util.List<String> all = listGameFolders();
        int idx = all.indexOf(current);
        if (idx < 0) idx = 0;
        for (int i = 1; i <= all.size(); i++) {
            String g = all.get((idx + i) % all.size());
            if (!loadGame(g).isEmpty()) return g;
        }
        return "";
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
            String ln;
            while ((ln = br.readLine()) != null) { String g = gameOfPackage(ln); if (!g.isEmpty()) { br.close(); p.waitFor(); return g; } }
            br.close();
            p.waitFor();
            return "";
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
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (f.exists()) {
            try {
                Scanner sc = new Scanner(f, "UTF-8");
                while (sc.hasNextLine()) {
                    String ln = sc.nextLine();
                    if (!token.isEmpty() && lineHasToken(ln, token)) continue;
                    out.add(ln);
                }
                sc.close();
            } catch (Exception ignored) {}
        }
        out.add(line);
        return writeLinesAtomic(f, out);
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
        File f = gameFile(game);
        if (!f.exists()) return false;
        java.util.List<String> keep = new java.util.ArrayList<String>();
        try {
            Scanner sc = new Scanner(f, "UTF-8");
            while (sc.hasNextLine()) {
                String ln = sc.nextLine();
                if (lineHasToken(ln, token)) continue;
                keep.add(ln);
            }
            sc.close();
        } catch (Exception e) {
            lastError = "read fail: " + e.getMessage();
            return false;
        }
        if (!writeLinesAtomic(f, keep)) { lastError = "delete fail"; return false; }
        return true;
    }

    public static boolean updateNote(String game, String token, String note) {
        File f = gameFile(game);
        if (!f.exists()) return false;
        java.util.List<String> lines = new java.util.ArrayList<String>();
        boolean found = false;
        try {
            Scanner sc = new Scanner(f, "UTF-8");
            while (sc.hasNextLine()) {
                String line = sc.nextLine();
                if (!found && lineHasToken(line, token)) {
                    String[] parts = line.split(String.valueOf((char)124));
                    StringBuilder sb = new StringBuilder();
                    if (parts.length >= 4) {
                        sb.append(parts[0].trim()).append(" | ").append(parts[1].trim()).append(" | ").append(parts[2].trim()).append(" | ").append(parts[3].trim()).append(" | ").append(note.replace(String.valueOf((char)124), "/"));
                    } else {
                        sb.append(line).append(" | ").append(note);
                    }
                    lines.add(sb.toString());
                    found = true;
                } else {
                    lines.add(line);
                }
            }
            sc.close();
        } catch (Exception e) { lastError = "note fail: " + e.getMessage(); return false; }
        if (!found) return false;
        if (!writeLinesAtomic(f, lines)) { lastError = "note fail"; return false; }
        return true;
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
    public static int exportAll() {
        int n = 0;
        File src = rootDir();
        File dst = new File(downloadDir(), ROOT_NAME);
        try { if (!dst.exists()) dst.mkdirs(); } catch (Throwable ignored) {}
        File tar = new File(dst, FILE_NAME); if (tar.exists()) tar.delete(); File[] fs = src.listFiles();
        if (fs == null) return 0;
        for (int i = 0; i < fs.length; i++) {
            File f = fs[i];
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".txt")) continue;
            try { copyFile(f, tar); java.io.BufferedReader br2 = new java.io.BufferedReader(new java.io.FileReader(f)); while (br2.readLine() != null) n++; br2.close(); } catch (Exception ignored) {}
        }
        return n;
    }

    public static class ImportResult {
        public int added = 0;
        public java.util.List<String> unknownLines = new java.util.ArrayList<>();
    }

    public static ImportResult importFromUri(android.content.Context ctx, android.net.Uri uri) {
        ImportResult res = new ImportResult();
        try {
            java.io.InputStream is = ctx.getContentResolver().openInputStream(uri);
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Entry pe = parseLine(line, "");
                String game = gameOfUrl(line);
                if (game != null && !game.isEmpty()) {
                    String keep = (pe == null || pe.raw.isEmpty()) ? line : (pe.time.isEmpty() ? pe.raw : pe.time + "|" + game + "|" + pe.raw + "|" + pe.mode);
                    if (saveGameEntry(game, keep)) res.added++;
                } else if (pe != null && !pe.accessToken.isEmpty()) {
                    res.unknownLines.add(line);
                }
            }
            br.close();
        } catch (Exception e) {
            lastError = e.toString();
        }
        return res;
    }


    private static String tokenOf(String line) {
        if (line == null) return "";
        Entry e = parseLine(line, "");
        return e == null || e.accessToken == null ? "" : e.accessToken;
    }
    private static int mergeFile(File src, File dst) {
        int added = 0;
        try {
            java.util.Set<String> existing = new java.util.HashSet<String>();
            java.util.List<String> out = new java.util.ArrayList<String>();
            if (dst.exists()) {
                Scanner sc = new Scanner(dst, "UTF-8");
                while (sc.hasNextLine()) {
                    String ln = sc.nextLine();
                    out.add(ln);
                    String t = tokenOf(ln);
                    if (!t.isEmpty()) existing.add(t);
                }
                sc.close();
            }
            Scanner sc2 = new Scanner(src, "UTF-8");
            while (sc2.hasNextLine()) {
                String ln = sc2.nextLine();
                if (ln.trim().isEmpty()) continue;
                String t = tokenOf(ln);
                if (!t.isEmpty() && existing.contains(t)) continue;
                out.add(ln);
                if (!t.isEmpty()) existing.add(t);
                added++;
            }
            sc2.close();
            java.io.File tmp = new java.io.File(dst.getAbsolutePath() + ".tmp");
            java.io.FileWriter w = new java.io.FileWriter(tmp, false);
            for (int i = 0; i < out.size(); i++) { w.append(out.get(i)); w.append((char)10); }
            w.flush(); w.close();
            if (out.size() > 0 && tmp.length() == 0) { tmp.delete(); throw new java.io.IOException("tmp empty"); }
            java.io.File bak = new java.io.File(dst.getAbsolutePath() + ".bak");
            if (dst.exists()) {
                if (bak.exists()) bak.delete();
                if (!dst.renameTo(bak)) { tmp.delete(); throw new java.io.IOException("backup failed"); }
            }
            if (!tmp.renameTo(dst)) {
                if (bak.exists()) bak.renameTo(dst);
                tmp.delete();
                throw new java.io.IOException("replace failed");
            }
        } catch (Exception e) {
            lastError = "导入失败: " + e.getMessage();
            added = 0;
        }
        return added;
    }

    private static void copyFile(File src, File dst) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst, true);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private static boolean writeLinesAtomic(File f, java.util.List<String> lines) {
        try {
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileWriter w = new FileWriter(tmp, false);
            for (int i = 0; i < lines.size(); i++) { w.append(lines.get(i)); w.append((char)10); }
            w.flush(); w.close();
            if (!lines.isEmpty() && tmp.length() == 0) { tmp.delete(); return false; }
            File bak = new File(f.getAbsolutePath() + ".bak");
            if (f.exists()) {
                if (bak.exists()) bak.delete();
                if (!f.renameTo(bak)) { tmp.delete(); return false; }
            }
            if (!tmp.renameTo(f)) {
                if (bak.exists()) bak.renameTo(f);
                tmp.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            lastError = "write fail: " + e.getMessage();
            return false;
        }
    }

    private static boolean lineHasToken(String line, String token) {
        if (line == null || token == null || token.isEmpty()) return false;
        Entry e = parseLine(line, "");
        return e != null && token.equals(e.accessToken);
    }
}

