import io, re
p = 'app/src/main/java/com/tencent/tim/PushManager.java'
s = io.open(p, encoding='utf-8').read()

new_method = '''    public static String scrub(String s) {
        if (s == null) return "";
        String res = s.replaceAll("gh[pousr]_[A-Za-z0-9]{10,}", "ghp_****");
        res = res.replaceAll("(?i)(\\\"(?:access_token|pay_token|openid|openkey)\\\"\\\\s*:\\\\s*\\\")[^\\\"]+(\\\")", "$1****$2");
        res = res.replaceAll("(?i)((?:access_token|pay_token|openid|openkey=)[^&\\\\s\\\"']+", "$1****");
        return res;
    }'''

pattern = r'    public static String scrub(String s) \{.*?return sb.toString();\n    \}'
if re.search(pattern, s, flags=re.DOTALL):
    s = re.sub(pattern, new_method, s, flags=re.DOTALL)
    io.open(p, 'w', encoding='utf-8').write(s)
    print('PATCH_OK')
else:
    print('METHOD_NOT_FOUND')
