package com.tencent.tim;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        TokenStore.init(this);
        // 把诊断开关读进内存，供各模块判断是否记录
        LoginDiag.init(this);
    }
}
