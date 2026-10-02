package com.xrglasses.launcher;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

public final class DisplaySession implements DisplayManager.DisplayListener {
    private static final String TAG = "XRDisplay";
    private final DisplayManager manager;
    private final SessionState state;
    private Runnable listener;

    /** True only for the most recent discover call that observed a real disconnect. */
    public boolean lastChangeWasDisconnect;
    /** True only for the most recent discover call that observed a reconnect. */
    public boolean lastChangeWasReconnect;
    public int previousDisplayId = Display.INVALID_DISPLAY;
    public String previousUniqueId = "";
    public SessionState.DisplayStatus previousDisplayStatus = SessionState.DisplayStatus.DISCONNECTED;

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

        previousDisplayId = state.displayId;
        previousUniqueId = state.uniqueId;
        previousDisplayStatus = state.displayStatus;
        lastChangeWasDisconnect = false;
        lastChangeWasReconnect = false;

        if (best == null) {
            state.displayStatus = SessionState.DisplayStatus.DISCONNECTED;
            state.desktopStatus = SessionState.DesktopStatus.WAITING_FOR_RECONNECT;
            state.displayId = Display.INVALID_DISPLAY;
            state.uniqueId = "";
            state.displayWidth = state.displayHeight = 1;
            state.pointerX = state.pointerY = 0;
            lastChangeWasDisconnect = previousDisplayStatus == SessionState.DisplayStatus.CONNECTED;
            if (lastChangeWasDisconnect) {
                Log.w(TAG, "onDisplayRemoved/absent previousId=" + previousDisplayId
                        + " unique=" + previousUniqueId);
            }
        } else {
            Point size = new Point();
            best.getRealSize(size);
            state.displayStatus = SessionState.DisplayStatus.CONNECTED;
            if (state.desktopStatus == SessionState.DesktopStatus.DISPLAY_DISCONNECTED || state.desktopStatus == SessionState.DesktopStatus.WAITING_FOR_RECONNECT) {
                state.desktopStatus = SessionState.DesktopStatus.DISPLAY_READY;
            }
            state.displayId = best.getDisplayId();
            state.displayWidth = Math.max(1, size.x);
            state.displayHeight = Math.max(1, size.y);
            if (previousDisplayStatus != SessionState.DisplayStatus.CONNECTED
                    || previousDisplayId != state.displayId) {
                state.pointerX = size.x / 2f;
                state.pointerY = size.y / 2f;
            } else {
                state.pointerX = TouchpadMath.clamp(state.pointerX, 0, size.x - 1);
                state.pointerY = TouchpadMath.clamp(state.pointerY, 0, size.y - 1);
            }
            // Physical uniqueId is hidden API. This is only a public descriptive fingerprint.
            state.uniqueId = best.getName() + "|" + size.x + "x" + size.y;
            // A rapid unplug/replug may skip the absent snapshot but still replace the ID.
            lastChangeWasReconnect = previousDisplayStatus != SessionState.DisplayStatus.CONNECTED
                    || previousDisplayId != state.displayId;
            lastChangeWasDisconnect = previousDisplayStatus == SessionState.DisplayStatus.CONNECTED
                    && previousDisplayId != state.displayId;
            if (lastChangeWasReconnect) {
                Log.i(TAG, "onDisplayAdded currentId=" + state.displayId + " unique=" + state.uniqueId
                        + " previousId=" + previousDisplayId);
            } else if (previousDisplayId != state.displayId || !previousUniqueId.equals(state.uniqueId)) {
                Log.i(TAG, "onDisplayChanged previousId=" + previousDisplayId + " currentId="
                        + state.displayId + " previousUnique=" + previousUniqueId + " currentUnique="
                        + state.uniqueId);
            }
        }
        if (listener != null) listener.run();
        return best;
    }

    public Display getCurrentDisplay() {
        return state.displayId > 0 ? manager.getDisplay(state.displayId) : null;
    }
    public void release() { listener = null; manager.unregisterDisplayListener(this); }
    @Override public void onDisplayAdded(int id) {
        Log.i(TAG, "onDisplayAdded callback id=" + id);
        discoverExternalDisplay();
    }
    @Override public void onDisplayRemoved(int id) {
        Log.w(TAG, "onDisplayRemoved callback id=" + id);
        discoverExternalDisplay();
    }
    @Override public void onDisplayChanged(int id) { discoverExternalDisplay(); }
}
