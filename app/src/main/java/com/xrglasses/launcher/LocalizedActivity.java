package com.xrglasses.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

public class LocalizedActivity extends Activity {
    private final SharedPreferences.OnSharedPreferenceChangeListener localeListener = (prefs, key) -> {
        if ("language".equals(key) && !isFinishing() && !isDestroyed()) runOnUiThread(this::recreate);
    };
    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppLanguage.wrap(base)); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT < 33) AppLanguage.preferences(this).registerOnSharedPreferenceChangeListener(localeListener);
    }
    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT < 33) AppLanguage.preferences(this).unregisterOnSharedPreferenceChangeListener(localeListener);
        super.onDestroy();
    }
}
