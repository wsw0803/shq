package com.tencent.tim;

/**
 * 日志脱敏。
 *
 * 目标：任何进日志的字符串都不应包含可直接利用的凭据或节点信息。
 *
 * 覆盖范围：
 *   - QQ 相关 token：access_token / pay_token / openid / openkey
 *   - GitHub token：ghp_ / gho_ / ghu_ / ghs_ / ghr_
 *   - 代理节点：vless:// 整串、UUID、Reality 公钥、shortId
 *   - 节点地址与端口
 *   - 一般的 user:pass@host 形式
 *
 * 注意这是「防止日志泄露」，不是加密。节点参数在 NodeConfig 里另做混淆。
 */
public class LogScrubber {

    public static String scrub(String s) {
        if (s == null) return "";
        String res = s;

        // 1) 分享链接整串（vless:// vmess:// trojan:// ss:// 等）
        res = res.replaceAll("(?i)(vless|vmess|trojan|ss|ssr|hysteria2?|tuic)://[^\\s\"']+", "$1://****");

        // 2) GitHub token
        res = res.replaceAll("gh[pousr]_[A-Za-z0-9]{10,}", "ghp_****");

        // 3) UUID 形式 8-4-4-4-12
        res = res.replaceAll(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                "****-uuid-****");

        // 4) 当前节点的关键字段（即便拼接进日志也抹掉）
        try {
            String host = NodeConfig.host();
            if (host != null && host.length() > 3) {
                res = res.replace(host, NodeConfig.maskHost(host));
            }
            String sni = NodeConfig.sni();
            if (sni != null && sni.length() > 3) {
                res = res.replace(sni, NodeConfig.maskDomain(sni));
            }
            String pbk = NodeConfig.publicKey();
            if (pbk != null && pbk.length() > 8) {
                res = res.replace(pbk, "****");
            }
            String sid = NodeConfig.shortId();
            if (sid != null && sid.length() >= 4) {
                res = res.replace(sid, "****");
            }
            String uuid = NodeConfig.uuid();
            if (uuid != null && uuid.length() > 8) {
                res = res.replace(uuid, "****");
            }
        } catch (Throwable ignored) {
        }

        // 5) 键值对形式
        String[] keys = {
                "access_token", "pay_token", "openid", "openkey",
                "publicKey", "public_key", "pbk", "shortId", "short_id", "sid",
                "uuid", "id", "password", "passwd", "pwd"
        };
        for (String k : keys) {
            res = maskJson(res, k);
            res = maskQuery(res, k);
        }

        // 6) user:pass@host
        res = res.replaceAll("[A-Za-z0-9._%-]+:[^@\\s/]+@", "****@");

        return res;
    }

    /** 处理 "key":"value" 形式。 */
    private static String maskJson(String s, String key) {
        String p = "\"" + key + "\":\"";
        int idx = 0;
        while ((idx = s.indexOf(p, idx)) != -1) {
            int start = idx + p.length();
            int end = s.indexOf('"', start);
            if (end == -1) break;
            s = s.substring(0, start) + "****" + s.substring(end);
            idx = start + 4;
        }
        return s;
    }

    /** 处理 key=value 形式，value 截止到 & 空格 引号。 */
    private static String maskQuery(String s, String key) {
        String p = key + "=";
        int idx = 0;
        while ((idx = s.indexOf(p, idx)) != -1) {
            // 避免把 access_token 里的 token= 重复处理
            if (idx > 0) {
                char prev = s.charAt(idx - 1);
                if (prev != '&' && prev != '?' && prev != ' ' && prev != '"' && prev != '(') {
                    idx += p.length();
                    continue;
                }
            }
            int start = idx + p.length();
            int end1 = s.indexOf('&', start);
            int end2 = s.indexOf(' ', start);
            int end3 = s.indexOf('"', start);
            int end = -1;
            if (end1 != -1) end = end1;
            if (end2 != -1 && (end == -1 || end2 < end)) end = end2;
            if (end3 != -1 && (end == -1 || end3 < end)) end = end3;
            if (end == -1) {
                if (start < s.length()) s = s.substring(0, start) + "****";
                break;
            }
            s = s.substring(0, start) + "****" + s.substring(end);
            idx = start + 4;
        }
        return s;
    }
}
