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
        return "";
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
        String g = gameOfAppid(appidOfUrl(url)); if (g == null && url != null) { for (String[] cg : cloudGames()) { if (url.indexOf(cg[1]) >= 0) return cg[0]; } }
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
        public String appid = ""; public String note = ""; public String line = "";

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

    private static boolean strong(String t) { if (t == null) return false; String s = t.trim(); if (s.length() < 12) return false; if (s.indexOf("access_token=") >= 0) return true; if (s.indexOf("auth://") >= 0) return true; if (s.indexOf("http") >= 0) return true; if (s.indexOf("token=") >= 0) return true; if (s.indexOf("=") >= 0 && s.length() >= 24) return true; return false; } private static boolean meta(String t) { if (t == null) return false; String s = t.trim(); if (s.length() == 0 || s.length() > 200) return false; for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c == (char)61 || c == (char)38 || c == (char)47 || c == (char)63 || c == (char)58 || c == (char)37 || c == (char)43 || c == (char)35 || c == (char)124) return false; } return true; } private static boolean tok(String t) { if (t == null) return false; String s = t.trim(); if (s.length() >= 16) return true; for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c == (char)61 || c == (char)38 || c == (char)47 || c == (char)63 || c == (char)58 || c == (char)37) return true; } return false; } private static String[] splitLineInner(String line) { String e0 = new String(); String[] r = new String[]{ e0, e0, e0, e0, e0 }; if (line == null) return r; String s = line.trim(); if (s.length() == 0) return r; s = s.replace("%7C", "\u0001"); int p1 = s.indexOf((char)124); if (p1 < 0) { r[0] = s; r[2] = s; return r; } int p2 = s.indexOf((char)124, p1 + 1); if (p2 < 0) { r[0] = s.substring(0, p1).trim(); r[2] = s.substring(p1 + 1).trim(); return r; } r[0] = s.substring(0, p1).trim(); r[1] = s.substring(p1 + 1, p2).trim(); String body = s.substring(p2 + 1); int lastSep = body.lastIndexOf((char)124); if (lastSep > 0) { String maybeNote = body.substring(lastSep + 1).trim(); String maybeToken = body.substring(0, lastSep).trim(); if (tok(maybeToken) && maybeNote.length() > 0 && maybeNote.length() <= 200 && maybeNote.indexOf("access_token=") < 0 && !maybeNote.equals("capture") && !maybeNote.equals("login") && !maybeNote.equals("hang_up") && !maybeNote.startsWith("cap") && !maybeNote.startsWith("hang")) { r[2] = maybeToken; r[4] = maybeNote; return r; } } r[2] = body.trim().replace("\u0001", "|"); return r; }
    private static String[] splitLine(String line) {
        String[] r = splitLineInner(line);
        for (int i = 0; i < r.length; i++) if (r[i] != null) r[i] = r[i].replace(String.valueOf((char) 1), "|");
        if (r[3] != null && r[3].length() == 0 && r[2] != null) {
            boolean _sp4 = false;
            String[] _mk = new String[]{"capture", "login", "hang_up"}; 
            for (String _m : _mk) {
                if (r[2].endsWith("|" + _m)) { r[2] = r[2].substring(0, r[2].length() - _m.length() - 1).trim(); r[3] = _m; _sp4 = true; break; }
            }
        }
        return r;
    }

    public static String joinLine(String note, String time, String game, String rawSafe, String mode) { StringBuilder sb = new StringBuilder();
 String _rs = rawSafe;
 for (int _g = 0; _g < 6; _g++) {
  if (_rs.endsWith("%7Ccapture")) _rs = _rs.substring(0, _rs.length() - 10);
  else if (_rs.endsWith("|capture")) _rs = _rs.substring(0, _rs.length() - 8);
  else if (_rs.endsWith("%7Clogin")) _rs = _rs.substring(0, _rs.length() - 8);
  else if (_rs.endsWith("|login")) _rs = _rs.substring(0, _rs.length() - 6);
  else if (_rs.endsWith("%7Chang_up")) _rs = _rs.substring(0, _rs.length() - 9);
  else if (_rs.endsWith("|hang_up")) _rs = _rs.substring(0, _rs.length() - 8);
  else break;
 }
 rawSafe = _rs; if (note != null && note.length() > 0) { sb.append(note.replace("|", "/").replace((char) 10, (char) 32).replace((char) 13, (char) 32)).append(" "); } sb.append(time).append((char) 124).append(game).append((char) 124).append(rawSafe).append((char) 124).append(mode); return sb.toString(); }

                                private static java.util.Set<String> knownGameCache = null;
    private static long knownGameCacheAt = 0;

    private static java.util.Set<String> knownGames() {
        long now = System.currentTimeMillis();
        if (knownGameCache != null && now - knownGameCacheAt < 60000L) return knownGameCache;
        java.util.Set<String> set = new java.util.HashSet<String>();
        try { java.util.List<String> a = importGames(); for (int i = 0; i < a.size(); i++) { String g = a.get(i); if (g != null && g.length() > 0) set.add(g.trim()); } } catch (Throwable t) {}
        try { java.util.List<String> b = allGames(); for (int i = 0; i < b.size(); i++) { String g = b.get(i); if (g != null && g.length() > 0) set.add(g.trim()); } } catch (Throwable t) {}
        knownGameCache = set;
        knownGameCacheAt = now;
        return set;
    }

    private static boolean isKnownGame(String p) {
        if (p == null) return false;
        String t = p.trim();
        if (t.length() == 0 || t.length() > 40) return false;
        return knownGames().contains(t);
    }

    private static boolean hasCJK(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= (char) 0x4E00 && c <= (char) 0x9FA5) return true;
        }
        return false;
    }

    private static boolean isDigitOrHex(char c) {
        return (c >= (char) 48 && c <= (char) 57) || (c >= (char) 97 && c <= (char) 102) || (c >= (char) 65 && c <= (char) 70);
    }

    private static int tokenCandidate(String p) {
        if (p == null) return -1;
        String t = p.trim();
        if (t.length() < 6) return -1;
        if (isModeWord(t)) return -1;
        if (isDateSeg(t)) return -1;
        if (hasCJK(t)) return -1;
        if (isExplicitToken(t)) return 100;
        boolean allNum = true;
        boolean clean = true;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < (char) 48 || c > (char) 57) allNum = false;
            boolean ok = (c >= (char) 48 && c <= (char) 57) || (c >= (char) 97 && c <= (char) 122) || (c >= (char) 65 && c <= (char) 90) || c == (char) 95 || c == (char) 45 || c == (char) 46 || c == (char) 61 || c == (char) 38 || c == (char) 58 || c == (char) 47 || c == (char) 63 || c == (char) 37;
            if (!ok) clean = false;
        }
        if (allNum) return -1;
        if (t.indexOf((char) 32) >= 0) return -1;
        int sc = 0;
        if (t.indexOf((char) 61) >= 0) sc += 3;
        if (t.indexOf((char) 38) >= 0) sc += 3;
        if (t.length() >= 16) sc += 2;
        if (t.length() >= 28) sc += 2;
        if (clean) sc += 1;
        return sc;
    }

    public static Entry parseAnyInput(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.length() == 0) return null;
        return parseLine(s, "");
    }

    private static boolean isDateSeg(String p) {
        if (p == null) return false;
        String t = p.trim();
        if (t.length() < 10) return false;
        if (!isDigitAt(t, 0) || !isDigitAt(t, 1) || !isDigitAt(t, 2) || !isDigitAt(t, 3)) return false;
        if (t.charAt(4) != (char) 45) return false;
        if (!isDigitAt(t, 5) || !isDigitAt(t, 6)) return false;
        if (t.charAt(7) != (char) 45) return false;
        if (!isDigitAt(t, 8) || !isDigitAt(t, 9)) return false;
        return true;
    }

    public static Entry parseLine(String line, String game) {
        if (line == null) return null;
        String s = line.trim();
        if (s.length() == 0) return null;
        Entry e = new Entry();
        java.util.List<String> parts = splitByPipe(s);
        String date = "";
        String appid = "";
        String gameName = "";
        String mode = "";
        int tokIdx = -1;
        int tokScore = -1;
        java.util.List<String> notes = new java.util.ArrayList<String>();
        for (int i = 0; i < parts.size(); i++) {
            String raw = parts.get(i);
            String p = raw.trim();
            if (p.length() == 0) continue;
            if (isDateSeg(p)) { if (date.length() == 0) date = p; continue; }
            int sc = tokenCandidate(p);
            if (sc > tokScore) { tokScore = sc; tokIdx = i; }
        }
        for (int i = 0; i < parts.size(); i++) {
            String raw = parts.get(i);
            String p = raw.trim();
            if (p.length() == 0) continue;
            if (isDateSeg(p)) continue;
            if (i == tokIdx) continue;
            if (isModeWord(p)) { if (mode.length() == 0) mode = p; continue; }
            if (p.length() >= 5 && p.length() <= 12) {
                boolean digits = true;
                for (int k = 0; k < p.length(); k++) if (!isDigitAt(p, k)) { digits = false; break; }
                if (digits) { if (appid.length() == 0) appid = p; continue; }
            }
            if (gameName.length() == 0 && isKnownGame(p)) { gameName = p; continue; }
            notes.add(p);
        }
        if (gameName.length() == 0 && game != null && game.length() > 0) gameName = game;
        String tokRaw = "";
        if (tokIdx >= 0 && tokIdx < parts.size()) {
            tokRaw = parts.get(tokIdx).trim().replace("%7C", "|");
        }
        if (tokRaw.length() == 0) tokRaw = s.replace("%7C", "|");
        e.raw = tokRaw;
        e.accessToken = extractToken(tokRaw);
        if (e.accessToken.length() == 0 && tokRaw.length() > 0 && tokRaw.indexOf((char) 61) < 0 && tokenCandidate(tokRaw) >= 0) e.accessToken = tokRaw;
        if (appid.length() == 0) {
            String a = appidOfUrl(tokRaw);
            if (a != null) appid = a;
            else if (tokRaw.length() >= 5 && tokRaw.length() <= 12) {
                boolean digits = true;
                for (int k = 0; k < tokRaw.length(); k++) if (!isDigitAt(tokRaw, k)) { digits = false; break; }
                if (digits) appid = tokRaw;
            }
        }
        if (appid.length() == 0 && gameName.length() > 0) appid = appidOfGame(gameName);
        if (appid.length() == 0) {
            String a = appidOfUrl(s);
            if (a != null) appid = a;
        }
        if (gameName.length() == 0 && appid.length() > 0) {
            String nm = gameOfAppid(appid);
            if (nm != null && nm.length() > 0) gameName = nm;
        }
        if (gameName.length() == 0 && game != null && game.length() > 0) gameName = game;
        e.time = date;
        e.appid = appid;
        e.game = gameName;
        e.mode = mode;
        StringBuilder nb = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            if (nb.length() > 0) nb.append((char) 32);
            nb.append(notes.get(i));
        }
        e.note = nb.toString().replace((char) 10, (char) 32).replace((char) 13, (char) 32).replace((char) 124, (char) 47);
        if (e.mode.length() == 0) {
            int mk = e.raw.lastIndexOf((char) 124);
            if (mk > 0) {
                String mt = e.raw.substring(mk + 1).trim();
                if (isModeWord(mt)) { e.mode = mt; e.raw = e.raw.substring(0, mk); }
            }
        }
        e.openid = grab(e.raw, "openid=([^&\\s]+)");
        e.payToken = grab(e.raw, "pay_token=([^&\\s]+)");
        e.expiresIn = grab(e.raw, "expires_in=([^&\\s]+)");
        return e;
    }

