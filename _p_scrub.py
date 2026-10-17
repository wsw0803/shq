import io
p='app/src/main/java/com/tencent/tim/PushManager.java'
s=io.open(p,encoding='utf-8').read()
anchor='    public static String mask(String tk) {'
assert anchor in s
add='''    private static final java.util.regex.Pattern TOK = java.util.regex.Pattern.compile("gh[pousr]_[A-Za-z0-9]{10,}");

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

'''
s=s.replace(anchor,add+anchor,1)
io.open(p,'w',encoding='utf-8').write(s)
print('SCRUB_OK')
