p="app/src/main/java/com/tencent/tim/TokenStore.java"
s=open(p).read()
i=s.index("public static boolean saveGameEntry")
j=s.index("public static String rebuildUrl")
seg=s[i:j]
seg=seg.replace("} catch (Exception ignored) {}","} catch (Exception e) { lastError = String.valueOf(e.getMessage()); }")
s=s[:i]+seg+s[j:]
open(p,"w").write(s)
print("ok")
