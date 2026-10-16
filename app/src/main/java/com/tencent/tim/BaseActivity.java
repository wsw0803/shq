package com.tencent.tim;

import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;
import androidx.appcompat.app.AppCompatActivity;

public class BaseActivity extends AppCompatActivity {

    private float edgeDownX = 0f;
    private float edgeDownY = 0f;
    private boolean edgeTracking = false;
    private final int edgeWidthPx = 40;
    private final int edgeSlopPx = 120;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Ui.wrap(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.apply(this);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                edgeDownX = ev.getX();
                edgeDownY = ev.getY();
                edgeTracking = edgeDownX <= edgeWidthPx;
                break;
            case MotionEvent.ACTION_UP:
                if (edgeTracking) {
                    float dx = ev.getX() - edgeDownX;
                    float dy = Math.abs(ev.getY() - edgeDownY);
                    if (dx > edgeSlopPx && dy < edgeSlopPx) {
                        edgeTracking = false;
                        onBackPressed();
                        return true;
                    }
                }
                edgeTracking = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                edgeTracking = false;
                break;
        }
        return super.dispatchTouchEvent(ev);
    }
}