private static boolean isExplicitToken(String p) {
        if (p == null) return false;
        String lp = p.toLowerCase();
        return p.indexOf("access_token=") >= 0 || lp.startsWith("auth://") || lp.startsWith("http://") || lp.startsWith("https://");
    }

    private static boolean isModeWord(String p) {
        return p != null && (p.equals("capture") || p.equals("login") || p.equals("hang_up"));
    }

    private static int tokenScore(String p) {
        if (p == null) return -1;
        String t = p.trim();
        if (t.length() < 6) return -1;
        if (t.matches("\\d+")) return -1;
        if (t.matches("\\d{4}-\\d{2}-\\d{2}.*")) return -1;
        if (isModeWord(t)) return -1;
        int score = 0;
        if (t.indexOf("access_token=") >= 0 || t.startsWith("auth://") || t.startsWith("http://") || t.startsWith("https://")) score += 10;
        if (t.indexOf((char) 61) >= 0) score += 3;
        if (t.indexOf((char) 38) >= 0) score += 3;
        if (t.length() >= 16) score += 2;
        if (t.length() >= 32) score += 2;
        return score;
    }

    private static String extractToken(String raw) {
        if (raw == null) return "";
        String t = grab(raw, "access_token=([^&\\s]+)");
        if (t.length() > 0) return t;
        t = grab(raw, "token=([^&\\s]+)");
        if (t.length() > 0) return t;
        return "";
    }

    private static boolean looksLikeToken(String p) {
        if (p == null) return false;
        String t = p.trim();
        if (t.length() < 6) return false;
        if (t.matches("\\d+")) return false;
        if (t.matches("\\d{4}-\\d{2}-\\d{2}.*")) return false;
        if (t.equals("capture") || t.equals("login") || t.equals("hang_up")) return false;
        return true;
    }

    private static boolean isDigitAt(String s, int i) {
        return i >= 0 && i < s.length() && s.charAt(i) >= (char) 48 && s.charAt(i) <= (char) 57;
    }

    private static int firstDateIndex(String s) {
        for (int i = 0; i + 9 < s.length(); i++) {
            if (isDigitAt(s, i) && isDigitAt(s, i + 1) && isDigitAt(s, i + 2) && isDigitAt(s, i + 3)
                    && s.charAt(i + 4) == (char) 45
                    && isDigitAt(s, i + 5) && isDigitAt(s, i + 6)
                    && s.charAt(i + 7) == (char) 45
                    && isDigitAt(s, i + 8) && isDigitAt(s, i + 9)) return i;
        }
        return -1;
    }

    private static int dateTokenEnd(String s) {
        if (s.length() < 10) return s.length();
        int n = 10;
        if (n + 2 < s.length() && (s.charAt(n) == (char) 32 || s.charAt(n) == (char) 84)
                && isDigitAt(s, n + 1) && isDigitAt(s, n + 2)) n = n + 3;
        else return n;
        if (n + 2 < s.length() && s.charAt(n) == (char) 58
                && isDigitAt(s, n + 1) && isDigitAt(s, n + 2)) n = n + 3;
        return n;
    }

    private static String stripLead(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == (char) 32 || c == (char) 124) i++; else break;
        }
        return s.substring(i);
    }

    private static java.util.List<String> splitByPipe(String s) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == (char) 124) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }

        public static List<Entry> loadGame(String game) {
        List<Entry> list = new ArrayList<>();
        File f = gameFile(game);
        if (!f.exists()) return list;
        try {
            Scanner sc = new Scanner(f, "UTF-8");
            while (sc.hasNextLine()) {
                String _rawLine = sc.nextLine(); Entry e = parseLine(_rawLine, game); if (e != null) e.line = _rawLine;
                if (e != null && !e.accessToken.isEmpty()) list.add(e);
            }
            sc.close();
        } catch (Exception ignored) {}
        return list;
    }

    public static java.util.List<String[]> cloudGames() {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        try {
            android.content.SharedPreferences sp = appContext.getSharedPreferences("myPrefs", android.content.Context.MODE_PRIVATE);
            String synced = sp.getString("synced_data", "");
            for (String line : synced.split(";")) {
                int k = line.indexOf("|");
                if (k <= 0) continue;
                String name = line.substring(0, k).trim();
                String appid = line.substring(k + 1).trim();
                if (name.isEmpty() || appid.isEmpty()) continue;
                { boolean _n = true; for (int _i = 0; _i < appid.length(); _i++) if (!Character.isDigit(appid.charAt(_i))) { _n = false; break; } if (!_n) continue; } if (seen.add(appid)) out.add(new String[]{name, appid});
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
        
        return names;
    }
    public static String gameOfAppidLocal(String appid) {
        if (appid == null || appid.isEmpty()) return "";
        String g = gameOfAppid(appid);
        if (g != null && !g.isEmpty()) return g;
        try {
            File[] fs = rootDir().listFiles();
            if (fs == null) return "";
            for (File f : fs) {
                String fn = f.getName();
                if (fn == null || !fn.toLowerCase().endsWith(".txt")) continue;
                Scanner sc = new Scanner(f, "UTF-8");
                boolean hit = false;
                while (sc.hasNextLine()) {
                    String ln = sc.nextLine();
                    if (ln.indexOf(appid) >= 0) { hit = true; break; }
                }
                sc.close();
                if (hit) return fn.substring(0, fn.length() - 4);
            }
        } catch (Throwable ignored) {}
        return "";
    }

    public static String resolveGame(String game, String url) {
        String aid = appidOfUrl(url);
        if (game != null && !game.isEmpty() && !"未知游戏".equals(game) && !"其他".equals(game)) return game;
        String byAid = gameOfAppidLocal(aid);
        if (!byAid.isEmpty()) return byAid;
        String byUrl = gameOfUrl(url);
        if (byUrl != null && !byUrl.isEmpty()) return byUrl;
        if (aid != null && !aid.isEmpty()) { String _nm = gameOfAppid(aid); if (_nm != null && !_nm.isEmpty()) return _nm; }
        return game == null || game.isEmpty() ? "其他" : game;
    }

    public static java.util.List<String> importGames() { java.util.List<String> names = new java.util.ArrayList<String>(); try { android.content.SharedPreferences sp = appContext.getSharedPreferences("myPrefs", android.content.Context.MODE_PRIVATE); String synced = sp.getString("synced_data", ""); for (String line : synced.split(";")) { int k = line.indexOf("|"); if (k <= 0) continue; String name = line.substring(0, k).trim(); if (name.isEmpty()) continue; if (!names.contains(name)) names.add(name); } } catch (Throwable ignored) {} if (names.isEmpty()) { names = allGames(); if (names.isEmpty()) names.add("其他"); } return names; }
    private static volatile long _cAt=0;
    private static volatile java.util.List<String> _c=null;
    public static synchronized void invalidateGamesCache(){_cAt=0;_c=null;}
    public static java.util.List<String> allGames() {
        long n=System.currentTimeMillis();
        java.util.List<String> c=_c; if(c!=null&&n-_cAt<30000) return c;
        java.util.List<String> r=new java.util.ArrayList<String>();
        try{android.content.SharedPreferences sp=appContext.getSharedPreferences("myPrefs",0);
        for(String ln:sp.getString("synced_data","").split(";")){int k=ln.indexOf((char)124);if(k<=0)continue;
        String nm=ln.substring(0,k).trim();if(nm.length()>0&&!r.contains(nm))r.add(nm);}}catch(Throwable t){}
        for(String g:scanLocal()) if(!r.contains(g)) r.add(g);
        _c=r;_cAt=n;return r;
    }

    public static java.util.List<String> scanLocal() {
        java.util.List<String> o=new java.util.ArrayList<String>();
        try{File[] fs=rootDir().listFiles();if(fs!=null)for(File f:fs){String fn=f.getName();
        if(fn==null||!fn.toLowerCase().endsWith(".txt"))continue;
        String nm=fn.substring(0,fn.length()-4).trim();
        if(nm.length()>0&&!o.contains(nm))o.add(nm);}}catch(Throwable t){}
        return o;
    }

    public static java.util.List<String> listGameFolders() { return scanLocal(); }

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

    public static String sanitizeGame(String g) { if (g == null) return "其他"; String s = g.trim(); if (s.isEmpty() || "未知游戏".equals(s)) return "其他"; StringBuilder sb = new StringBuilder(); for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c == (char)47 || c == (char)92 || c == (char)58 || c == (char)42 || c == (char)63 || c == (char)34 || c == (char)60 || c == (char)62 || c == (char)124 || c < 32) sb.append((char)95); else sb.append(c); } String r = sb.toString().trim(); if (r.isEmpty() || r.equals(".") || r.equals("..")) return "其他"; return r; }

    public static int addEntry(String game, String line) {
        game = sanitizeGame(game);
        if (line == null) return -1;
        File f = gameFile(game);
        String token = grab(line, "access_token=([^&\\s]+)");
        if (token.isEmpty()) { Entry _pe = parseLine(line, ""); if (_pe != null) token = _pe.accessToken; }
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (f.exists()) {
            try {
                Scanner sc = new Scanner(f, "UTF-8");
                while (sc.hasNextLine()) out.add(sc.nextLine());
                sc.close();
            } catch (Exception e) {
                lastError = "读取旧记录失败，已中止写入以免覆盖: " + e;
                return -1;
            }
        }
        for (int i = 0; i < out.size(); i++) {
            String ln = out.get(i);
            boolean dup = token.isEmpty() ? ln.trim().equals(line.trim()) : lineHasToken(ln, token);
            if (dup) return 0;
        }
        out.add(line);
        if (!writeLinesAtomic(f, out)) return -1;
        invalidateGamesCache();
        return 1;
    }


    public static int cleanEmptyFiles() {
        int n = 0;
        try {
            File[] fs = rootDir().listFiles();
            if (fs == null) return 0;
            for (int i = 0; i < fs.length; i++) {
                File f = fs[i];
                String fn = f.getName();
                if (fn == null || !fn.toLowerCase().endsWith(".txt")) continue;
                boolean bad = false;
                try {
                    if (f.length() == 0) bad = true;
                    else {
                        Scanner sc = new Scanner(f, "UTF-8");
                        boolean hasTok = false;
                        while (sc.hasNextLine()) {
                            String ln = sc.nextLine();
                            if (ln == null || ln.trim().length() == 0) continue;
                            Entry pe = parseLine(ln, "");
                            String tk = (pe == null) ? "" : pe.accessToken;
                            if (tk == null) tk = "";
                            if (tk.trim().length() > 0) { hasTok = true; break; }
                        }
                        sc.close();
                        if (!hasTok) bad = true;
                    }
                } catch (Throwable t2) {}
                if (bad) {
                    try { if (f.delete()) n++; } catch (Throwable t3) {}
                    try { new File(f.getAbsolutePath() + ".bak").delete(); } catch (Throwable t4) {}
                }
            }
        } catch (Throwable t) {}
        return n;
    }

    public static int[] saveBatch(java.util.List<String[]> pairs) {
        int[] r = new int[]{0, 0, 0};
        if (pairs == null) return r;
        for (String[] pr : pairs) {
            if (pr == null || pr.length < 2) continue;
            int x = addEntry(pr[0], pr[1]);
            if (x == 1) r[0]++; else if (x == 0) r[1]++; else r[2]++;
        }
        cleanEmptyFiles();
        return r;
    }

    public static boolean saveGameEntry(String game, String line) {
        return addEntry(game, line) >= 0;
    }

    static String appidCol(String appid, String game, String raw) { if (appid != null && appid.length() > 0) { boolean _d = true; for (int _i = 0; _i < appid.length(); _i++) if (!Character.isDigit(appid.charAt(_i))) { _d = false; break; } if (_d) return appid; } String a = appidOfUrl(raw); if (a != null && a.length() > 0) return a; a = appidOfGame(game); return a == null ? "" : a; }
    static String withAppid(String u, String g) { if (u == null) return u; if (u.contains("appid=") || u.contains("client_id=")) return u; String a = appidOfGame(g);
        if (a == null || a.isEmpty()) { try { String su = appContext.getSharedPreferences("myPrefs", 0).getString("selected_game_url", ""); java.util.regex.Matcher m = java.util.regex.Pattern.compile("client_id=([0-9]+)").matcher(su == null ? "" : su); if (m.find()) a = m.group(1); } catch (Exception ex) {} } if (a == null || a.isEmpty()) return u; return u + (u.contains("?") ? "&" : "?") + "appid=" + a; }

    public static String rebuildUrl(Entry e) {
        if (e.raw != null && e.raw.contains("access_token=")) return withAppid(e.raw, e.game);
        StringBuilder sb = new StringBuilder("auth://tauth.qq.com/?#");
        sb.append("access_token=").append(e.accessToken);
        if (!e.expiresIn.isEmpty()) sb.append("&expires_in=").append(e.expiresIn);
        sb.append("&openid=").append(e.openid);
        sb.append("&pay_token=").append(e.payToken);
        return withAppid(sb.toString(), e.game);
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

    public static boolean deleteEntryByLine(String game, String line) {
        if (line == null) return false;
        File f = gameFile(game);
        if (!f.exists()) return false;
        java.util.List<String> keep = new java.util.ArrayList<String>();
        boolean removed = false;
        try {
            Scanner sc = new Scanner(f, "UTF-8");
            while (sc.hasNextLine()) {
                String ln = sc.nextLine();
                if (!removed && ln.equals(line)) { removed = true; continue; }
                keep.add(ln);
            }
            sc.close();
        } catch (Exception e) {
            lastError = "read fail: " + e.getMessage();
            return false;
        }
        if (!removed) return false;
        if (!writeLinesAtomic(f, keep)) { lastError = "delete fail"; return false; }
        invalidateGamesCache();
        return true;
    }

    public static java.util.List<String> sortedGames() {
        java.util.List<String> list = new java.util.ArrayList<String>(listGameFolders());
        java.util.Collections.sort(list, new java.util.Comparator<String>() {
            public int compare(String a, String b) {
                int la = a == null ? 0 : a.length();
                int lb = b == null ? 0 : b.length();
                if (la != lb) return la - lb;
                return (a == null ? "" : a).compareTo(b == null ? "" : b);
            }
        });
        return list;
    }

    public static boolean updateNote(String game, String token, String note) { File f = gameFile(game); if (!f.exists()) return false; java.util.List<String> lines = new java.util.ArrayList<String>(); boolean found = false; try { Scanner sc = new Scanner(f, java.nio.charset.StandardCharsets.UTF_8.name()); while (sc.hasNextLine()) { String line = sc.nextLine(); if (!found && lineHasToken(line, token)) { String nt = note == null ? new String() : note.trim(); Entry _pe = parseLine(line, ""); lines.add(joinLine(nt, _pe.time, appidCol(_pe.appid, _pe.game, _pe.raw), _pe.raw.replace("|", "%7C"), _pe.mode)); found = true; } else { lines.add(line); } } sc.close(); } catch (Exception ex) { lastError = String.valueOf(ex); return false; } if (!found) return false; if (!writeLinesAtomic(f, lines)) return false; return true; } public static void copy(String label, String text, android.content.Context c) {
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
        File dst = new File(downloadDir(), ROOT_NAME);
        try { if (!dst.exists()) dst.mkdirs(); } catch (Throwable ignored) {}
        java.util.Set<String> seen = new java.util.HashSet<String>();
        try {
            java.io.FileWriter w = new java.io.FileWriter(new File(dst, FILE_NAME), false);
            for (String g : allGames()) {
                File f = gameFile(g);
                if (!f.exists()) continue;
                Scanner sc = new Scanner(f, "UTF-8");
                while (sc.hasNextLine()) {
                    String ln = sc.nextLine().trim();
                    if (ln.isEmpty()) continue;
                    Entry e = parseLine(ln, g);
                    if (e == null || e.accessToken.isEmpty()) continue;
                    if (!seen.add(e.accessToken)) continue;
                    String rawSafe = e.raw.replace("|", "%7C"); w.append(joinLine(e.note, e.time, appidCol(e.appid, e.game, e.raw), rawSafe, e.mode));
                    
                    w.append((char)10);
                    n++;
                }
                sc.close();
            }
            w.flush();
            w.close();
        } catch (Exception ex) { lastError = String.valueOf(ex); return 0; }
        return n;
    }
    public static class ImportResult {
        public int added = 0;
        public int skipped = 0;
        public int existing = 0;
        public java.util.List<String[]> resolved = new java.util.ArrayList<String[]>();
        public java.util.List<String> unknownLines = new java.util.ArrayList<>();
    }

    public static ImportResult importFromUri(android.content.Context ctx, android.net.Uri uri) {
        ImportResult res = new ImportResult();
        java.io.InputStream is = null;
        try {
            is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) { lastError = "无法读取文件"; return res; }
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            String line;
            java.util.Set<String> seen = new java.util.HashSet<String>();
            java.util.List<String> unknown = new java.util.ArrayList<String>();
            String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date());
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Entry pe = parseAnyInput(line);
                if (pe == null || pe.accessToken.isEmpty()) { res.skipped++; continue; }
                if (!seen.add(pe.accessToken)) { res.skipped++; continue; }
                String game = "";
                if (pe.game != null && !pe.game.isEmpty() && !pe.game.matches("[0-9]+")) game = pe.game.trim();
                if (game.isEmpty()) { String byUrl = gameOfUrl(line); if (byUrl != null && !byUrl.isEmpty() && !byUrl.matches("[0-9]+")) game = byUrl; }
                if (game.isEmpty() && pe.appid != null && !pe.appid.isEmpty()) { String byAid = gameOfAppid(pe.appid); if (byAid != null && !byAid.isEmpty()) game = byAid; }
                if (game.isEmpty()) { unknown.add(line); continue; }
                String appid = appidCol(pe.appid, game, pe.raw);
                String t = (pe.time == null || pe.time.isEmpty()) ? now : pe.time;
                String keep = joinLine(pe.note, t, appid, pe.raw.replace("|", "%7C"), pe.mode);
                res.resolved.add(new String[]{game, keep});
            }
            br.close();
            res.unknownLines.addAll(unknown);
            res.added = res.resolved.size();
        } catch (Exception e) {
            lastError = "导入失败: " + e;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
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

