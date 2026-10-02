package com.xrglasses.launcher;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.Rect;
import android.content.pm.LauncherApps;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Display;

import java.util.List;

public final class ExternalTaskLauncher {
    private static final String TAG = "XRLaunch";
    private static final String SESSION_PREFS = "xr_session";
    private static final String SETTINGS_PREFS = "xr_settings";
    private static final String KEY_ACTIVE_PACKAGE = "active_external_package";
    private static final String KEY_ACTIVE_CLASS = "active_external_class";
    private static final String KEY_ACTIVE_DISPLAY_ID = "active_external_display_id";
    private static final String KEY_ACTIVE_DISPLAY_UNIQUE = "active_external_display_unique";
    private static final String KEY_PENDING_RESTORE = "pending_external_restore";

    private final Context context;
    private final SessionState state;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferencesExternalAppHistory history;
    private Runnable listener;
    private int request;
    private int boundDisplayId = -1;
    private final ExternalRuntimeState runtime = new ExternalRuntimeState();

    public ExternalTaskLauncher(Context context, SessionState state) {
        this.context = context.getApplicationContext();
        this.state = state;
        this.history = new SharedPreferencesExternalAppHistory(this.context);
    }

    public void setListener(Runnable callback) { listener = callback; }
    public void release() { request++; handler.removeCallbacksAndMessages(null); listener = null; }
    public ActiveExternalApp getActiveApp() { return history.getActiveApp(); }
    public List<ActiveExternalApp> getRecentApps(int limit) { return history.getRecentApps(limit); }

    public void refreshLiveState() {
        if (state.displayStatus != SessionState.DisplayStatus.CONNECTED || state.displayId <= 0) {
            runtime.disconnect(); boundDisplayId = -1;
            clearLiveEvidence();
            return;
        }
        if (boundDisplayId != state.displayId) {
            runtime.connect(state.displayId); boundDisplayId = state.displayId;
        }
        TextRelayAccessibilityService.ExternalWindowSnapshot snapshot =
                TextRelayAccessibilityService.externalWindowSnapshot(state.displayId, context.getPackageName());
        runtime.observe(snapshot.presence);
        state.externalPresence = snapshot.presence;
        state.runningExternalPackage = snapshot.packageName;
        state.runningExternalLabel = resolvePackageLabel(snapshot.packageName);
        if (!state.touchpadMode) state.desktopStatus = snapshot.presence == ExternalRuntimeState.Presence.APPLICATION
                ? SessionState.DesktopStatus.RUNNING_EXTERNAL_APP : SessionState.DesktopStatus.DISPLAY_READY;
    }

    public String getRunningPackage() { return state.runningExternalPackage; }
    public String getRunningLabel() { return state.runningExternalLabel; }

    public boolean shouldAutoOpenLauncher() {
        refreshLiveState();
        // History offers a resume action; it is never evidence of a surviving external task.
        return runtime.shouldOpenDesktop();
    }

    private void clearLiveEvidence() {
        state.externalPresence = ExternalRuntimeState.Presence.UNKNOWN;
        state.runningExternalPackage = state.runningExternalLabel = "";
        state.launcherVisible = false;
        state.lastObservedDisplayId = -1;
        state.lastObservedPackage = state.lastObservedClass = "";
    }

    private String resolvePackageLabel(String packageName) {
        if (packageName.isEmpty()) return "";
        try { return context.getPackageManager().getApplicationInfo(packageName, 0)
                .loadLabel(context.getPackageManager()).toString(); }
        catch (android.content.pm.PackageManager.NameNotFoundException error) { return packageName; }
    }

    public boolean launchLauncher() { return launchLauncher(false); }

    public boolean launchLauncher(boolean focusRecents) {
        Intent intent = new Intent(context, ComposeExternalLauncherActivity.class)
                .putExtra("focus_recents", focusRecents)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        boolean launched = launch(intent, false);
        if (launched) {
            state.launcherVisible = true;
            if (!state.touchpadMode) state.desktopStatus = SessionState.DesktopStatus.DISPLAY_READY;
        }
        return launched;
    }

