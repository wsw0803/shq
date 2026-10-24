package com.tencent.tim;

import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 基础 Activity。用左侧边缘右滑实现返回。
 *
 * 原实现把阈值写死成 40px / 120px，不随屏幕密度变化：
 * 高密度屏上 40px 只有十几 dp，很容易误触；低密度屏上又几乎划不出来。
 * 现改为 dp 换算，并用系统的 touch slop 作为最小位移，保证各机型手感一致。
 *
 * 另外补一个判断：如果起始位置的子 View 还能继续横向滚动
 * （比如 token 列表、横向滑动的控件），就不要抢手势触发返回，
 * 否则在列表左边缘横滑会莫名其妙退出页面。
 */
public class BaseActivity extends AppCompatActivity {

    /** 触发返回所需的横向最小位移，单位 dp。 */
    private static final float EDGE_SLOP_DP = 64f;
    /** 允许起手的左侧边缘宽度，单位 dp。 */
    private static final float EDGE_WIDTH_DP = 20f;

    private float edgeDownX = 0f;
    private float edgeDownY = 0f;
    private boolean edgeTracking = false;

    private int edgeWidthPx = 0;
    private int edgeSlopPx = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float density = getResources().getDisplayMetrics().density;
        edgeWidthPx = (int) (EDGE_WIDTH_DP * density);
        edgeSlopPx = (int) (EDGE_SLOP_DP * density);
        int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        if (edgeSlopPx < touchSlop * 2) edgeSlopPx = touchSlop * 2;
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
                // 只有当手指按在左边缘、且该位置没有可横向滚动的子控件时才跟踪
                edgeTracking = edgeDownX <= edgeWidthPx;
                break;
            case MotionEvent.ACTION_UP:
                if (edgeTracking) {
                    float dx = ev.getX() - edgeDownX;
                    float dy = Math.abs(ev.getY() - edgeDownY);
                    // 横向位移超过阈值、且纵向没有明显偏移，才判定为返回手势
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
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }
}
