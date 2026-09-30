package com.xrglasses.launcher;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

public final class TouchpadView extends View {
    private final InputBridge input;
    private final SessionState state;
    private final Runnable openText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float slop;
    private float lastX, lastY, downX, downY, threeDy, scrollX, scrollY;
    private long downAt, lastScrollAt;
    private int maxPointers, currentPointers;
    private boolean moved, dragging, consumed;
    private final Runnable hold = this::onHold;

    private void onHold() {
        if (currentPointers == 1 && maxPointers == 1 && !moved) {
            dragging = input.buttonDown(state.displayId, state.pointerX, state.pointerY);
            consumed = true;
        } else if (currentPointers == 3 && maxPointers == 3 && !moved) {
            consumed = true;
            openText.run();
        }
    }

    public TouchpadView(Context context, InputBridge bridge, SessionState session, Runnable text) {
        super(context);
        input = bridge;
        state = session;
        openText = text;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) { cancel(); return true; }
        if (action == MotionEvent.ACTION_DOWN) {
            cancel();
            maxPointers = currentPointers = 1;
            downAt = SystemClock.uptimeMillis();
            downX = lastX = event.getX();
            downY = lastY = event.getY();
            threeDy = scrollX = scrollY = 0;
            moved = consumed = false;
            handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            handler.removeCallbacks(hold);
            if (maxPointers == 2 && currentPointers == 2 && !consumed) flushScroll();
            if (dragging) { input.buttonUp(); dragging = false; }
            int exclude = action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
            currentPointers = event.getPointerCount() - (exclude >= 0 ? 1 : 0);
            maxPointers = Math.max(maxPointers, currentPointers);
            lastX = centroid(event, true, exclude);
            lastY = centroid(event, false, exclude);
            downX = lastX; downY = lastY;
            scrollX = scrollY = 0;
            if (action == MotionEvent.ACTION_POINTER_DOWN && currentPointers == 3) {
                moved = false;
                handler.postDelayed(hold, 650);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float x = centroid(event, true, -1), y = centroid(event, false, -1);
            float dx = x - lastX, dy = y - lastY;
            if (TouchpadMath.moved(x, y, downX, downY, slop)) {
                moved = true;
                handler.removeCallbacks(hold);
            }
            if (!consumed || dragging) {
                if (maxPointers == 1) {
                    float nx = TouchpadMath.move(state.pointerX, dx, 1.6f, state.displayWidth);
                    float ny = TouchpadMath.move(state.pointerY, dy, 1.6f, state.displayHeight);
                    input.movePointer(state.displayId, state.pointerX, state.pointerY, nx, ny);
                    state.pointerX = nx; state.pointerY = ny;
                } else if (maxPointers == 2 && currentPointers == 2 && moved) {
                    scrollX += dx * 2; scrollY += dy * 2;
                    if (SystemClock.uptimeMillis() - lastScrollAt >= 160) flushScroll();
                } else if (maxPointers == 3 && currentPointers == 3) {
                    threeDy += dy;
                }
            }
            lastX = x; lastY = y;
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            handler.removeCallbacks(hold);
            if (dragging) { input.buttonUp(); dragging = false; }
            else if (!consumed) {
                if (maxPointers == 3) {
                    if (threeDy < -80) input.home(state.displayId);
                    else if (!moved && SystemClock.uptimeMillis() - downAt < 650) {
                        input.back(state.displayId);
                    }
                } else if (maxPointers == 2) flushScroll();
                else if (!moved && SystemClock.uptimeMillis() - downAt < 500) performClick();
            }
            currentPointers = 0;
            return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return input.tap(state.displayId, state.pointerX, state.pointerY);
    }

    private void flushScroll() {
        if (Math.abs(scrollX) + Math.abs(scrollY) < 3) return;
        input.scroll(state.displayId, TouchpadMath.clamp(scrollX, -240, 240),
                TouchpadMath.clamp(scrollY, -240, 240));
        scrollX = scrollY = 0;
        lastScrollAt = SystemClock.uptimeMillis();
    }

    private static float centroid(MotionEvent event, boolean horizontal, int exclude) {
        float sum = 0; int count = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == exclude) continue;
            sum += horizontal ? event.getX(i) : event.getY(i);
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    public void cancel() {
        handler.removeCallbacks(hold);
        if (dragging) input.buttonUp();
        dragging = false;
        currentPointers = 0;
        scrollX = scrollY = 0;
    }

    @Override protected void onDetachedFromWindow() { cancel(); super.onDetachedFromWindow(); }
}
