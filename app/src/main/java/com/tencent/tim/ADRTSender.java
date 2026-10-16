package com.tencent.tim.adrt;

import android.content.Context;
import android.content.Intent;

public class ADRTSender {
    private static Context context;
    private static String debuggerPackageName;

    public static void onContext(Context context2, String str) {
        context = context2;
        debuggerPackageName = str;
    }

    public static void sendLogcatLines(String[] lines) {
        // 日志发送（可选）
    }
}