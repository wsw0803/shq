p="app/src/main/java/com/tencent/tim/MainActivity.java"
s=open(p).read()
old="String name = line.substring(0, i);"
new=old+"\n                    if (!java.util.Arrays.asList(TokenStore.GAMES).contains(name)) continue;"
if "TokenStore.GAMES).contains(name)" not in s:
    s=s.replace(old,new)
    open(p,"w").write(s)
print("ok")
