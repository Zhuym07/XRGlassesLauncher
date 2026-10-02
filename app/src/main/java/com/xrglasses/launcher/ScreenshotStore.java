package com.xrglasses.launcher;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ScreenshotStore {
    private ScreenshotStore() {}

    public static ScreenshotResult save(Context context, HardwareBuffer buffer, ColorSpace colorSpace) {
        if (Build.VERSION.SDK_INT < 30 || buffer == null) {
            return new ScreenshotResult.Failed(context.getString(R.string.capture_buffer));
        }
        Bitmap wrapped = null;
        Bitmap bitmap = null;
        Uri uri = null;
        try {
            wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            if (wrapped == null) return new ScreenshotResult.Failed(context.getString(R.string.capture_decode));
            bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false);
            if (bitmap == null) return new ScreenshotResult.Failed(context.getString(R.string.capture_copy));
            String name = "XRDesktop_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                    + ".png";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/XRDesktop");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            ContentResolver resolver = context.getContentResolver();
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return new ScreenshotResult.Failed(context.getString(R.string.capture_insert));
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IllegalStateException(context.getString(R.string.capture_write));
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return new ScreenshotResult.Saved(uri);
        } catch (Exception error) {
            if (uri != null) {
                try { context.getContentResolver().delete(uri, null, null); }
                catch (RuntimeException ignored) { }
            }
            return new ScreenshotResult.Failed(error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
        } finally {
            if (bitmap != null && bitmap != wrapped) bitmap.recycle();
            if (wrapped != null) wrapped.recycle();
            buffer.close();
        }
    }
}
