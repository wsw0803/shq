package com.tencent.tim;

public class LogScrubber {
    public static String scrub(String s) {
        if (s == null) return "";
        // 1. GitHub Token
        String res = s.replaceAll("gh[pousr]_[A-Za-z0-9]{10,}", "ghp_****");
        
        // 2. JSON 规格的游戏Token (允许各种空格和引号)
        res = res.replaceAll("(?i)(\\\"(?:access_token|pay_token|openid|openkey)\\\"\\\\s*:\\\\s*\\\")[^\\\"]+", "$1****");
        
        // 3. URL 格式的游戏Token
        res = res.replaceAll("(?i)((?:access_token|pay_token|openid|openkey)=)[^&\\\\s\\\"']+", "$1****");
        
        return res;
    }
}