    public boolean launchExternalQuickMenu(String mode) {
        Intent intent = new Intent(context, ExternalQuickMenuActivity.class)
                .putExtra(ExternalQuickMenuActivity.EXTRA_MODE, mode == null ? "recent" : mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return launch(intent, false);
    }

    public boolean launchPackage(String packageName, String className) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(packageName, className))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        state.launcherVisible = false;
        return launch(intent, true);
    }

    public boolean resumeActiveApp() {
        ActiveExternalApp active = getActiveApp();
        if (active == null) {
            report(AppLanguage.wrap(context).getString(R.string.no_active_app));
            return false;
        }
        if (!isLaunchable(active)) {
            history.clearActiveApp();
            report(AppLanguage.wrap(context).getString(R.string.active_unavailable, active.label));
            return false;
        }
        return launchPackage(active.packageName, active.className);
    }

    public boolean launchRecentApps() { return launchLauncher(true); }
    public boolean returnToLauncher() { return launchLauncher(false); }

    public void onDisplayDisconnected() {
        request++;
        handler.removeCallbacksAndMessages(null);
        runtime.disconnect(); boundDisplayId = -1;
        clearLiveEvidence();
        state.desktopStatus = SessionState.DesktopStatus.WAITING_FOR_RECONNECT;
        ActiveExternalApp active = getActiveApp();
        state.lastDisconnectApp = active == null ? "" : active.label;
        state.lastDisconnectAt = java.text.DateFormat.getDateTimeInstance().format(new java.util.Date());
        SharedPreferences prefs = preferences();
        if (active == null) {
            Log.w(TAG, "external display disconnected; no tracked third-party app to restore");
            return;
        }
        prefs.edit().putBoolean(KEY_PENDING_RESTORE, true).apply();
        report("外屏已断开，系统可能已销毁外屏任务；已记录待恢复应用：" + active.label);
    }

    public void onDisplayReconnected(int displayId, String uniqueId) {
        request++;
        handler.removeCallbacksAndMessages(null);
        runtime.connect(displayId); boundDisplayId = displayId;
        clearLiveEvidence();
        SharedPreferences prefs = preferences();
        if (!prefs.getBoolean(KEY_PENDING_RESTORE, false)) return;
        ActiveExternalApp active = getActiveApp();
        prefs.edit().putBoolean(KEY_PENDING_RESTORE, false).apply();
        if (active == null) return;

        boolean restore = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getBoolean("restoreLastAppAfterReconnect", false);
        String oldDisplay = prefs.getString(KEY_ACTIVE_DISPLAY_UNIQUE, "");
        Log.i(TAG, "external display reconnected newId=" + displayId + " unique=" + uniqueId
                + " oldUnique=" + oldDisplay + " restore=" + restore);
        if (!restore) {
            report("外屏已重连，未自动恢复上次应用（可在设置中开启）");
            return;
        }

        final int reconnectRequest = request;
        final long restoreToken = runtime.beginRequest();
        handler.postDelayed(() -> {
            if (request != reconnectRequest || !runtime.isCurrent(restoreToken, displayId)
                    || state.displayId != displayId
                    || state.displayStatus != SessionState.DisplayStatus.CONNECTED) return;
            runtime.finishRequest(restoreToken);
            refreshLiveState();
            if (runtime.presence() == ExternalRuntimeState.Presence.APPLICATION) {
                report(AppLanguage.wrap(context).getString(R.string.restore_skipped_live));
                return;
            }
            boolean restored = launchPackage(active.packageName, active.className);
            if (!restored) {
                report(AppLanguage.wrap(context).getString(R.string.restore_failed, active.label));
            }
        }, 500L);
    }

    public String queryTaskPlacement() {
        SharedPreferences prefs = preferences();
        String active = prefs.getString(KEY_ACTIVE_PACKAGE, "");
        String pending = Boolean.toString(prefs.getBoolean(KEY_PENDING_RESTORE, false));
        return "Requested display=" + state.displayId + " live=" + state.externalPresence
                + " pendingLaunch=" + runtime.isPending() + "\n"
                + "Tracked external app=" + (active.isEmpty() ? "none" : active + "/"
                + prefs.getString(KEY_ACTIVE_CLASS, "")) + " pendingRestore=" + pending + "\n"
                + TextRelayAccessibilityService.lastObservedWindowSummary();
    }

    private boolean launch(Intent intent, boolean thirdParty) {
        final int displayId = state.displayId;
        request++; // Manual requests supersede reconnect restoration and previous verification.
        runtime.cancelRequest();
        if (boundDisplayId != displayId) { runtime.connect(displayId); boundDisplayId = displayId; }
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (displayId <= 0 || display == null || !display.isValid()) {
            report("外屏已断开");
            return false;
        }
        ComponentName component = intent.getComponent();
        ActivityManager am = context.getSystemService(ActivityManager.class);
        boolean allowed;
        try {
            allowed = am == null || am.isActivityStartAllowedOnDisplay(context, displayId, intent);
        } catch (RuntimeException error) {
            report(AppLanguage.wrap(context).getString(R.string.launch_permission_failed, error));
            return false;
        }
        Log.i(TAG, "request component=" + component + " display=" + displayId
                + " allowed=" + allowed + " unique=" + display.getName());
        if (!allowed) {
            report(AppLanguage.wrap(context).getString(R.string.launch_denied, component == null ? intent.getComponent() : component.flattenToShortString()));
            return false;
        }
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        Point size = new Point();
        display.getRealSize(size);
        if (size.x > 0 && size.y > 0) options.setLaunchBounds(new Rect(0, 0, size.x, size.y));
        Context launchContext = context.createDisplayContext(display);
        Log.i(TAG, "external metrics display=" + displayId + " size=" + size.x + "x" + size.y
                + " density=" + launchContext.getResources().getDisplayMetrics().density);
        try {
            launchContext.startActivity(intent, options.toBundle());
        } catch (RuntimeException primary) {
            if (!thirdParty) { report(AppLanguage.wrap(context).getString(R.string.launcher_failed, primary)); return false; }
            try {
                LauncherApps apps = context.getSystemService(LauncherApps.class);
                if (apps == null) throw primary;
                apps.startMainActivity(component, Process.myUserHandle(), null, options.toBundle());
            } catch (RuntimeException fallback) {
                report(AppLanguage.wrap(context).getString(R.string.launch_request_failed, primary + "\nLauncherApps: " + fallback));
                return false;
            }
        }
        final String packageName = thirdParty && component != null ? component.getPackageName() : "";
        final String className = thirdParty && component != null ? component.getClassName() : "";
        final int currentRequest = request;
        final long launchToken = runtime.beginRequest();
        report("已请求启动：" + packageName + " → display " + displayId);
        handler.postDelayed(() -> verify(currentRequest, launchToken, displayId, packageName, className), 1800);
        return true;
    }

    private void verify(int expectedRequest, long token, int displayId, String packageName, String className) {
        if (request != expectedRequest || !runtime.isCurrent(token, displayId)
                || state.displayId != displayId
                || state.displayStatus != SessionState.DisplayStatus.CONNECTED) return;
        runtime.finishRequest(token);
        refreshLiveState();
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null || !display.isValid()) { report("应用启动期间外屏断开"); return; }
        try {
            int observed = packageName.isEmpty() ? displayId : TextRelayAccessibilityService.observedPackageDisplay(packageName, displayId);
            state.lastObservedPackage = packageName;
            state.lastObservedClass = className;
            state.lastObservedDisplayId = observed;
            if (observed == displayId) {
                if (!packageName.isEmpty()) {
                    long now = System.currentTimeMillis();
                    ActiveExternalApp active = new ActiveExternalApp(packageName, className,
                            resolveLabel(packageName, className), now);
                    history.recordConfirmedLaunch(active);
                    preferences().edit()
                            .putInt(KEY_ACTIVE_DISPLAY_ID, displayId)
                            .putString(KEY_ACTIVE_DISPLAY_UNIQUE, state.uniqueId)
                            .putBoolean(KEY_PENDING_RESTORE, false)
                            .apply();
                    if (!state.touchpadMode) state.desktopStatus = SessionState.DesktopStatus.RUNNING_EXTERNAL_APP;
                }
                report("已确认应用位于外屏：" + packageName + " / display=" + displayId);
            } else if (observed >= 0) {
                report("应用被系统重定向到 display " + observed + "，目标外屏为 " + displayId);
            } else {
                report("已请求启动，但暂未观察到外屏应用窗口：" + packageName);
            }
        } catch (RuntimeException error) { report("外屏任务验证失败：" + error); }
    }

    private boolean isLaunchable(ActiveExternalApp app) {
        try {
            android.content.pm.ActivityInfo info = context.getPackageManager().getActivityInfo(
                    new ComponentName(app.packageName, app.className), 0);
            return info.enabled && info.exported;
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private String resolveLabel(String packageName, String className) {
        try {
            android.content.pm.ActivityInfo info = context.getPackageManager().getActivityInfo(
                    new ComponentName(packageName, className), 0);
            CharSequence label = info.loadLabel(context.getPackageManager());
            return label == null ? packageName : label.toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            return packageName;
        }
    }

    private SharedPreferences preferences() { return context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE); }

    private void report(String message) {
        state.lastError = message;
        preferences().edit().putString("launch_status", message).apply();
        Log.i(TAG, message);
        if (listener != null) listener.run();
    }
}
