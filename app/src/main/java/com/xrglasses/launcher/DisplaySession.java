package com.xrglasses.launcher;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

public final class DisplaySession implements DisplayManager.DisplayListener {
    private final DisplayManager manager;
    private final SessionState state;
    private Runnable listener;

    public DisplaySession(Context context, SessionState sessionState) {
        manager = context.getSystemService(DisplayManager.class);
        state = sessionState;
        manager.registerDisplayListener(this, new Handler(Looper.getMainLooper()));
    }

    public void setListener(Runnable callback) { listener = callback; }

    public Display discoverExternalDisplay() {
        Display best = null;
        for (Display d : manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY || !d.isValid()
                    || d.getState() == Display.STATE_OFF) continue;
            if (d.getDisplayId() == state.displayId) { best = d; break; }
            if (best == null || d.getMode().getPhysicalWidth() * d.getMode().getPhysicalHeight()
                    > best.getMode().getPhysicalWidth() * best.getMode().getPhysicalHeight()) best = d;
        }
        int previous = state.displayId;
        if (best == null) {
            state.displayStatus = SessionState.DisplayStatus.DISCONNECTED;
            state.displayId = Display.INVALID_DISPLAY;
            state.uniqueId = "";
            state.displayWidth = state.displayHeight = 1;
            state.pointerX = state.pointerY = 0;
        } else {
            Point size = new Point();
            best.getRealSize(size);
            state.displayStatus = SessionState.DisplayStatus.CONNECTED;
            state.displayId = best.getDisplayId();
            state.displayWidth = Math.max(1, size.x);
            state.displayHeight = Math.max(1, size.y);
            if (previous != state.displayId) {
                state.pointerX = size.x / 2f;
                state.pointerY = size.y / 2f;
            } else {
                state.pointerX = TouchpadMath.clamp(state.pointerX, 0, size.x - 1);
                state.pointerY = TouchpadMath.clamp(state.pointerY, 0, size.y - 1);
            }
            // Physical uniqueId is hidden API. This is only a public descriptive fingerprint.
            state.uniqueId = best.getName() + "|" + size.x + "x" + size.y;
        }
        if (listener != null) listener.run();
        return best;
    }

    public Display getCurrentDisplay() {
        return state.displayId > 0 ? manager.getDisplay(state.displayId) : null;
    }
    public void release() { listener = null; manager.unregisterDisplayListener(this); }
    @Override public void onDisplayAdded(int id) { discoverExternalDisplay(); }
    @Override public void onDisplayRemoved(int id) { discoverExternalDisplay(); }
    @Override public void onDisplayChanged(int id) { discoverExternalDisplay(); }
}
