package com.xrglasses.launcher;

public final class TouchpadMath {
    private TouchpadMath() { }
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }
    public static float move(float coordinate, float delta, float gain, int size) {
        return clamp(coordinate + delta * gain, 0, size - 1);
    }
    public static boolean moved(float x, float y, float downX, float downY, float slop) {
        return Math.hypot(x - downX, y - downY) > slop;
    }
}
