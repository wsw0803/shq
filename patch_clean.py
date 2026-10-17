import io
p="/data/data/com.termux/files/home/shq_new/app/src/main/java/com/tencent/tim/MainActivity.java"
s=io.open(p,encoding="utf-8").read()
s=s.replace("    private CheckBox cbLocalSource;\n","")
s=s.replace("    private CheckBox cbCloudSource;\n","")
s=s.replace("        cbLocalSource = findViewById(R.id.cb_local_source);\n","")
s=s.replace("        cbCloudSource = findViewById(R.id.cb_cloud_source);\n","")
s=s.replace("        setupSourceToggles();\n","")
a=s.find("    private void setupSourceToggles() {")
b=s.find("    private String buildLocalUrl(String appid) {")
if a>0 and b>a: s=s[:a]+s[b:]
s=s.replace("        if (getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(KEY_LOCAL_ON, true)) loadGameListFromServer();\n","        loadGameListFromServer();\n")
io.open(p,"w",encoding="utf-8").write(s)
print("done")
