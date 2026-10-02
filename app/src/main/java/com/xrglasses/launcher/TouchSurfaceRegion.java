package com.xrglasses.launcher;

/** Geometry is in view-local pixels; controls are limited to the top corner/bottom strip. */
public enum TouchSurfaceRegion {
    EXIT, MULTITASK, EXTERNAL_BACK, TOUCHPAD;

    public static TouchSurfaceRegion at(float x, float y, float width, float height,
                                        float bottomHeight, float exitWidth, float exitHeight) {
        if (width <= 0 || height <= 0 || x < 0 || y < 0 || x >= width || y >= height) {
            return TOUCHPAD;
        }
        if (x < exitWidth && y < exitHeight) return EXIT;
        if (y >= height - bottomHeight) {
            return x < width / 3f ? MULTITASK : EXTERNAL_BACK;
        }
        return TOUCHPAD;
    }
}
