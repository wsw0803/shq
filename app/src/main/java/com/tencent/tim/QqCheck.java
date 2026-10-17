package com.tencent.tim;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class QqCheck {

    static final char Q = (char) 34;

    public static String checkSync(String token, String openid) {
        if (token == null || token.length() == 0) return "无效";
        try {
            String me = get("https://graph.qq.com/oauth2.0/me?access_token=" + URLEncoder.encode(token, "UTF-8"));
            String oid = val(me, "openid");
            if (oid == null) oid = openid;
            if (oid == null || oid.length() == 0) return "离线(失效)";
            String nick = "";
            try {
                String info = get("https://graph.qq.com/user/get_user_info?access_token=" + URLEncoder.encode(token, "UTF-8") + "&oauth_consumer_key=1106467070&openid=" + URLEncoder.encode(oid, "UTF-8"));
                String n = val(info, "nickname");
                if (n != null) nick = n;
            } catch (Throwable ig) {}
            String sid = oid.length() > 12 ? oid.substring(0, 6) + "..." + oid.substring(oid.length() - 4) : oid;
            return "在线(有效) ID:" + sid + (nick.length() == 0 ? "" : (" " + nick));
        } catch (Throwable t) {
            return "检测失败";
        }
    }

    static String val(String s, String key) {
        if (s == null) return null;
        int i = s.indexOf(key);
        if (i < 0) return null;
        int q1 = s.indexOf(Q, i);
        if (q1 < 0) return null;
        int c = s.indexOf(58, q1);
        if (c < 0) return null;
        int q2 = s.indexOf(Q, c);
        if (q2 < 0) return null;
        int q3 = s.indexOf(Q, q2 + 1);
        if (q3 < 0) return null;
        return s.substring(q2 + 1, q3);
    }

    static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = br.readLine()) != null) sb.append(l);
        br.close();
        c.disconnect();
        return sb.toString();
    }
}
