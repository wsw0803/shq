package com.tencent.tim;

import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Token 存取与解析。
 *
 * 本次性能与可读性修复（解析逻辑与落盘格式未改变）：
 *
 * 7) cloudGames() 每次调用都重新解析 synced_data 字符串，
 *    而 appidOfGame / gameOfAppid / gameOfUrl 都会调它，
 *    在 parseLine 里被反复触发，复杂度接近 O(n²)。
 *    现在给 cloudGames 加缓存，写操作时统一失效。
 *
 * 8) gameOfAppidLocal() 对每个 txt 文件全量扫描且无缓存，
 *    resolveGame() 每次解析都触发，导入几百条时是磁盘灾难。
 *    现在建立「appid -> 游戏名」索引并缓存。
 *
 * 9) addEntry() 为了去重，会先 parseLine 整份文件的每一行。
 *    批量导入 N 条就是 N 次全量重解析。
 *    现在按文件缓存「token 集合」，用文件长度 + 修改时间校验新鲜度，
 *    命中缓存时只做 O(1) 查表。
 *
 * 16) 把原先挤成单行的 strong/meta/tok/splitLineInner/joinLine 等展开成正常格式。
 *     这些方法里的字符常量（124 即 '|'、61 即 '='）一律换成可读写法，
 *     数值与分支顺序保持原样，避免改变解析行为。
 */
public class TokenStore {

    public static final String ROOT_NAME = "WEI";
    public static final String DATA_NAME = "";
    public static final String FILE_NAME = "数据.txt";
    public static String lastError = "";
    public static android.content.Context appContext = null;

    /** '|' 的码点，作为字段分隔符。 */
    private static final char SEP = '|';
    /** 转义后的分隔符 %7C，用于在内容里表达字面量 '|'。 */
    private static final String SEP_ESC = "%7C";
    /** 内部占位符，防止内容里的 '|' 被当作分隔符切开。 */
    private static final char SEP_PH = '\u0001';

