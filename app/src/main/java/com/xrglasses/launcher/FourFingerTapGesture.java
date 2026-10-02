package com.xrglasses.launcher;

import java.util.HashMap;
import java.util.Map;

/** Tracks each finger independently; pointer removal must never reset cancellation. */
public final class FourFingerTapGesture {
    private final Map<Integer, float[]> origins = new HashMap<>();
    private boolean cancelled;
    private long beganAt;
    private float slop;

    public void begin(boolean enabled, long time, float slop) {
        origins.clear();
        cancelled = !enabled;
        beganAt = time;
        this.slop = slop;
    }

    public void pointerDown(int id, float x, float y, long time) {
        origins.put(id, new float[]{x, y});
        if (origins.size() > 4 || time - beganAt > 180) cancelled = true;
    }

    public void move(int id, float x, float y) {
        float[] start = origins.get(id);
        if (start != null && TouchpadMath.moved(x, y, start[0], start[1], slop)) cancelled = true;
    }

    public void cancel() { cancelled = true; }

    public boolean canCapture(long time) {
        return !cancelled && origins.size() == 4 && time >= beganAt && time - beganAt < 500;
    }
}
