package com.xrglasses.launcher;

/** One control gesture owns its DOWN region until UP/CANCEL, never leaking into input. */
public final class TouchpadControlGesture {
    private TouchSurfaceRegion region = TouchSurfaceRegion.TOUCHPAD;
    private float startX, startY;
    private long startedAt;
    private boolean cancelled;

    public void begin(TouchSurfaceRegion region, float x, float y, long time) {
        this.region = region;
        startX = x;
        startY = y;
        startedAt = time;
        cancelled = false;
    }

    public void move(float x, float y, int pointers, TouchSurfaceRegion current, float slop) {
        if (pointers != 1 || current != region ||
                TouchpadMath.moved(x, y, startX, startY, slop)) cancelled = true;
    }

    public void cancel() { cancelled = true; }

    public TouchSurfaceRegion finish(float x, float y, long time, float slop) {
        long duration = time - startedAt;
        if (cancelled || duration < 0 || duration >= 500 ||
                TouchpadMath.moved(x, y, startX, startY, slop)) return TouchSurfaceRegion.TOUCHPAD;
        return region;
    }
}