    private static final Pattern CLIENT_ID = Pattern.compile("client_id=([0-9]+)");
    private static final Pattern PT_3RD_AID = Pattern.compile("pt_3rd_aid=([0-9]+)");
    /** 5~12 位连续数字，用于建立 appid 索引（appid 都是这个长度区间）。 */
    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9]{5,12}");

    public static void init(android.content.Context c) {
        if (c != null) appContext = c.getApplicationContext();
    }

    public static File rootDir() {
        File dir = new File(appContext.getFilesDir(), ROOT_NAME);
        try {
            if (!dir.exists()) dir.mkdirs();
        } catch (Throwable ignored) {
        }
        return dir;
    }

    public static File downloadDir() {
        File d = new File(Environment.getExternalStorageDirectory(), "Download");
        try {
            if (!d.exists()) d.mkdirs();
        } catch (Throwable ignored) {
        }
        return d;
    }

    public static File gameDir(String game) {
        return rootDir();
    }

    public static File gameFile(String game) {
        return new File(rootDir(), game + ".txt");
    }

    // ------------------------------------------------------------------
    // 云游戏列表缓存（第 7 项）
    // ------------------------------------------------------------------

    /** 缓存 synced_data 解析结果，避免被 appidOfGame / gameOfAppid 反复触发。 */
    private static volatile List<String[]> cloudGamesCache = null;
    private static volatile long cloudGamesAt = 0;
    private static final long CLOUD_TTL = 30000L;

    public static java.util.List<String[]> cloudGames() {
        long now = System.currentTimeMillis();
        List<String[]> cached = cloudGamesCache;
        if (cached != null && now - cloudGamesAt < CLOUD_TTL) return cached;

        List<String[]> out = new ArrayList<String[]>();
        Set<String> seen = new HashSet<String>();
        try {
            android.content.SharedPreferences sp =
                    appContext.getSharedPreferences("myPrefs", android.content.Context.MODE_PRIVATE);
            String synced = sp.getString("synced_data", "");
            for (String line : synced.split(";")) {
                int k = line.indexOf(SEP);
                if (k <= 0) continue;
                String name = line.substring(0, k).trim();
                String appid = line.substring(k + 1).trim();
                if (name.isEmpty() || appid.isEmpty()) continue;
                if (!isAllDigits(appid)) continue;
                if (seen.add(appid)) out.add(new String[]{name, appid});
            }
        } catch (Throwable ignored) {
        }
        cloudGamesCache = out;
        cloudGamesAt = now;
        return out;
    }

    public static String appidOfGame(String game) {
        if (game == null) return "";
        for (String[] g : cloudGames()) {
            if (g[0].equals(game)) return g[1];
        }
        return "";
    }

    public static String gameOfAppid(String appid) {
        if (appid == null) return null;
        for (String[] g : cloudGames()) {
            if (g[1].equals(appid)) return g[0];
        }
        return null;
    }

    public static String appidOfUrl(String url) {
        if (url == null) return null;
        Matcher m = CLIENT_ID.matcher(url);
        if (m.find()) return m.group(1);
        m = PT_3RD_AID.matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    public static String gameOfUrl(String url) {
        String g = gameOfAppid(appidOfUrl(url));
        if (g == null && url != null) {
            for (String[] cg : cloudGames()) {
                if (url.indexOf(cg[1]) >= 0) return cg[0];
            }
        }
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
        public String appid = "";
        public String note = "";
        public String line = "";

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

    private static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /** 判断是否像一条完整凭据（含分隔符、长度较长）。 */
    private static boolean strong(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.length() < 12) return false;
        if (s.indexOf("access_token=") >= 0) return true;
        if (s.indexOf("auth://") >= 0) return true;
        if (s.indexOf("http") >= 0) return true;
        if (s.indexOf("token=") >= 0) return true;
        if (s.indexOf('=') >= 0 && s.length() >= 24) return true;
        return false;
    }

    /** 判断是否像一段短备注：不含 URL/查询串常见字符。 */
    private static boolean meta(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.length() == 0 || s.length() > 200) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c == '&' || c == '/' || c == '?' || c == ':'
                    || c == '%' || c == '+' || c == '#' || c == SEP) return false;
        }
        return true;
    }

    /** 判断某段是否像 token 内容。 */
    private static boolean tok(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.length() >= 16) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c == '&' || c == '/' || c == '?' || c == ':' || c == '%') return true;
        }
        return false;
    }

    /**
     * 按分隔符拆出 [备注, 游戏, token, 模式, 尾部备注]。
     * 逻辑与原实现一致：先还原 %7C，再按位置切分，最后判断最后一段是不是备注。
     */
    private static String[] splitLineInner(String line) {
        String empty = "";
        String[] r = new String[]{empty, empty, empty, empty, empty};
        if (line == null) return r;
        String s = line.trim();
        if (s.length() == 0) return r;

        s = s.replace(SEP_ESC, String.valueOf(SEP_PH));

        int p1 = s.indexOf(SEP);
        if (p1 < 0) {
            r[0] = s;
            r[2] = s;
            return r;
        }

        int p2 = s.indexOf(SEP, p1 + 1);
        if (p2 < 0) {
            r[0] = s.substring(0, p1).trim();
            r[2] = s.substring(p1 + 1).trim();
            return r;
        }

        r[0] = s.substring(0, p1).trim();
        r[1] = s.substring(p1 + 1, p2).trim();

        String body = s.substring(p2 + 1);
        int lastSep = body.lastIndexOf(SEP);
        if (lastSep > 0) {
            String maybeNote = body.substring(lastSep + 1).trim();
            String maybeToken = body.substring(0, lastSep).trim();
            if (tok(maybeToken)
                    && maybeNote.length() > 0
                    && maybeNote.length() <= 200
                    && maybeNote.indexOf("access_token=") < 0
                    && !maybeNote.equals("capture")
                    && !maybeNote.equals("login")
                    && !maybeNote.equals("hang_up")
                    && !maybeNote.startsWith("cap")
                    && !maybeNote.startsWith("hang")) {
                r[2] = maybeToken;
                r[4] = maybeNote;
                return r;
            }
        }
        r[2] = body.trim().replace(String.valueOf(SEP_PH), String.valueOf(SEP));
        return r;
    }

    private static String[] splitLine(String line) {
        String[] r = splitLineInner(line);
        for (int i = 0; i < r.length; i++) {
            if (r[i] != null) r[i] = r[i].replace(String.valueOf(SEP_PH), String.valueOf(SEP));
        }
        // 第 4 段（模式）为空时，检查 token 段末尾是否挂着模式词
        if (r[3] != null && r[3].length() == 0 && r[2] != null) {
            String[] modes = new String[]{"capture", "login", "hang_up"};
            for (String m : modes) {
                String suffix = SEP + m;
                if (r[2].endsWith(suffix)) {
                    r[2] = r[2].substring(0, r[2].length() - suffix.length()).trim();
                    r[3] = m;
                    break;
                }
            }
        }
        return r;
    }

    /**
     * 拼一行记录：备注 时间|游戏|token|模式。
     *
     * 原实现用魔数 10/8/6/9 表示各种后缀长度，改一个字符就会错位，
     * 这里改成用后缀字符串本身的长度计算。
     */
    public static String joinLine(String note, String time, String game, String rawSafe, String mode) {
        String rs = rawSafe == null ? "" : rawSafe;
        String[] tails = new String[]{
                SEP_ESC + "capture", String.valueOf(SEP) + "capture",
                SEP_ESC + "login", String.valueOf(SEP) + "login",
                SEP_ESC + "hang_up", String.valueOf(SEP) + "hang_up"
        };
        for (int g = 0; g < 6; g++) {
            boolean stripped = false;
            for (String tail : tails) {
                if (rs.endsWith(tail)) {
                    rs = rs.substring(0, rs.length() - tail.length());
                    stripped = true;
                    break;
                }
            }
            if (!stripped) break;
        }

        StringBuilder sb = new StringBuilder();
        if (note != null && note.length() > 0) {
            sb.append(note.replace(String.valueOf(SEP), "/")
                    .replace((char) 10, (char) 32)
                    .replace((char) 13, (char) 32)).append(" ");
        }
        sb.append(time).append(SEP).append(game).append(SEP).append(rs).append(SEP).append(mode);
        return sb.toString();
    }

    private static Set<String> knownGameCache = null;
    private static long knownGameCacheAt = 0;

    private static Set<String> knownGames() {
        long now = System.currentTimeMillis();
        if (knownGameCache != null && now - knownGameCacheAt < 60000L) return knownGameCache;
        Set<String> set = new HashSet<String>();
        try {
            List<String> a = importGames();
            for (int i = 0; i < a.size(); i++) {
                String g = a.get(i);
                if (g != null && g.length() > 0) set.add(g.trim());
            }
        } catch (Throwable t) {
        }
        try {
            List<String> b = allGames();
            for (int i = 0; i < b.size(); i++) {
                String g = b.get(i);
                if (g != null && g.length() > 0) set.add(g.trim());
            }
        } catch (Throwable t) {
        }
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
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
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
            if (c < '0' || c > '9') allNum = false;
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || c == '_' || c == '-' || c == '.' || c == '=' || c == '&'
                    || c == ':' || c == '/' || c == '?' || c == '%';
            if (!ok) clean = false;
        }
        if (allNum) return -1;
        if (t.indexOf(' ') >= 0) return -1;

        int sc = 0;
        if (t.indexOf('=') >= 0) sc += 3;
        if (t.indexOf('&') >= 0) sc += 3;
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
        if (t.charAt(4) != '-') return false;
        if (!isDigitAt(t, 5) || !isDigitAt(t, 6)) return false;
        if (t.charAt(7) != '-') return false;
        if (!isDigitAt(t, 8) || !isDigitAt(t, 9)) return false;
        return true;
    }

    public static Entry parseLine(String line, String game) {
        if (line == null) return null;
        String s = line.trim();
        if (s.length() == 0) return null;

        Entry e = new Entry();
        List<String> parts = splitByPipe(s);
        String date = "";
        String appid = "";
        String gameName = "";
        String mode = "";
        int tokIdx = -1;
        int tokScore = -1;
        List<String> notes = new ArrayList<String>();
        Set<String> noteSeen = new HashSet<String>();

        // 第一步：把每段里夹带的日期/时间抽出来
        for (int i = 0; i < parts.size(); i++) {
            String p0 = parts.get(i).trim();
            if (p0.length() == 0) {
                parts.set(i, "");
                continue;
            }
            int di0 = firstDateIndex(p0);
            if (di0 < 0) continue;
            if (di0 > 0) {
                String nt0 = p0.substring(0, di0).trim();
                if (nt0.length() > 0 && noteSeen.add(nt0)) notes.add(nt0);
            }
            int de0 = di0 + dateTokenEnd(p0.substring(di0));
            if (de0 > p0.length()) de0 = p0.length();
            if (date.length() == 0) date = p0.substring(di0, de0).trim();
            if (date.length() > 10 && date.charAt(10) != ' ') {
                date = date.substring(0, 10) + ' ' + date.substring(10);
            }
            String rest0 = stripLead(p0.substring(de0));
            int tpe = timePrefixEnd(rest0);
            if (tpe > 0 && date.indexOf(' ') < 0) {
                date = date + ' ' + rest0.substring(0, tpe);
                rest0 = rest0.substring(tpe);
            }
            parts.set(i, rest0);
        }

        // 第二步：选出最像 token 的一段
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i).trim();
            if (p.length() == 0) continue;
            if (isDateSeg(p)) {
                if (date.length() == 0) date = p;
                continue;
            }
            int sc = tokenCandidate(p);
            if (sc > tokScore) {
                tokScore = sc;
                tokIdx = i;
            }
        }

        // 第三步：其余段分别判定为模式 / appid / 游戏名 / 备注
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i).trim();
            if (p.length() == 0) continue;
            if (isDateSeg(p)) continue;
            if (i == tokIdx) continue;
            if (isModeWord(p)) {
                if (mode.length() == 0) mode = p;
                continue;
            }
            if (p.length() >= 5 && p.length() <= 12 && isAllDigits(p)) {
                if (appid.length() == 0) appid = p;
                continue;
            }
            if (gameName.length() == 0 && isKnownGame(p)) {
                gameName = p;
                continue;
            }
            if (!p.matches("[0-9][0-9]?:[0-9][0-9](:[0-9][0-9])?") && noteSeen.add(p)) {
                notes.add(p);
            }
        }

        if (gameName.length() == 0 && game != null && game.length() > 0) gameName = game;

        String tokRaw = "";
        if (tokIdx >= 0 && tokIdx < parts.size()) {
            tokRaw = parts.get(tokIdx).trim().replace(SEP_ESC, String.valueOf(SEP));
        }
        if (tokRaw.length() == 0) tokRaw = s.replace(SEP_ESC, String.valueOf(SEP));
        e.raw = tokRaw;
        e.accessToken = extractToken(tokRaw);
        if (e.accessToken.length() == 0 && tokRaw.length() > 0
                && tokRaw.indexOf('=') < 0 && tokenCandidate(tokRaw) >= 0) {
            e.accessToken = tokRaw;
        }

        if (appid.length() == 0) {
            String a = appidOfUrl(tokRaw);
            if (a != null) {
                appid = a;
            } else if (tokRaw.length() >= 5 && tokRaw.length() <= 12 && isAllDigits(tokRaw)) {
                appid = tokRaw;
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
            if (nb.length() > 0) nb.append(' ');
            nb.append(notes.get(i));
        }
        e.note = stripDateFromNote(nb.toString()
                .replace((char) 10, ' ')
                .replace((char) 13, ' ')
                .replace(SEP, '/'));

        // 兼容旧格式：模式词被拼在 raw 末尾
        if (e.mode.length() == 0) {
            int mk = e.raw.lastIndexOf(SEP);
            if (mk > 0) {
                String mt = e.raw.substring(mk + 1).trim();
                if (isModeWord(mt)) {
                    e.mode = mt;
                    e.raw = e.raw.substring(0, mk);
                }
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
        return p.indexOf("access_token=") >= 0
                || lp.startsWith("auth://")
                || lp.startsWith("http://")
                || lp.startsWith("https://");
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
        if (t.indexOf("access_token=") >= 0 || t.startsWith("auth://")
                || t.startsWith("http://") || t.startsWith("https://")) score += 10;
        if (t.indexOf('=') >= 0) score += 3;
        if (t.indexOf('&') >= 0) score += 3;
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
        if (isModeWord(t)) return false;
        return true;
    }

    private static boolean isDigitAt(String s, int i) {
        return i >= 0 && i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9';
    }

    private static int timePrefixEnd(String s) {
        if (s == null || s.length() < 4) return 0;
        int i = 0;
        while (i < s.length() && i < 2 && isDigitAt(s, i)) i++;
        if (i < 1 || i >= s.length() || s.charAt(i) != ':') return 0;
        int j = i + 1;
        int d = 0;
        while (j < s.length() && d < 2 && isDigitAt(s, j)) {
            j++;
            d++;
        }
        if (d < 1) return 0;
        if (j + 2 < s.length() && s.charAt(j) == ':' && isDigitAt(s, j + 1) && isDigitAt(s, j + 2)) j = j + 3;
        return j;
    }

    private static int firstDateIndex(String s) {
        for (int i = 0; i + 9 < s.length(); i++) {
            if (isDigitAt(s, i) && isDigitAt(s, i + 1) && isDigitAt(s, i + 2) && isDigitAt(s, i + 3)
                    && s.charAt(i + 4) == '-'
                    && isDigitAt(s, i + 5) && isDigitAt(s, i + 6)
                    && s.charAt(i + 7) == '-'
                    && isDigitAt(s, i + 8) && isDigitAt(s, i + 9)) return i;
        }
        return -1;
    }

    private static int dateTokenEnd(String s) {
        if (s.length() < 10) return s.length();
        int n = 10;
        if (n + 2 < s.length() && (s.charAt(n) == ' ' || s.charAt(n) == 'T')
                && isDigitAt(s, n + 1) && isDigitAt(s, n + 2)) {
            n = n + 3;
        } else if (isDigitAt(s, n) && isDigitAt(s, n + 1)) {
            n = n + 2;
        } else {
            return n;
        }
        for (int k = 0; k < 2; k++) {
            if (n + 2 < s.length() && s.charAt(n) == ':' && isDigitAt(s, n + 1) && isDigitAt(s, n + 2)) {
                n = n + 3;
            } else {
                break;
            }
        }
        return n;
    }

    private static String stripLead(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == SEP) i++;
            else break;
        }
        return s.substring(i);
    }

    private static List<String> splitByPipe(String s) {
        List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == SEP) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    public static List<Entry> loadGame(String game) {
        List<Entry> list = new ArrayList<>();
        File f = gameFile(game);
        if (!f.exists()) return list;
        Scanner sc = null;
        try {
            sc = new Scanner(f, "UTF-8");
            while (sc.hasNextLine()) {
                String rawLine = sc.nextLine();
                Entry e = parseLine(rawLine, game);
                if (e != null) e.line = rawLine;
                if (e != null && !e.accessToken.isEmpty()) list.add(e);
            }
        } catch (Exception ignored) {
        } finally {
            if (sc != null) {
                try {
                    sc.close();
                } catch (Exception ignored) {
                }
            }
        }
        return list;
    }

    public static List<String> cloudOnlyGames() {
        List<String> names = new ArrayList<String>();
        try {
            android.content.SharedPreferences sp =
                    appContext.getSharedPreferences("myPrefs", android.content.Context.MODE_PRIVATE);
            String synced = sp.getString("synced_data", "");
            for (String line : synced.split(";")) {
                int k = line.indexOf(SEP);
                if (k <= 0) continue;
                String name = line.substring(0, k).trim();
                String appid = line.substring(k + 1).trim();
                if (name.isEmpty() || appid.isEmpty()) continue;
                if (!names.contains(name)) names.add(name);
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    // ------------------------------------------------------------------
    // appid -> 游戏名 索引缓存（第 8 项）
    // ------------------------------------------------------------------

    private static volatile Map<String, String> appidGameIndex = null;
    private static volatile long appidGameAt = 0;
    private static final long APPID_TTL = 30000L;

    private static Map<String, String> appidIndex() {
        long now = System.currentTimeMillis();
        Map<String, String> cached = appidGameIndex;
        if (cached != null && now - appidGameAt < APPID_TTL) return cached;

        Map<String, String> map = new HashMap<String, String>();
        try {
            File[] fs = rootDir().listFiles();
            if (fs != null) {
                for (File f : fs) {
                    String fn = f.getName();
                    if (fn == null || !fn.toLowerCase().endsWith(".txt")) continue;
                    String game = fn.substring(0, fn.length() - 4);
                    Scanner sc = null;
                    try {
                        sc = new Scanner(f, "UTF-8");
                        while (sc.hasNextLine()) {
                            String ln = sc.nextLine();
                            Matcher m = DIGIT_RUN.matcher(ln);
                            while (m.find()) {
                                String ap = m.group();
                                if (!map.containsKey(ap)) map.put(ap, game);
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (sc != null) {
                            try {
                                sc.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        appidGameIndex = map;
        appidGameAt = now;
        return map;
    }

    public static String gameOfAppidLocal(String appid) {
        if (appid == null || appid.isEmpty()) return "";
        String g = gameOfAppid(appid);
        if (g != null && !g.isEmpty()) return g;
        String byIndex = appidIndex().get(appid);
        if (byIndex != null && !byIndex.isEmpty()) return byIndex;
        return "";
    }

    public static String resolveGame(String game, String url) {
        String aid = appidOfUrl(url);
        if (game != null && !game.isEmpty() && !"未知游戏".equals(game) && !"其他".equals(game)) return game;
        String byAid = gameOfAppidLocal(aid);
        if (!byAid.isEmpty()) return byAid;
        String byUrl = gameOfUrl(url);
        if (byUrl != null && !byUrl.isEmpty()) return byUrl;
        if (aid != null && !aid.isEmpty()) {
            String nm = gameOfAppid(aid);
            if (nm != null && !nm.isEmpty()) return nm;
        }
        return game == null || game.isEmpty() ? "其他" : game;
    }

    public static List<String> importGames() {
        List<String> names = new ArrayList<String>();
        try {
            android.content.SharedPreferences sp =
                    appContext.getSharedPreferences("myPrefs", android.content.Context.MODE_PRIVATE);
            String synced = sp.getString("synced_data", "");
            for (String line : synced.split(";")) {
                int k = line.indexOf(SEP);
                if (k <= 0) continue;
                String name = line.substring(0, k).trim();
                if (name.isEmpty()) continue;
                if (!names.contains(name)) names.add(name);
            }
        } catch (Throwable ignored) {
        }
        if (names.isEmpty()) {
            names = allGames();
            if (names.isEmpty()) names.add("其他");
        }
        return names;
    }

    private static volatile long allGamesAt = 0;
    private static volatile List<String> allGamesCache = null;

    /** 失效所有派生缓存。写操作后调用。 */
    public static synchronized void invalidateGamesCache() {
        allGamesAt = 0;
        allGamesCache = null;
        knownGameCache = null;
        knownGameCacheAt = 0;
        cloudGamesCache = null;
        cloudGamesAt = 0;
        appidGameIndex = null;
        appidGameAt = 0;
        tokenCache.clear();
    }

    public static List<String> allGames() {
        long n = System.currentTimeMillis();
        List<String> c = allGamesCache;
        if (c != null && n - allGamesAt < 30000) return c;
        List<String> r = new ArrayList<String>();
        try {
            android.content.SharedPreferences sp = appContext.getSharedPreferences("myPrefs", 0);
            for (String ln : sp.getString("synced_data", "").split(";")) {
                int k = ln.indexOf(SEP);
                if (k <= 0) continue;
                String nm = ln.substring(0, k).trim();
                if (nm.length() > 0 && !r.contains(nm)) r.add(nm);
            }
        } catch (Throwable t) {
        }
        for (String g : scanLocal()) {
            if (!r.contains(g)) r.add(g);
        }
        allGamesCache = r;
        allGamesAt = n;
        return r;
    }

    public static List<String> scanLocal() {
        List<String> o = new ArrayList<String>();
        try {
            File[] fs = rootDir().listFiles();
            if (fs != null) {
                for (File f : fs) {
                    String fn = f.getName();
                    if (fn == null || !fn.toLowerCase().endsWith(".txt")) continue;
                    String nm = fn.substring(0, fn.length() - 4).trim();
                    if (nm.length() > 0 && !o.contains(nm)) o.add(nm);
                }
            }
        } catch (Throwable t) {
        }
        return o;
    }

    public static List<String> listGameFolders() {
        return scanLocal();
    }

    public static String nextGameWithAccounts(String current) {
        if (current != null && !current.isEmpty() && !loadGame(current).isEmpty()) return current;
        List<String> all = listGameFolders();
        if (all.isEmpty()) return "";
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
        Process p = null;
        java.io.BufferedReader br = null;
        try {
            p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes("dumpsys activity recents\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String ln;
            while ((ln = br.readLine()) != null) {
                String g = gameOfPackage(ln);
                if (!g.isEmpty()) return g;
            }
            return "";
        } catch (Exception e) {
            return "";
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ignored) {
                }
            }
            if (p != null) p.destroy();
        }
    }

    /**
     * root 追加写。
     * 内容先 base64 再解码落盘，路径用单引号包裹，避免被 shell 解释；
     * 权限用 660，比原来的 666 更严格。
     */
    public static boolean rootAppend(File f, String line) {
        Process p = null;
        try {
            String txt = line.endsWith("\n") ? line : line + "\n";
            String payload = android.util.Base64.encodeToString(
                    txt.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            String dir = RootUtil.shellQuote(f.getParentFile().getAbsolutePath());
            String path = RootUtil.shellQuote(f.getAbsolutePath());
            String cmd = "mkdir -p " + dir
                    + "; echo " + payload + " | base64 -d >> " + path
                    + "; chmod 660 " + path;
            p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            int code = p.waitFor();
            if (code != 0) lastError = "root 退出码 " + code;
            return code == 0;
        } catch (Exception e) {
            return RootUtil.append(f, line);
        } finally {
            if (p != null) p.destroy();
        }
    }

    public static List<Entry> loadAll() {
        List<Entry> all = new ArrayList<>();
        for (String g : listGameFolders()) all.addAll(loadGame(g));
        return all;
    }

    public static String sanitizeGame(String g) {
        if (g == null) return "其他";
        String s = g.trim();
        if (s.isEmpty() || "未知游戏".equals(s)) return "其他";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == SEP || c < 32) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String r = sb.toString().trim();
        if (r.isEmpty() || r.equals(".") || r.equals("..")) return "其他";
        return r;
    }

    // ------------------------------------------------------------------
    // 单文件 token 集合缓存（第 9 项）
    // ------------------------------------------------------------------

    private static final class TokenIndex {
        long length;
        long modified;
        final Set<String> tokens = new HashSet<String>();
        final Set<String> rawLines = new HashSet<String>();
    }

    private static final Map<String, TokenIndex> tokenCache = new HashMap<String, TokenIndex>();

    /**
     * 取某个文件的 token 索引。文件长度与修改时间都没变就直接复用，
     * 避免 addEntry 每次都为去重而重新解析整份文件。
     */
    private static TokenIndex tokenIndexOf(File f, List<String> linesIn) {
        String key = f.getAbsolutePath();
        long len = f.exists() ? f.length() : 0;
        long mod = f.exists() ? f.lastModified() : 0;

        TokenIndex idx = tokenCache.get(key);
        if (idx != null && idx.length == len && idx.modified == mod) return idx;

        TokenIndex fresh = new TokenIndex();
        fresh.length = len;
        fresh.modified = mod;
        if (linesIn != null) {
            for (String ln : linesIn) {
                fresh.rawLines.add(ln.trim());
                Entry pe = parseLine(ln, "");
                if (pe != null && pe.accessToken != null && !pe.accessToken.isEmpty()) {
                    fresh.tokens.add(pe.accessToken);
                }
            }
        }
        tokenCache.put(key, fresh);
        return fresh;
    }

    public static int addEntry(String game, String line) {
        game = sanitizeGame(game);
        if (line == null) return -1;
        File f = gameFile(game);

        String token = grab(line, "access_token=([^&\\s]+)");
        if (token.isEmpty()) {
            Entry pe = parseLine(line, "");
            if (pe != null) token = pe.accessToken;
        }

        // 读取旧内容（写入时需要整体重写）
        List<String> out = new ArrayList<String>();
        if (f.exists()) {
            Scanner sc = null;
            try {
                sc = new Scanner(f, "UTF-8");
                while (sc.hasNextLine()) out.add(sc.nextLine());
            } catch (Exception e) {
                lastError = "读取旧记录失败，已中止写入以免覆盖: " + e;
                return -1;
            } finally {
                if (sc != null) {
                    try {
                        sc.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // 去重：命中缓存时是 O(1) 查表，不再逐行 parseLine
        TokenIndex idx = tokenIndexOf(f, out);
        boolean dup = token.isEmpty()
                ? idx.rawLines.contains(line.trim())
                : idx.tokens.contains(token);
        if (dup) return 0;

        out.add(line);
        if (!writeLinesAtomic(f, out)) return -1;
        tokenCache.remove(f.getAbsolutePath());
        invalidateGamesCache();
        return 1;
    }

    private static String stripDateFromNote(String note) {
        if (note == null) return "";
        String s = note;
        for (int g = 0; g < 6; g++) {
            int di = firstDateIndex(s);
            if (di < 0) break;
            int de = di + dateTokenEnd(s.substring(di));
            if (de > s.length()) de = s.length();
            String a = s.substring(0, di).trim();
            String b = s.substring(de).trim();
            s = (a + " " + b).trim();
        }
        return s;
    }

    public static boolean isValidTokenStr(String tk) {
        if (tk == null) return false;
        String t = tk.trim();
        if (t.length() < 8) return false;
        if (isDateSeg(t)) return false;
        if (hasCJK(t)) return false;
        return true;
    }

    public static int cleanEmptyFiles() {
        int n = 0;
        try {
            File dir = rootDir();
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    String fn = f.getName();
                    if (fn == null || !fn.toLowerCase().endsWith(".txt")) continue;
                    boolean hasTok = false;
                    Scanner sc = null;
                    try {
                        sc = new Scanner(f, "UTF-8");
                        while (sc.hasNextLine()) {
                            String ln = sc.nextLine();
                            if (ln == null || ln.trim().length() == 0) continue;
                            Entry pe = parseLine(ln, "");
                            String tk = (pe == null) ? "" : pe.accessToken;
                            if (isValidTokenStr(tk)) {
                                hasTok = true;
                                break;
                            }
                        }
                    } catch (Throwable t2) {
                    } finally {
                        if (sc != null) {
                            try {
                                sc.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (!hasTok) {
                        try {
                            if (f.delete()) n++;
                        } catch (Throwable t3) {
                        }
                        try {
                            new File(f.getAbsolutePath() + ".bak").delete();
                        } catch (Throwable t4) {
                        }
                    }
                }
            }
            File[] bs = dir.listFiles();
            if (bs != null) {
                for (File b : bs) {
                    String bn = b.getName();
                    if (bn == null || !bn.toLowerCase().endsWith(".txt.bak")) continue;
                    String base = bn.substring(0, bn.length() - 4);
                    File origin = new File(dir, base);
                    if (!origin.exists()) {
                        try {
                            if (b.delete()) n++;
                        } catch (Throwable t5) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
        }
        return n;
    }

    public static int[] saveBatch(List<String[]> pairs) {
        int[] r = new int[]{0, 0, 0};
        if (pairs == null) return r;
        for (String[] pr : pairs) {
            if (pr == null || pr.length < 2) continue;
            int x = addEntry(pr[0], pr[1]);
            if (x == 1) r[0]++;
            else if (x == 0) r[1]++;
            else r[2]++;
        }
        cleanEmptyFiles();
        return r;
    }

    public static boolean saveGameEntry(String game, String line) {
        return addEntry(game, line) >= 0;
    }

    static String appidCol(String appid, String game, String raw) {
        if (appid != null && appid.length() > 0 && isAllDigits(appid)) return appid;
        String a = appidOfUrl(raw);
        if (a != null && a.length() > 0) return a;
        a = appidOfGame(game);
        return a == null ? "" : a;
    }

    static String withAppid(String u, String g) {
        if (u == null) return u;
        if (u.contains("appid=") || u.contains("client_id=")) return u;
        String a = appidOfGame(g);
        if (a == null || a.isEmpty()) {
            try {
                String su = appContext.getSharedPreferences("myPrefs", 0)
                        .getString("selected_game_url", "");
                Matcher m = CLIENT_ID.matcher(su == null ? "" : su);
                if (m.find()) a = m.group(1);
            } catch (Exception ignored) {
            }
        }
        if (a == null || a.isEmpty()) return u;
        return u + (u.contains("?") ? "&" : "?") + "appid=" + a;
    }

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
        List<String> keep = new ArrayList<String>();
        Scanner sc = null;
        try {
            sc = new Scanner(f, "UTF-8");
            while (sc.hasNextLine()) {
                String ln = sc.nextLine();
                if (lineHasToken(ln, token)) continue;
                keep.add(ln);
            }
        } catch (Exception e) {
            lastError = "read fail: " + e.getMessage();
            return false;
        } finally {
            if (sc != null) {
                try {
                    sc.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (!writeLinesAtomic(f, keep)) {
            lastError = "delete fail";
            return false;
        }
        tokenCache.remove(f.getAbsolutePath());
        invalidateGamesCache();
        return true;
    }

    public static boolean deleteEntryByLine(String game, String line) {
        if (line == null) return false;
        File f = gameFile(game);
        if (!f.exists()) return false;
        List<String> keep = new ArrayList<String>();
        boolean removed = false;
        Scanner sc = null;
        try {
            sc = new Scanner(f, "UTF-8");
            while (sc.hasNextLine()) {
                String ln = sc.nextLine();
                if (!removed && ln.equals(line)) {
                    removed = true;
                    continue;
                }
                keep.add(ln);
            }
        } catch (Exception e) {
            lastError = "read fail: " + e.getMessage();
            return false;
        } finally {
            if (sc != null) {
                try {
                    sc.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (!removed) return false;
        if (!writeLinesAtomic(f, keep)) {
            lastError = "delete fail";
            return false;
        }
        tokenCache.remove(f.getAbsolutePath());
        invalidateGamesCache();
        return true;
    }

    public static List<String> sortedGames() {
        List<String> list = new ArrayList<String>(listGameFolders());
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

    public static boolean updateNote(String game, String token, String note) {
        File f = gameFile(game);
        if (!f.exists()) return false;
        List<String> lines = new ArrayList<String>();
        boolean found = false;
        Scanner sc = null;
        try {
            sc = new Scanner(f, java.nio.charset.StandardCharsets.UTF_8.name());
            while (sc.hasNextLine()) {
                String line = sc.nextLine();
                if (!found && lineHasToken(line, token)) {
                    String nt = note == null ? "" : note.trim();
                    Entry pe = parseLine(line, "");
                    lines.add(joinLine(nt, pe.time, appidCol(pe.appid, pe.game, pe.raw),
                            pe.raw.replace(String.valueOf(SEP), SEP_ESC), pe.mode));
                    found = true;
                } else {
                    lines.add(line);
                }
            }
        } catch (Exception ex) {
            lastError = String.valueOf(ex);
            return false;
        } finally {
            if (sc != null) {
                try {
                    sc.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (!found) return false;
        if (!writeLinesAtomic(f, lines)) return false;
        tokenCache.remove(f.getAbsolutePath());
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
        } catch (Exception ignored) {
        }
    }

    public static int exportAll() {
        int n = 0;
        File dst = new File(downloadDir(), ROOT_NAME);
        try {
            if (!dst.exists()) dst.mkdirs();
        } catch (Throwable ignored) {
        }
        Set<String> seen = new HashSet<String>();
        FileWriter w = null;
        try {
            w = new FileWriter(new File(dst, FILE_NAME), false);
            for (String g : allGames()) {
                File f = gameFile(g);
                if (!f.exists()) continue;
                Scanner sc = null;
                try {
                    sc = new Scanner(f, "UTF-8");
                    while (sc.hasNextLine()) {
                        String ln = sc.nextLine().trim();
                        if (ln.isEmpty()) continue;
                        Entry e = parseLine(ln, g);
                        if (e == null || e.accessToken.isEmpty()) continue;
                        if (!seen.add(e.accessToken)) continue;
                        String rawSafe = e.raw.replace(String.valueOf(SEP), SEP_ESC);
                        w.append(joinLine(e.note, e.time, appidCol(e.appid, e.game, e.raw), rawSafe, e.mode));
                        w.append((char) 10);
                        n++;
                    }
                } finally {
                    if (sc != null) {
                        try {
                            sc.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            w.flush();
        } catch (Exception ex) {
            lastError = String.valueOf(ex);
            return 0;
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                }
            }
        }
        return n;
    }

    public static class ImportResult {
        public int added = 0;
        public int skipped = 0;
        public int existing = 0;
        public List<String[]> resolved = new ArrayList<String[]>();
        public List<String> unknownLines = new ArrayList<String>();
    }

    public static ImportResult importFromUri(android.content.Context ctx, android.net.Uri uri) {
        ImportResult res = new ImportResult();
        java.io.InputStream is = null;
        java.io.BufferedReader br = null;
        try {
            is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) {
                lastError = "无法读取文件";
                return res;
            }
            br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
            String line;
            Set<String> seen = new HashSet<String>();
            List<String> unknown = new ArrayList<String>();
            String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                    .format(new java.util.Date());
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Entry pe = parseAnyInput(line);
                if (pe == null || pe.accessToken.isEmpty()) {
                    res.skipped++;
                    continue;
                }
                if (!seen.add(pe.accessToken)) {
                    res.skipped++;
                    continue;
                }
                String game = "";
                if (pe.game != null && !pe.game.isEmpty() && !pe.game.matches("[0-9]+")) game = pe.game.trim();
                if (game.isEmpty()) {
                    String byUrl = gameOfUrl(line);
                    if (byUrl != null && !byUrl.isEmpty() && !byUrl.matches("[0-9]+")) game = byUrl;
                }
                if (game.isEmpty() && pe.appid != null && !pe.appid.isEmpty()) {
                    String byAid = gameOfAppid(pe.appid);
                    if (byAid != null && !byAid.isEmpty()) game = byAid;
                }
                if (game.isEmpty()) {
                    unknown.add(line);
                    continue;
                }
                String appid = appidCol(pe.appid, game, pe.raw);
                String t = (pe.time == null || pe.time.isEmpty()) ? now : pe.time;
                String keep = joinLine(pe.note, t, appid, pe.raw.replace(String.valueOf(SEP), SEP_ESC), pe.mode);
                res.resolved.add(new String[]{game, keep});
            }
            res.unknownLines.addAll(unknown);
            res.added = res.resolved.size();
        } catch (Exception e) {
            lastError = "导入失败: " + e;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ignored) {
                }
            }
            try {
                if (is != null) is.close();
            } catch (Throwable ignored) {
            }
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
            Set<String> existing = new HashSet<String>();
            List<String> out = new ArrayList<String>();
            if (dst.exists()) {
                Scanner sc = new Scanner(dst, "UTF-8");
                try {
                    while (sc.hasNextLine()) {
                        String ln = sc.nextLine();
                        out.add(ln);
                        String t = tokenOf(ln);
                        if (!t.isEmpty()) existing.add(t);
                    }
                } finally {
                    sc.close();
                }
            }
            Scanner sc2 = new Scanner(src, "UTF-8");
            try {
                while (sc2.hasNextLine()) {
                    String ln = sc2.nextLine();
                    if (ln.trim().isEmpty()) continue;
                    String t = tokenOf(ln);
                    if (!t.isEmpty() && existing.contains(t)) continue;
                    out.add(ln);
                    if (!t.isEmpty()) existing.add(t);
                    added++;
                }
            } finally {
                sc2.close();
            }
            File tmp = new File(dst.getAbsolutePath() + ".tmp");
            FileWriter w = new FileWriter(tmp, false);
            try {
                for (int i = 0; i < out.size(); i++) {
                    w.append(out.get(i));
                    w.append((char) 10);
                }
                w.flush();
            } finally {
                w.close();
            }
            if (out.size() > 0 && tmp.length() == 0) {
                tmp.delete();
                throw new java.io.IOException("tmp empty");
            }
            File bak = new File(dst.getAbsolutePath() + ".bak");
            if (dst.exists()) {
                if (bak.exists()) bak.delete();
                if (!dst.renameTo(bak)) {
                    tmp.delete();
                    throw new java.io.IOException("backup failed");
                }
            }
            if (!tmp.renameTo(dst)) {
                if (bak.exists()) bak.renameTo(dst);
                tmp.delete();
                throw new java.io.IOException("replace failed");
            }
            tokenCache.remove(dst.getAbsolutePath());
        } catch (Exception e) {
            lastError = "导入失败: " + e.getMessage();
            added = 0;
        }
        return added;
    }

    private static boolean writeLinesAtomic(File f, List<String> lines) {
        try {
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileWriter w = new FileWriter(tmp, false);
            try {
                for (int i = 0; i < lines.size(); i++) {
                    w.append(lines.get(i));
                    w.append((char) 10);
                }
                w.flush();
            } finally {
                w.close();
            }
            if (!lines.isEmpty() && tmp.length() == 0) {
                tmp.delete();
                return false;
            }
            File bak = new File(f.getAbsolutePath() + ".bak");
            if (f.exists()) {
                if (bak.exists()) bak.delete();
                if (!f.renameTo(bak)) {
                    tmp.delete();
                    return false;
                }
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
