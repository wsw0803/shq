package com.tencent.tim;

public class LogScrubber {
    public static String scrub(String s) {
        if (s == null) return "";
        String res = s.replaceAll("gh[pousr]_[A-Za-z0-9]{10,}", "ghp_****");
        String[] keys = {"access_token", "pay_token", "openid", "openkey"};
        for (String k : keys) {
            // 1. 处理 JSON 格式 uvkey:value""
            String p1 = "\"" + k + "\":\"";
            int idx = 0;
            while ((idx = res.indexOf(p1, idx)) != -1) {
                int start = idx + p1.length();
                int end = res.indexOf("\"", start);
                if (end != -1) {
                    res = res.substring(0, start) + "****" + res.substring(end);
                    idx = start + 4;
                } else {
                    break;
                }
            }
            // 2. 处理 URL 格式 uvkey=value
            String p2 = k + "=";
            idx = 0;
            while ((idx = res.indexOf(p2, idx)) != -1) {
                int start = idx + p2.length();
                int end1 = res.indexOf("&", start);
                int end2 = res.indexOf(" ", start);
                int end3 = res.indexOf("\"", start);
                int end = -1;
                if (end1 != -1) end = end == -1 ? end1 : Math.min(end, end1);
                if (end2 != -1) end = end == -1 ? end2 : Math.min(end, end2);
                if (end3 != -1) end = end == -1 ? end3 : Math.min(end, end3);
                if (end != -1) {
                    res = res.substring(0, start) + "****" + res.substring(end);
                    idx = start + 4;
                } else {
                    if (start < res.length()) {
                        res = res.substring(0, start) + "****";
                    }
                    break;
                }
            }
        }
        return res;
    }
}
