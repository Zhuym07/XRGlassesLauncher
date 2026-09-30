package com.xrglasses.launcher;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Display;

public final class ExternalTaskLauncher {
    private final Context context;
    private final SessionState state;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable listener;
    private int request;

    public ExternalTaskLauncher(Context context, SessionState state) {
        this.context = context;
        this.state = state;
    }

    public void setListener(Runnable callback) { listener = callback; }
    public void release() { request++; handler.removeCallbacksAndMessages(null); listener = null; }

    public boolean launchLauncher() {
        Intent intent = new Intent(context, ExternalLauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return launch(intent, false);
    }

    public boolean launchPackage(String packageName, String className) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(packageName, className))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return launch(intent, true);
    }

    public boolean returnToLauncher() { return launchLauncher(); }

    public String queryTaskPlacement() {
        return "Requested display=" + state.displayId + "\n"
                + TextRelayAccessibilityService.lastObservedWindowSummary();
    }

    private boolean launch(Intent intent, boolean thirdParty) {
        final int displayId = state.displayId;
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (displayId <= 0 || display == null || !display.isValid()) {
            report("External display disconnected");
            return false;
        }
        ComponentName component = intent.getComponent();
        ActivityManager am = context.getSystemService(ActivityManager.class);
        boolean allowed;
        try {
            allowed = am == null || am.isActivityStartAllowedOnDisplay(context, displayId, intent);
        } catch (RuntimeException error) {
            report("Display permission query failed: " + error);
            return false;
        }
        Log.i("XRLaunch", "request component=" + component + " display=" + displayId
                + " allowed=" + allowed);
        if (!allowed) {
            report("System denied external launch: " + component.flattenToShortString()
                    + " display=" + displayId);
            return false;
        }
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        try {
            context.startActivity(intent, options.toBundle());
        } catch (RuntimeException primary) {
            if (!thirdParty) { report("Launcher launch failed: " + primary); return false; }
            try {
                LauncherApps apps = context.getSystemService(LauncherApps.class);
                if (apps == null) throw primary;
                apps.startMainActivity(component, Process.myUserHandle(), null, options.toBundle());
            } catch (RuntimeException fallback) {
                report("App launch failed: " + primary + "\nLauncherApps: " + fallback);
                return false;
            }
        }
        String packageName = component.getPackageName();
        final int currentRequest = ++request;
        report("Launch requested: " + packageName + " -> display " + displayId
                + "; placement not yet verified");
        preferences().edit().putLong("recent:" + packageName, System.currentTimeMillis()).apply();
        handler.postDelayed(() -> verify(currentRequest, displayId, packageName), 1800);
        return true;
    }

    private void verify(int expectedRequest, int displayId, String packageName) {
        if (request != expectedRequest) return;
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null || !display.isValid()) { report("Display disconnected during launch"); return; }
        try {
            int observed = TextRelayAccessibilityService.observedPackageDisplay(packageName, displayId);
            state.lastObservedPackage = packageName;
            state.lastObservedDisplayId = observed;
            if (observed == displayId) {
                report("External app window observed: " + packageName + " display=" + displayId);
            } else if (observed >= 0) {
                report("App window is on display " + observed + ", not external display " + displayId
                        + ". System may have reused or redirected the phone task: " + packageName);
            } else {
                report("Launch requested but no accessible app window observed: " + packageName
                        + ". Enable accessibility or inspect task placement in diagnostics.");
            }
        } catch (RuntimeException error) { report("Placement observation failed: " + error); }
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences("xr_session", Context.MODE_PRIVATE);
    }

    private void report(String message) {
        state.lastError = message;
        preferences().edit().putString("launch_status", message).apply();
        Log.i("XRLaunch", message);
        if (listener != null) listener.run();
    }
}
