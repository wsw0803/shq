package com.tencent.tim;

import android.util.Base64;

import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * root 追加写工具。
 *
 * 原实现用 heredoc 把内容拼进 shell 脚本：
 *   cat >> /path <<SHQEOF
 *   <内容>
 *   SHQEOF
 * 一旦内容里出现 SHQEOF、反引号、$，或者内容本身带换行，就会被 shell 解释；
 * 轻则写坏文件，重则执行注入的命令。路径也没有引号，目录含空格时直接失败。
 *
 * 现改为：内容先 base64 编码再解码落盘，路径用单引号包裹并转义，
 * 内容不经过 shell 解析，任意字符都能原样写入。
 * 权限也从 666（任何应用都可改）收紧到 660。
 */
public class RootUtil {

    public static boolean append(File f, String line) {
        if (f == null || line == null) return false;
        File parent = f.getParentFile();
        if (parent == null) return false;

        long before = f.exists() ? f.length() : 0;
        String dir = shellQuote(parent.getAbsolutePath());
        String path = shellQuote(f.getAbsolutePath());
        String payload = Base64.encodeToString(
                (line.endsWith("\n") ? line : line + "\n").getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);

        String script = "mkdir -p " + dir + "\n"
                + "echo " + payload + " | base64 -d >> " + path + "\n"
                + "chmod 660 " + path + "\n";

        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.write(script.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            int code = p.waitFor();
            return code == 0 && f.exists() && f.length() > before;
        } catch (Exception e) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** 单引号包裹并转义内部单引号，避免路径中的空格或特殊字符被 shell 拆分。 */
    static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
