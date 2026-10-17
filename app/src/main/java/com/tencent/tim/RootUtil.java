package com.tencent.tim;
import java.io.File;
public class RootUtil {
    public static boolean append(File f, String line) {
        try {
            String dir = f.getParentFile().getAbsolutePath();
            String path = f.getAbsolutePath();
            long before = f.exists() ? f.length() : 0;
            String script = "mkdir -p " + dir + "\ncat >> " + path + " <<SHQEOF\n" + line + "\nSHQEOF\nchmod 666 " + path + "\n";
            Process p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.write(script.getBytes("UTF-8"));
            os.flush();
            os.close();
            p.waitFor();
            return f.exists() && f.length() > before;
        } catch (Exception e) {
            return false;
        }
    }
}
