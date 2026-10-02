package com.xrglasses.launcher;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

/** Uses public per-app languages on Android 13+, scoped contexts on Android 10–12. */
public final class AppLanguage {
    private AppLanguage() {}
    public static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("xr_locale", Context.MODE_PRIVATE);
    }
    public static String selected(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            return manager == null ? "" : LanguageChoice.normalize(manager.getApplicationLocales().toLanguageTags().split(",")[0]);
        }
        return LanguageChoice.normalize(preferences(context).getString("language", ""));
    }
    public static void apply(Context context, String language) {
        String tag = LanguageChoice.normalize(language);
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager != null) manager.setApplicationLocales(LocaleList.forLanguageTags(tag));
        } else {
            // Separate from launcher preferences: resetting controls keeps language.
            preferences(context).edit().putString("language", tag).apply();
        }
    }
    public static Context wrap(Context context) {
        if (Build.VERSION.SDK_INT >= 33) return context;
        String tag = selected(context);
        if (tag.isEmpty()) return context;
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocales(LocaleList.forLanguageTags(tag));
        return context.createConfigurationContext(configuration);
    }
}
