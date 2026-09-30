package com.xrglasses.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AppCatalog {
    public static final class Entry {
        public final String packageName, className;
        public final CharSequence label;
        public final Drawable icon;
        public final long lastUsed;
        Entry(String p, String c, CharSequence l, Drawable icon, long lastUsed) {
            packageName = p; className = c; label = l; this.icon = icon; this.lastUsed = lastUsed;
        }
    }
    public List<Entry> load(Context context) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> results = context.getPackageManager().queryIntentActivities(query, 0);
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        SharedPreferences prefs = context.getSharedPreferences("xr_session", Context.MODE_PRIVATE);
        for (ResolveInfo info : results) {
            if (info.activityInfo == null || !info.activityInfo.enabled || !info.activityInfo.exported
                    || info.activityInfo.packageName.equals(context.getPackageName())) continue;
            String key = info.activityInfo.packageName + "/" + info.activityInfo.name;
            if (!seen.add(key)) continue;
            entries.add(new Entry(info.activityInfo.packageName, info.activityInfo.name,
                    info.loadLabel(context.getPackageManager()), info.loadIcon(context.getPackageManager()),
                    prefs.getLong("recent:" + info.activityInfo.packageName, 0)));
        }
        entries.sort((a, b) -> compare(a.lastUsed, a.label.toString(), b.lastUsed, b.label.toString()));
        return entries;
    }
    static int compare(long aTime, String aLabel, long bTime, String bLabel) {
        int recent = Long.compare(bTime, aTime);
        return recent != 0 ? recent : aLabel.toLowerCase(Locale.ROOT)
                .compareTo(bLabel.toLowerCase(Locale.ROOT));
    }
}
