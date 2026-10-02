package com.xrglasses.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

public final class TouchpadView extends View {
    private final InputBridge input;
    private final SessionState state;
    private final Runnable openText;
    private final ExternalScreenshotter screenshotter;
    private final GestureSettings gestureSettings;
    private TouchpadRegionListener regionListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint feedbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float slop;
    private float lastX, lastY, downX, downY, scrollX, scrollY;
    private long downAt, lastScrollAt;
    private int maxPointers, currentPointers;
    private boolean moved, dragging, consumed;
    private boolean detached;
    private final TouchpadControlGesture controlGesture = new TouchpadControlGesture();
    private final FourFingerTapGesture screenshotGesture = new FourFingerTapGesture();
    private final float bottomHeight, exitWidth, exitHeight;
    private TouchSurfaceRegion activeRegion = TouchSurfaceRegion.TOUCHPAD;
    private String feedbackText = "";
    private final Runnable hold = this::onHold;
    private final Runnable clearFeedback = () -> {
        feedbackText = "";
        invalidate();
    };

    private void onHold() {
        if (detached || activeRegion != TouchSurfaceRegion.TOUCHPAD) return;
        if (currentPointers == 1 && maxPointers == 1 && !moved) {
            dragging = safeButtonDown();
            consumed = true;
        }
    }

    public TouchpadView(Context context, InputBridge bridge, SessionState session, Runnable text) {
        this(context, bridge, session, text, GestureSettings.defaults(), null, null);
    }

    public TouchpadView(Context context, InputBridge bridge, SessionState session, Runnable text,
                        GestureSettings settings, ExternalScreenshotter screenshot) {
        this(context, bridge, session, text, settings, screenshot, null);
    }

    public TouchpadView(Context context, InputBridge bridge, SessionState session, Runnable text,
                        GestureSettings settings, ExternalScreenshotter screenshot,
                        TouchpadRegionListener listener) {
        super(context);
        input = bridge;
        state = session;
        openText = text;
        gestureSettings = settings == null ? GestureSettings.defaults() : settings;
        screenshotter = screenshot;
        regionListener = listener;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        float density = getResources().getDisplayMetrics().density;
        bottomHeight = 96f * density;
        exitWidth = 128f * density;
        exitHeight = 80f * density;
        feedbackPaint.setColor(Color.rgb(130, 130, 130));
        feedbackPaint.setTextAlign(Paint.Align.CENTER);
        feedbackPaint.setTextSize(14f * getResources().getDisplayMetrics().scaledDensity);
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setWillNotDraw(false);
    }

    public void setRegionListener(TouchpadRegionListener listener) {
        regionListener = listener;
    }

