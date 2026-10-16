package com.tencent.tim.adrt;

import android.content.Context;

public class ADRTLogCatReader implements Runnable {
    private static Context context;

    public static void onContext(Context context2, String str) {
        context = context2.getApplicationContext();
    }

    @Override
    public void run() {
        // 日志监控（可选）
    }
}
