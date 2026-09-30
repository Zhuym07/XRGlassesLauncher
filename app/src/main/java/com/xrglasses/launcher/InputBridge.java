package com.xrglasses.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class InputBridge {
    private final Context context;
    private final RootBridge root;
    private final SessionState state;
    private final ExternalTaskLauncher launcher;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2));
    private boolean rootBackEnabled;
    private volatile int generation;

    public InputBridge(Context context, RootBridge rootBridge, SessionState sessionState) {
        this.context = context.getApplicationContext();
        launcher = new ExternalTaskLauncher(this.context, sessionState);
        root = rootBridge;
        state = sessionState;
        worker.allowCoreThreadTimeOut(true);
    }

    public boolean start() {
        if (!connected(state.displayId)) return false;
        boolean ok = TextRelayAccessibilityService.startPointer(state.displayId,
                state.displayWidth, state.displayHeight, state.pointerX, state.pointerY);
        state.inputBackend = ok ? "accessibility touch + overlay cursor" : "unavailable";
        state.lastInputResult = ok ? "Touchpad connected" : TextRelayAccessibilityService.diagnostics();
        return ok;
    }

    public void stop() {
        generation++;
        worker.getQueue().clear();
        TextRelayAccessibilityService.stopPointer();
        state.inputBackend = "none";
    }

    public void close() { stop(); launcher.release(); worker.shutdownNow();
        handler.removeCallbacksAndMessages(null); }
    public void setRootBackEnabled(boolean enabled) { rootBackEnabled = enabled; }

    private boolean connected(int id) {
        return state.displayStatus == SessionState.DisplayStatus.CONNECTED
                && id > 0 && state.displayId == id;
    }

    private boolean result(boolean ok) {
        state.lastInputResult = ok ? "Gesture queued; see completion in diagnostics"
                : TextRelayAccessibilityService.diagnostics();
        return ok;
    }

    public boolean movePointer(int id, float fromX, float fromY, float x, float y) {
        return connected(id) && TextRelayAccessibilityService.movePointer(id, x, y);
    }

    public boolean tap(int id, float x, float y) {
        return connected(id) && result(TextRelayAccessibilityService.dispatchTap(id, x, y));
    }

    public boolean buttonDown(int id, float x, float y) {
        return connected(id) && result(TextRelayAccessibilityService.beginDrag(id, x, y));
    }

    public void buttonUp() { TextRelayAccessibilityService.endDrag(); }

    public boolean scroll(int id, float dx, float dy) {
        if (!connected(id)) return false;
        float x = TouchpadMath.clamp(state.pointerX, 1, state.displayWidth - 2);
        float y = TouchpadMath.clamp(state.pointerY, 1, state.displayHeight - 2);
        float endX = TouchpadMath.clamp(x + dx, 1, state.displayWidth - 2);
        float endY = TouchpadMath.clamp(y + dy, 1, state.displayHeight - 2);
        return result(TextRelayAccessibilityService.dispatchSwipe(id, x, y, endX, endY, 130));
    }

    public boolean back(int id) {
        if (!connected(id)) return false;
        // GLOBAL_ACTION_BACK has no display argument and may navigate the phone instead.
        if (!rootBackEnabled) {
            state.lastInputResult = "External Back needs optional Root; global phone Back is disabled";
            android.widget.Toast.makeText(context, "External Back requires optional Root",
                    android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
        final int expectedGeneration = generation;
        try {
            worker.execute(() -> {
                if (expectedGeneration != generation) return;
                RootBridge.Result r = root.executeInputCommand("keyboard -d " + id
                        + " keyevent " + KeyEvent.KEYCODE_BACK);
                handler.post(() -> {
                    if (expectedGeneration == generation) {
                        state.lastInputResult = "External Back: " + r;
                        state.rootAvailable = r.ok();
                        if (!r.ok()) android.widget.Toast.makeText(context, r.toString(),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException error) {
            state.lastInputResult = "Root queue busy";
            return false;
        }
    }

    public boolean home(int id) {
        return connected(id) && launcher.returnToLauncher();
    }

    public boolean sendText(int id, String text) {
        boolean ok = connected(id) && TextRelayAccessibilityService.setFocusedText(id, text);
        state.lastInputResult = ok ? "Text sent to external editable focus"
                : "No editable external focus; draft retained";
        return ok;
    }
}
