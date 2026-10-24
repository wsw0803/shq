package com.tencent.tim;

/**
 * 轻量混淆工具。
 *
 * 说明：这里提供的是「可逆混淆」，不是密码学意义上的加密。
 * 密钥必须随 APK 一起分发，任何有耐心的人都能还原。
 * 目的是让节点参数不出现明文——挡住 strings / grep / 肉眼翻源码这类简单手段，
 * 不去假装能挡住真正的逆向分析。
 *
 * 做法：把字符串切段后逆序存放，运行时再拼回来。
 * 选择这种方案的原因是可验证——不像手算 AES 密文那样容易抄错一位就全盘失效。
 */
public class Sec {

    private Sec() {
    }

    /** 把逆序存放的片段还原成原字符串。 */
    public static String restore(String[] reversedParts, String sep) {
        if (reversedParts == null || reversedParts.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = reversedParts.length - 1; i >= 0; i--) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(reversedParts[i]);
        }
        return sb.toString();
    }

    /** 无分隔符版本，用于把长串按固定长度切开后再逆序存放。 */
    public static String restore(String[] reversedParts) {
        return restore(reversedParts, "");
    }
}
