import io
Q=chr(34)
p="/data/data/com.termux/files/home/shq_new/app/src/main/java/com/tencent/tim/TokenStore.java"
L=io.open(p,encoding="utf-8").read().split(chr(10))
O=[]
for ln in L:
    if "proxy.htm" in ln and "return false" in ln:
        O.append("        if (line != null && line.indexOf("+Q+"proxy.htm"+Q+") >= 0) { String at = grab(line, "+Q+"access_token=([^&\\\\s]+)"+Q+"); if (at.isEmpty()) { lastError = "+Q+"SDK 中转页缺少 access_token"+Q+"; return false; } String oid = grab(line, "+Q+"openid=([^&\\\\s]+)"+Q+"); String pt = grab(line, "+Q+"pay_token=([^&\\\\s]+)"+Q+"); String nu = "+Q+"auth://tauth.qq.com/?#access_token="+Q+" + at + (oid.isEmpty() ? "+Q+Q+" : "+Q+"&openid="+Q+" + oid) + (pt.isEmpty() ? "+Q+Q+" : "+Q+"&pay_token="+Q+" + pt) + "+Q+"&ret=0"+Q+"; line = line.replaceAll("+Q+"https?://[^ |]+"+Q+", java.util.regex.Matcher.quoteReplacement(nu)); }")
        continue
    O.append(ln)
io.open(p,"w",encoding="utf-8").write(chr(10).join(O))
print("proxy-normalized", sum(1 for x in O if "quoteReplacement" in x))
