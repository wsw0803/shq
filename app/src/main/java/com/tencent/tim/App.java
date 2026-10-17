package com.tencent.tim;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        TokenStore.init(this);
    }
}
