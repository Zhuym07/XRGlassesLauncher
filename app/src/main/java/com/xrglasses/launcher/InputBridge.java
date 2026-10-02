package com.xrglasses.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
    private volatile int generation;
    private final java.util.concurrent.atomic.AtomicBoolean backPending = new java.util.concurrent.atomic.AtomicBoolean();

    public InputBridge(Context context, RootBridge rootBridge, SessionState sessionState) {
        this(context, rootBridge, sessionState, new ExternalTaskLauncher(context, sessionState));
    }

    public InputBridge(Context context, RootBridge rootBridge, SessionState sessionState,
                       ExternalTaskLauncher sharedLauncher) {
        this.context = context.getApplicationContext();
        root = rootBridge;
        state = sessionState;
        launcher = sharedLauncher;
        worker.allowCoreThreadTimeOut(true);
    }

    public boolean start() {
        if (!connected(state.displayId)) return false;
        try {
            boolean ok = TextRelayAccessibilityService.startPointer(state.displayId,
                    state.displayWidth, state.displayHeight, state.pointerX, state.pointerY);
            state.inputBackend = ok ? "accessibility touch + overlay cursor" : "unavailable";
            state.lastInputResult = ok ? "Touchpad connected" : TextRelayAccessibilityService.diagnostics();
            return ok;
        } catch (RuntimeException error) {
            state.inputBackend = "unavailable";
            state.lastInputResult = "输入服务启动异常：" + error.getClass().getSimpleName();
            Log.e("XRInput", "start failed", error);
            return false;
        }
    }

    public void stop() {
        generation++;
        // Queued Back requests abort via generation; do not discard their pending-state cleanup.
        try {
            TextRelayAccessibilityService.stopPointer();
        } catch (RuntimeException error) {
            Log.w("XRInput", "stop failed", error);
        }
        state.inputBackend = "none";
    }

    public void close() {
        stop();
        if (launcher != null) launcher.release();
        worker.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }

    /** Kept for compatibility with the legacy Java activity; Root is now probed on demand. */
    public void setRootBackEnabled(boolean ignored) { }

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
        if (!connected(id)) {
            Log.w("XRInput", "move rejected: display not connected id=" + id
                    + " state=" + state.displayStatus + " current=" + state.displayId);
            return false;
        }
        try {
            boolean ok = TextRelayAccessibilityService.movePointer(id, x, y);
            if (!ok) state.lastInputResult = TextRelayAccessibilityService.diagnostics();
            return ok;
        } catch (RuntimeException error) {
            state.lastInputResult = "指针移动异常：" + error.getClass().getSimpleName();
            Log.w("XRInput", "move failed", error);
            return false;
        }
    }

    public boolean tap(int id, float x, float y) {
        if (!connected(id)) return false;
        try {
            return result(TextRelayAccessibilityService.dispatchTap(id, x, y));
        } catch (RuntimeException error) {
            state.lastInputResult = "点击注入异常：" + error.getClass().getSimpleName();
            Log.w("XRInput", "tap failed", error);
            return false;
        }
    }

    public boolean buttonDown(int id, float x, float y) {
        if (!connected(id)) return false;
        try {
            return result(TextRelayAccessibilityService.beginDrag(id, x, y));
        } catch (RuntimeException error) {
            state.lastInputResult = "拖拽开始异常：" + error.getClass().getSimpleName();
            Log.w("XRInput", "buttonDown failed", error);
            return false;
        }
    }

    public void buttonUp() {
        try {
            TextRelayAccessibilityService.endDrag();
        } catch (RuntimeException error) {
            Log.w("XRInput", "buttonUp failed", error);
        }
    }

    public boolean scroll(int id, float dx, float dy) {
        if (!connected(id)) return false;
        float x = TouchpadMath.clamp(state.pointerX, 1, state.displayWidth - 2);
        float y = TouchpadMath.clamp(state.pointerY, 1, state.displayHeight - 2);
        float endX = TouchpadMath.clamp(x + dx, 1, state.displayWidth - 2);
        float endY = TouchpadMath.clamp(y + dy, 1, state.displayHeight - 2);
        try {
            return result(TextRelayAccessibilityService.dispatchSwipe(id, x, y, endX, endY, 130));
        } catch (RuntimeException error) {
            state.lastInputResult = "滚动注入异常：" + error.getClass().getSimpleName();
            Log.w("XRInput", "scroll failed", error);
            return false;
        }
    }

    public interface BackCallback {
        void onResult(boolean success, String message);
    }

    public boolean back(int id) { return back(id, null); }

    public boolean back(int id, BackCallback callback) {
        if (!connected(id)) return false;
        if (!backPending.compareAndSet(false, true)) {
            if (callback != null) callback.onResult(false, AppLanguage.wrap(context).getString(R.string.root_back_pending));
            return true;
        }
        final int expectedGeneration = generation;
        state.lastInputResult = "External Back requested: display=" + id;
        Log.i("XRInput", state.lastInputResult);
        try {
            worker.execute(() -> {
                if (expectedGeneration != generation || !connected(id)) { backPending.set(false); return; }
                RootBridge.Result rootResult;
                try {
                    rootResult = root.executeExternalBack(id);
                } catch (RuntimeException error) {
                    Log.w("XRInput", "external back failed", error);
                    rootResult = new RootBridge.Result(RootBridge.Status.FAILED, -1, "", "Root 返回异常：" + error.getClass().getSimpleName());
                }
                backPending.set(false);
                Log.i("XRInput", "external back display=" + id + ", result=" + rootResult);
                final RootBridge.Result finalRootResult = rootResult;
                handler.post(() -> {
                    if (expectedGeneration != generation || !connected(id)) return;
                    state.rootAvailable = finalRootResult.ok();
                    if (finalRootResult.ok()) {
                        state.lastInputResult = "External Back: sent by Root";
                        if (callback != null) callback.onResult(true, AppLanguage.wrap(context).getString(R.string.root_back_sent));
                    } else {
                        // Never use AccessibilityService.performGlobalAction(BACK) here:
                        // Android exposes no display argument and ColorOS can route it to
                        // the phone screen. A failed Root attempt is therefore reported
                        // instead of risking a phone Back action.
                        state.lastInputResult = "外屏返回不可用：请检查 Root 授权\n" + finalRootResult;
                        if (callback != null) callback.onResult(false, AppLanguage.wrap(context).getString(
                                finalRootResult.status == RootBridge.Status.TIMEOUT ? R.string.root_back_timeout : R.string.root_back_failed));
                        else android.widget.Toast.makeText(context,
                                AppLanguage.wrap(context).getString(R.string.root_back_failed),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException error) {
            backPending.set(false);
            state.lastInputResult = "Root queue busy";
            return false;
        }
    }

    public boolean home(int id) {
        if (!connected(id)) return false;
        try { return launcher.returnToLauncher(); }
        catch (RuntimeException error) { Log.w("XRInput", "home failed", error); return false; }
    }
    public boolean openRecentApps(int id) {
        if (!connected(id)) return false;
        try { return launcher.launchRecentApps(); }
        catch (RuntimeException error) { Log.w("XRInput", "recent apps failed", error); return false; }
    }

    public boolean sendText(int id, String text) {
        if (!connected(id)) return false;
        try {
            boolean ok = TextRelayAccessibilityService.setFocusedText(id, text);
            state.lastInputResult = ok ? "Text sent to external editable focus"
                    : "No editable external focus; draft retained";
            return ok;
        } catch (RuntimeException error) {
            state.lastInputResult = "文字输入异常：" + error.getClass().getSimpleName();
            Log.w("XRInput", "sendText failed", error);
            return false;
        }
    }
}
