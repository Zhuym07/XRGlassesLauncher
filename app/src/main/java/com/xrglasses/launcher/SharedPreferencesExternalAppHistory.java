package com.xrglasses.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SharedPreferencesExternalAppHistory implements ExternalAppHistory {
    private static final String PREFS = "xr_session";
    private static final String KEY_ACTIVE_PACKAGE = "active_external_package";
    private static final String KEY_ACTIVE_CLASS = "active_external_class";
    private static final String KEY_ACTIVE_LABEL = "active_external_label";
    private static final String KEY_ACTIVE_TIMESTAMP = "active_external_timestamp";
    private static final String PREFIX_COMPONENT = "recent_component:";
    private static final String PREFIX_LABEL = "recent_label:";

    private final Context context;
    private final SharedPreferences prefs;

    public SharedPreferencesExternalAppHistory(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override public ActiveExternalApp getActiveApp() {
        String pkg = prefs.getString(KEY_ACTIVE_PACKAGE, "");
        String cls = prefs.getString(KEY_ACTIVE_CLASS, "");
        if (pkg.isEmpty() || cls.isEmpty()) return null;
        String label = prefs.getString(KEY_ACTIVE_LABEL, resolveLabel(pkg, cls));
        long timestamp = prefs.getLong(KEY_ACTIVE_TIMESTAMP, prefs.getLong("recent:" + pkg, 0L));
        return new ActiveExternalApp(pkg, cls, label, timestamp);
    }

    @Override public List<ActiveExternalApp> getRecentApps(int limit) {
        if (limit <= 0) return Collections.emptyList();
        Map<String, Long> timestamps = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(PREFIX_COMPONENT) || !(entry.getValue() instanceof Long)) continue;
            String component = key.substring(PREFIX_COMPONENT.length());
            timestamps.put(component, (Long) entry.getValue());
            labels.put(component, prefs.getString(PREFIX_LABEL + component, ""));
        }
        // Backward compatibility with the old package-only recent:<package> records.
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : context.getPackageManager().queryIntentActivities(query, 0)) {
            ActivityInfo ai = info.activityInfo;
            if (ai == null) continue;
            long timestamp = prefs.getLong("recent:" + ai.packageName, 0L);
            String component = ai.packageName + "/" + ai.name;
            if (timestamp > timestamps.getOrDefault(component, 0L)) {
                timestamps.put(component, timestamp);
                labels.put(component, info.loadLabel(context.getPackageManager()).toString());
            }
        }
        List<ActiveExternalApp> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : timestamps.entrySet()) {
            String[] parts = entry.getKey().split("/", 2);
            if (parts.length != 2 || !isLaunchable(parts[0], parts[1])) continue;
            String label = labels.get(entry.getKey());
            if (label == null || label.isEmpty()) label = resolveLabel(parts[0], parts[1]);
            result.add(new ActiveExternalApp(parts[0], parts[1], label, entry.getValue()));
        }
        result.sort((a, b) -> Long.compare(b.lastConfirmedAt, a.lastConfirmedAt));
        ActiveExternalApp active = getActiveApp();
        if (active != null) {
            result.removeIf(app -> app.componentKey().equals(active.componentKey()));
            result.add(0, active);
        }
        if (result.size() > limit) return new ArrayList<>(result.subList(0, limit));
        return result;
    }

    @Override public void recordConfirmedLaunch(ActiveExternalApp app) {
        if (app == null || app.packageName.isEmpty() || app.className.isEmpty()) return;
        long now = app.lastConfirmedAt > 0 ? app.lastConfirmedAt : System.currentTimeMillis();
        String component = app.componentKey();
        prefs.edit()
                .putLong("recent:" + app.packageName, now)
                .putLong(PREFIX_COMPONENT + component, now)
                .putString(PREFIX_LABEL + component, app.label)
                .putString(KEY_ACTIVE_PACKAGE, app.packageName)
                .putString(KEY_ACTIVE_CLASS, app.className)
                .putString(KEY_ACTIVE_LABEL, app.label)
                .putLong(KEY_ACTIVE_TIMESTAMP, now)
                .apply();
    }

    @Override public void clearActiveApp() {
        prefs.edit().remove(KEY_ACTIVE_PACKAGE).remove(KEY_ACTIVE_CLASS)
                .remove(KEY_ACTIVE_LABEL).remove(KEY_ACTIVE_TIMESTAMP).apply();
    }

    private boolean isLaunchable(String pkg, String cls) {
        try {
            ActivityInfo info = context.getPackageManager().getActivityInfo(
                    new android.content.ComponentName(pkg, cls), 0);
            return info.enabled && info.exported;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private String resolveLabel(String pkg, String cls) {
        try {
            ActivityInfo info = context.getPackageManager().getActivityInfo(
                    new android.content.ComponentName(pkg, cls), 0);
            CharSequence label = info.loadLabel(context.getPackageManager());
            return label == null ? pkg : label.toString();
        } catch (PackageManager.NameNotFoundException error) {
            return pkg;
        }
    }
}
