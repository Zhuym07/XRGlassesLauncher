package com.xrglasses.launcher;

public interface ExternalScreenshotter {
    void capture(int displayId, Callback callback);

    interface Callback {
        void onResult(ScreenshotResult result);
    }
}
