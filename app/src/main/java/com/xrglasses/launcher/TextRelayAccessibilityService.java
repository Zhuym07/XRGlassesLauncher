package com.xrglasses.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TextRelayAccessibilityService extends AccessibilityService {
    private static TextRelayAccessibilityService instance;
    private static String status = "service unavailable";
    private static final Set<Runnable> connectionListeners = new LinkedHashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<GestureDescription> queue = new ArrayDeque<>();
    private WindowManager cursorManager;
    private View cursor;
    private WindowManager.LayoutParams cursorParams;
    private int targetDisplay = -1;
    private int dragDisplay = -1;
    private int width, height;
    private float pointerX, pointerY, strokeX, strokeY;
    private boolean busy, dragging, releaseDrag;
    private GestureDescription.StrokeDescription dragStroke;
    private String lastEvent = "none";

    @Override protected void onServiceConnected() {
        instance = this;
        report("service connected");
        notifyConnectionChanged();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null) {
            lastEvent = event.getPackageName() + "/" + event.getClassName();
        }
    }

    @Override public void onInterrupt() { stopSession(); }

    @Override public boolean onUnbind(Intent intent) {
        stopSession();
        markDisconnected();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        stopSession();
        handler.removeCallbacksAndMessages(null);
        markDisconnected();
        super.onDestroy();
    }

    private void markDisconnected() {
        if (instance != this) return;
        instance = null;
        report("service disconnected");
        notifyConnectionChanged();
    }

    // Service and Activity lifecycle callbacks run on the main thread.
    public static void addConnectionListener(Runnable listener) {
        connectionListeners.add(listener);
    }

    public static void removeConnectionListener(Runnable listener) {
        connectionListeners.remove(listener);
    }

    private static void notifyConnectionChanged() {
        for (Runnable listener : connectionListeners.toArray(new Runnable[0])) listener.run();
    }

    public static boolean isEnabled() { return instance != null; }

    public static String diagnostics() {
        return "service=" + isEnabled() + ", " + status
                + (instance == null ? "" : ", target=" + instance.targetDisplay
                + ", queued=" + instance.queue.size() + ", event=" + instance.lastEvent);
    }

    public static boolean startPointer(int displayId, int width, int height, float x, float y) {
        if (instance == null || Build.VERSION.SDK_INT < 30 || displayId <= 0) return false;
        return instance.attachCursor(displayId, width, height, x, y);
    }

    public static void stopPointer() { if (instance != null) instance.stopSession(); }

    public static boolean movePointer(int displayId, float x, float y) {
        if (instance == null || instance.targetDisplay != displayId || instance.cursor == null) {
            return false;
        }
        return instance.positionCursor(x, y);
    }

    private boolean attachCursor(int displayId, int w, int h, float x, float y) {
        if (targetDisplay == displayId && cursor != null) return positionCursor(x, y);
        stopSession();
        if (busy || dragging) {
            report("previous gesture finishing; retry touchpad");
            return false;
        }
        Display display = getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null || !display.isValid()) return false;
        try {
            // AccessibilityService supplies the per-display overlay token to this context.
            Context overlayContext = createDisplayContext(display);
            cursorManager = overlayContext.getSystemService(WindowManager.class);
            cursor = new CursorView(overlayContext);
            int size = Math.round(28 * overlayContext.getResources().getDisplayMetrics().density);
            cursorParams = new WindowManager.LayoutParams(size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            cursorParams.gravity = Gravity.TOP | Gravity.LEFT;
            cursorParams.setTitle("XR accessibility pointer");
            width = w;
            height = h;
            targetDisplay = displayId;
            cursorParams.x = Math.round(x);
            cursorParams.y = Math.round(y);
            cursorManager.addView(cursor, cursorParams);
            report("cursor attached display=" + displayId);
            return positionCursor(x, y);
        } catch (RuntimeException error) {
            report("overlay failed: " + error);
            removeCursor();
            return false;
        }
    }

    private boolean positionCursor(float x, float y) {
        pointerX = TouchpadMath.clamp(x, 0, width - 1);
        pointerY = TouchpadMath.clamp(y, 0, height - 1);
        cursorParams.x = Math.round(pointerX);
        cursorParams.y = Math.round(pointerY);
        try {
            cursorManager.updateViewLayout(cursor, cursorParams);
            return true;
        } catch (RuntimeException error) {
            report("cursor update failed: " + error);
            stopSession();
            return false;
        }
    }

    private void removeCursor() {
        if (cursor != null) {
            try { cursorManager.removeViewImmediate(cursor); }
            catch (RuntimeException error) { Log.w("XRInput", "remove cursor", error); }
        }
        cursor = null;
        cursorManager = null;
        targetDisplay = -1;
    }

    private void stopSession() {
        queue.clear();
        if (dragging) {
            releaseDrag = true;
            if (!busy) continueDrag();
        }
        removeCursor();
    }

    public static boolean dispatchTap(int displayId, float x, float y) {
        return dispatchSwipe(displayId, x, y, x, y, 40);
    }

    public static boolean dispatchSwipe(int displayId, float x, float y,
                                        float toX, float toY, long duration) {
        if (Build.VERSION.SDK_INT < 30) return false;
        if (!ready(displayId) || instance.dragging) return false;
        Path path = path(x, y, toX, toY);
        GestureDescription gesture = new GestureDescription.Builder().setDisplayId(displayId)
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        if (instance.queue.size() >= 12) {
            report("gesture queue full");
            return false;
        }
        instance.queue.add(gesture);
        instance.pump();
        return true;
    }

    public static boolean beginDrag(int displayId, float x, float y) {
        if (Build.VERSION.SDK_INT < 30) return false;
        if (!ready(displayId) || instance.busy || !instance.queue.isEmpty()) return false;
        instance.dragging = true;
        instance.dragDisplay = displayId;
        instance.releaseDrag = false;
        instance.strokeX = x;
        instance.strokeY = y;
        instance.dragStroke = new GestureDescription.StrokeDescription(path(x, y, x, y),
                0, 80, true);
        instance.submit(new GestureDescription.Builder().setDisplayId(displayId)
                .addStroke(instance.dragStroke).build(), true, false);
        return instance.dragging;
    }

    public static void endDrag() {
        if (instance != null && instance.dragging) {
            instance.releaseDrag = true;
            if (!instance.busy) instance.continueDrag();
        }
    }

    private void continueDrag() {
        if (Build.VERSION.SDK_INT < 30) return;
        if (!dragging || busy) return;
        boolean ending = releaseDrag;
        int displayId = dragDisplay;
        Display display = getSystemService(DisplayManager.class).getDisplay(displayId);
        // A removed overlay must not prevent releasing a held stroke on a connected display.
        if (display == null || !display.isValid()) {
            dragging = false;
            dragStroke = null;
            dragDisplay = -1;
            report("drag ended after display disconnect");
            return;
        }
        dragStroke = dragStroke.continueStroke(path(strokeX, strokeY, pointerX, pointerY),
                0, ending ? 1 : 80, !ending);
        strokeX = pointerX;
        strokeY = pointerY;
        submit(new GestureDescription.Builder().setDisplayId(displayId)
                .addStroke(dragStroke).build(), true, ending);
    }

    private void pump() {
        if (!busy && !dragging && !queue.isEmpty()) submit(queue.removeFirst(), false, false);
    }

    private void submit(GestureDescription gesture, boolean drag, boolean ending) {
        if (Build.VERSION.SDK_INT < 30) return;
        final int displayId = gesture.getDisplayId();
        busy = true;
        report("dispatch display=" + displayId);
        try {
            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription result) {
                    busy = false;
                    if (drag && ending) {
                        dragging = false; dragStroke = null; dragDisplay = -1;
                    }
                    report("completed display=" + displayId);
                    if (dragging) continueDrag(); else pump();
                }
                @Override public void onCancelled(GestureDescription result) {
                    failed("cancelled display=" + displayId);
                }
            }, handler);
            if (!accepted) failed("rejected display=" + displayId);
        } catch (RuntimeException error) { failed("dispatch failed: " + error); }
    }

    private void failed(String message) {
        busy = false;
        dragging = false;
        dragStroke = null;
        dragDisplay = -1;
        queue.clear();
        report(message);
    }

    private static Path path(float x, float y, float toX, float toY) {
        Path path = new Path();
        path.moveTo(x, y);
        if (x != toX || y != toY) path.lineTo(toX, toY);
        return path;
    }

    private static boolean ready(int displayId) {
        if (instance == null || Build.VERSION.SDK_INT < 30 || displayId <= 0
                || instance.targetDisplay != displayId || instance.cursor == null) {
            report("no active accessibility touchpad for display=" + displayId);
            return false;
        }
        Display d = instance.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (d == null || !d.isValid()) { instance.stopSession(); return false; }
        return true;
    }

    private static void report(String message) {
        status = message;
        Log.i("XRInput", message);
    }

    private static List<AccessibilityWindowInfo> windows(int displayId) {
        if (instance == null || Build.VERSION.SDK_INT < 30 || displayId <= 0) {
            return Collections.emptyList();
        }
        List<AccessibilityWindowInfo> result = instance.getWindowsOnAllDisplays().get(displayId);
        return result == null ? Collections.emptyList() : result;
    }

    public static String lastObservedWindowSummary() {
        if (instance == null || Build.VERSION.SDK_INT < 30) return "service unavailable";
        try {
            StringBuilder result = new StringBuilder();
            SparseArray<List<AccessibilityWindowInfo>> all = instance.getWindowsOnAllDisplays();
            for (int i = 0; i < all.size(); i++) {
                for (AccessibilityWindowInfo window : all.valueAt(i)) {
                    AccessibilityNodeInfo root = window.getRoot();
                    result.append("display=").append(all.keyAt(i))
                            .append(",type=").append(window.getType())
                            .append(",focused=").append(window.isFocused())
                            .append(",pkg=").append(root == null ? "?" : root.getPackageName())
                            .append('\n');
                    if (root != null) root.recycle();
                }
            }
            return result.length() == 0 ? "no accessible windows" : result.toString();
        } catch (RuntimeException error) { return "window query failed: " + error; }
    }

    public static int observedPackageDisplay(String packageName, int preferredDisplay) {
        if (instance == null || Build.VERSION.SDK_INT < 30) return -1;
        SparseArray<List<AccessibilityWindowInfo>> all = instance.getWindowsOnAllDisplays();
        int observed = -1;
        for (int i = 0; i < all.size(); i++) {
            for (AccessibilityWindowInfo window : all.valueAt(i)) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) {
                    boolean matches = packageName.contentEquals(root.getPackageName() == null
                            ? "" : root.getPackageName());
                    root.recycle();
                    if (matches) {
                        if (all.keyAt(i) == preferredDisplay) return preferredDisplay;
                        observed = all.keyAt(i);
                    }
                }
            }
        }
        return observed;
    }

    public static boolean setFocusedText(int displayId, String text) {
        try {
            for (AccessibilityWindowInfo window : windows(displayId)) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                boolean success = false;
                if (focused != null && focused.isEditable()) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text);
                    success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                }
                if (focused != null) focused.recycle();
                root.recycle();
                if (success) { report("text set display=" + displayId); return true; }
            }
        } catch (RuntimeException error) { report("text failed: " + error); return false; }
        report("no editable external focus; text retained on phone");
        return false;
    }

    private static final class CursorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        CursorView(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            float scale = getWidth() / 28f;
            canvas.save();
            canvas.scale(scale, scale);
            arrow.reset();
            arrow.moveTo(1, 1); arrow.lineTo(1, 22); arrow.lineTo(7, 17);
            arrow.lineTo(12, 26); arrow.lineTo(16, 24); arrow.lineTo(11, 15);
            arrow.lineTo(21, 15); arrow.close();
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
            canvas.drawPath(arrow, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f);
            paint.setColor(Color.BLACK); canvas.drawPath(arrow, paint);
            canvas.restore();
        }
    }
}
