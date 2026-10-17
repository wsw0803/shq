import io
N=chr(10)
p="/data/data/com.termux/files/home/shq_new/app/src/main/java/com/tencent/tim/TokenStore.java"
s=io.open(p,encoding="utf-8").read()
i=s.find("proxy.htm")
ls=s.rfind(N,0,i)+1
le=s.find(N,i)
new=N.join([
        if (line != null && line.indexOf("proxy.htm") >= 0) {
            String at = pickParam(line, "access_token=");
            if (at.isEmpty()) { lastError = "SDK中转页无access_token"; return false; }
            String oid = pickParam(line, "openid=");
            String pt = pickParam(line, "pay_token=");
            StringBuilder nb = new StringBuilder("auth://tauth.qq.com/?#access_token=").append(at);
            if (!oid.isEmpty()) nb.append("&openid=").append(oid);
            if (!pt.isEmpty()) nb.append("&pay_token=").append(pt);
            nb.append("&ret=0");
            int p1 = line.indexOf(" | ");
            int p2 = p1 > 0 ? line.indexOf(" | ", p1 + 3) : -1;
            int p3 = line.lastIndexOf(" | ");
            if (p1 > 0 && p2 > p1 && p3 > p2) line = line.substring(0, p2 + 3) + nb.toString() + line.substring(p3);
        }
])
s=s[:ls]+new+s[le:]
old=N.join([
            if (!tmp.renameTo(f)) {
                if (bak.exists()) bak.renameTo(f);
                tmp.delete();
                return false;
            }
])
nw=N.join([
            if (!tmp.renameTo(f)) {
                if (bak.exists()) bak.renameTo(f);
                try {
                    FileWriter w2 = new FileWriter(f, false);
                    for (int k2 = 0; k2 < lines.size(); k2++) { w2.append(lines.get(k2)); w2.append((char)10); }
                    w2.flush(); w2.close();
                    tmp.delete();
                    return true;
                } catch (Exception e2) { lastError = "write fail: " + e2.getMessage(); }
                tmp.delete();
                return false;
            }
])
s=s.replace(old,nw,1)
helper=N.join([
    private static String pickParam(String s, String key) {
        int q = s.indexOf(key);
        if (q < 0) return "";
        String v = s.substring(q + key.length());
        int e = v.indexOf(38);
        if (e >= 0) v = v.substring(0, e);
        int h = v.indexOf(35);
        if (h >= 0) v = v.substring(0, h);
        return v.trim();
    }

])
k=s.find("    public static boolean saveGameEntry")
if k>0 and s.find("pickParam")<0: s=s[:k]+helper+N+s[k:]
io.open(p,"w",encoding="utf-8").write(s)
print("pickParam",s.count("pickParam"),"ret0",s.count("ret=0"))
