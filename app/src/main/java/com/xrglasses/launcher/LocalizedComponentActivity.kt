package com.xrglasses.launcher

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity

open class LocalizedComponentActivity : ComponentActivity() {
    private val localeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "language" && !isFinishing && !isDestroyed) runOnUiThread { recreate() }
    }
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 33) AppLanguage.preferences(this).registerOnSharedPreferenceChangeListener(localeListener)
    }
    override fun onDestroy() {
        if (Build.VERSION.SDK_INT < 33) AppLanguage.preferences(this).unregisterOnSharedPreferenceChangeListener(localeListener)
        super.onDestroy()
    }
}
