package com.xrglasses.launcher;

public final class GestureSettings {
    public final float pointerSensitivity;
    public final float scrollSensitivity;
    public final boolean fourFingerScreenshotEnabled;

    public GestureSettings(float pointerSensitivity, float scrollSensitivity,
                           boolean fourFingerScreenshotEnabled) {
        this.pointerSensitivity = pointerSensitivity;
        this.scrollSensitivity = scrollSensitivity;
        this.fourFingerScreenshotEnabled = fourFingerScreenshotEnabled;
    }

    public static GestureSettings defaults() {
        return new GestureSettings(1.6f, 2.0f, true);
    }
}