    public void showFeedback(String text) {
        feedbackText = text == null ? "" : text;
        handler.removeCallbacks(clearFeedback);
        if (!feedbackText.isEmpty()) handler.postDelayed(clearFeedback, 500L);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!feedbackText.isEmpty()) {
            float x = activeRegion == TouchSurfaceRegion.MULTITASK
                    ? getWidth() / 6f : getWidth() * 0.66f;
            canvas.drawText(feedbackText, x, Math.max(24f,
                    getHeight() - feedbackPaint.getTextSize()), feedbackPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        long time = event.getEventTime();
        if (action == MotionEvent.ACTION_CANCEL) {
            cancel();
            return true;
        }
        if (detached) return true;
        if (action == MotionEvent.ACTION_DOWN) {
            cancel();
            requestFocus();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            maxPointers = currentPointers = 1;
            downAt = time;
            downX = lastX = event.getX();
            downY = lastY = event.getY();
            scrollX = scrollY = 0;
            moved = consumed = false;
            activeRegion = regionAt(downX, downY);
            controlGesture.begin(activeRegion, downX, downY, time);
            screenshotGesture.begin(gestureSettings.fourFingerScreenshotEnabled, time, slop);
            screenshotGesture.pointerDown(event.getPointerId(0), downX, downY, time);
            if (activeRegion == TouchSurfaceRegion.TOUCHPAD) {
                handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
            }
            return true;
        }
        // No control event (including multi-touch) is ever passed to InputBridge.
        if (activeRegion != TouchSurfaceRegion.TOUCHPAD) {
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                controlGesture.cancel();
            } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                controlGesture.move(event.getX(), event.getY(), event.getPointerCount(),
                        regionAt(event.getX(), event.getY()), slop);
                if (action == MotionEvent.ACTION_UP) {
                    TouchSurfaceRegion result = controlGesture.finish(event.getX(), event.getY(),
                            time, slop);
                    currentPointers = 0;
                    dispatchControl(result);
                }
            }
            return true;
        }
        for (int i = 0; i < event.getPointerCount(); i++) {
            screenshotGesture.move(event.getPointerId(i), event.getX(i), event.getY(i));
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            currentPointers = event.getPointerCount() - (action == MotionEvent.ACTION_POINTER_UP ? 1 : 0);
            handler.removeCallbacks(hold);
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                maxPointers = Math.max(maxPointers, currentPointers);
                int i = event.getActionIndex();
                screenshotGesture.pointerDown(event.getPointerId(i), event.getX(i), event.getY(i), time);
                if (regionAt(event.getX(i), event.getY(i)) != TouchSurfaceRegion.TOUCHPAD) {
                    screenshotGesture.cancel();
                    consumed = true;
                }
            }
            if (dragging) { safeButtonUp(); dragging = false; consumed = true; }
            if (maxPointers >= 3) consumed = true;
            // Rebase after a finger enters/leaves: otherwise the centroid jumps
            // and produces unintended scrolling or screenshot cancellation.
            lastX = centroid(event, true, action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1);
            lastY = centroid(event, false, action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1);
            if (maxPointers == 2 && currentPointers == 2) { downX = lastX; downY = lastY; }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float x = centroid(event, true, -1), y = centroid(event, false, -1);
            float dx = x - lastX, dy = y - lastY;
            if (maxPointers >= 3) { lastX = x; lastY = y; return true; }
            if (TouchpadMath.moved(x, y, downX, downY, slop)) {
                moved = true;
                handler.removeCallbacks(hold);
            }
            if (!consumed || dragging) {
                if (maxPointers == 1) {
                    float nx = TouchpadMath.move(state.pointerX, dx, gestureSettings.pointerSensitivity,
                            state.displayWidth);
                    float ny = TouchpadMath.move(state.pointerY, dy, gestureSettings.pointerSensitivity,
                            state.displayHeight);
                    safeMovePointer(state.pointerX, state.pointerY, nx, ny);
                    state.pointerX = nx; state.pointerY = ny;
                } else if (maxPointers == 2 && currentPointers == 2 && moved) {
                    scrollX += dx * gestureSettings.scrollSensitivity;
                    scrollY += dy * gestureSettings.scrollSensitivity;
                    if (time - lastScrollAt >= 160) flushScroll();
                }
            }
            lastX = x; lastY = y;
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            handler.removeCallbacks(hold);
            if (dragging) { safeButtonUp(); dragging = false; }
            if (maxPointers == 4 && screenshotGesture.canCapture(time)) {
                if (screenshotter != null && state.displayStatus == SessionState.DisplayStatus.CONNECTED) {
                    try { screenshotter.capture(state.displayId, result -> { }); }
                    catch (RuntimeException error) { Log.w("XRTouchpad", "screenshot request failed", error); }
                }
            } else if (!consumed) {
                if (maxPointers == 2) flushScroll();
                else if (maxPointers == 1 && !moved && time - downAt < 500) performClick();
            }
            currentPointers = 0;
            return true;
        }
        return true;
    }

    private void dispatchControl(TouchSurfaceRegion region) {
        if (regionListener == null) return;
        try {
            switch (region) {
                case EXIT: regionListener.onExitClick(); break;
                case MULTITASK: regionListener.onMultitaskClick(); break;
                case EXTERNAL_BACK: regionListener.onExternalBackClick(); break;
                default: break;
            }
        } catch (RuntimeException error) {
            Log.w("XRTouchpad", "control callback failed", error);
            showFeedback(getContext().getString(R.string.gesture_failed));
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return activeRegion == TouchSurfaceRegion.TOUCHPAD && safeTap();
    }

    private TouchSurfaceRegion regionAt(float x, float y) {
        return TouchSurfaceRegion.at(x, y, getWidth(), getHeight(), bottomHeight, exitWidth, exitHeight);
    }

    private void flushScroll() {
        if (Math.abs(scrollX) + Math.abs(scrollY) < 3) return;
        safeScroll(TouchpadMath.clamp(scrollX, -240, 240),
                TouchpadMath.clamp(scrollY, -240, 240));
        scrollX = scrollY = 0;
        lastScrollAt = SystemClock.uptimeMillis();
    }

    private static float centroid(MotionEvent event, boolean horizontal, int excludedIndex) {
        float sum = 0; int count = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == excludedIndex) continue;
            sum += horizontal ? event.getX(i) : event.getY(i);
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    public void cancel() {
        handler.removeCallbacks(hold);
        if (dragging) safeButtonUp();
        dragging = false;
        currentPointers = 0;
        screenshotGesture.cancel();
        controlGesture.cancel();
        activeRegion = TouchSurfaceRegion.TOUCHPAD;
        scrollX = scrollY = 0;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        detached = false;
        requestFocus();
        Log.i("XRTouchpad", "attached view=" + getWidth() + "x" + getHeight());
    }

    @Override protected void onDetachedFromWindow() {
        detached = true;
        handler.removeCallbacks(clearFeedback);
        cancel();
        super.onDetachedFromWindow();
    }

    private boolean safeTap() {
        if (detached) return false;
        try { return input.tap(state.displayId, state.pointerX, state.pointerY); }
        catch (RuntimeException error) { Log.w("XRTouchpad", "tap failed", error); return false; }
    }

    private boolean safeButtonDown() {
        if (detached) return false;
        try { return input.buttonDown(state.displayId, state.pointerX, state.pointerY); }
        catch (RuntimeException error) { Log.w("XRTouchpad", "button down failed", error); return false; }
    }

    private void safeButtonUp() {
        try { input.buttonUp(); }
        catch (RuntimeException error) { Log.w("XRTouchpad", "button up failed", error); }
    }

    private void safeMovePointer(float fromX, float fromY, float x, float y) {
        if (detached) return;
        try { input.movePointer(state.displayId, fromX, fromY, x, y); }
        catch (RuntimeException error) { Log.w("XRTouchpad", "move failed", error); }
    }

    private void safeScroll(float dx, float dy) {
        if (detached) return;
        try { input.scroll(state.displayId, dx, dy); }
        catch (RuntimeException error) { Log.w("XRTouchpad", "scroll failed", error); }
    }
}
