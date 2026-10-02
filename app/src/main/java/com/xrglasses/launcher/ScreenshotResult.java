package com.xrglasses.launcher;

import android.net.Uri;

public abstract class ScreenshotResult {
    private ScreenshotResult() {}

    public static final class Saved extends ScreenshotResult {
        public final Uri uri;
        public Saved(Uri uri) { this.uri = uri; }
    }

    public static final class Failed extends ScreenshotResult {
        public final String reason;
        public Failed(String reason) { this.reason = reason; }
    }
}
