package com.xrglasses.launcher

import android.content.Context

enum class SortMode { FAVORITES_FIRST, RECENT, ALPHABETICAL }
enum class GridMode { COMPACT, STANDARD, LARGE }

data class LauncherSettings(
    val favoriteComponents: Set<String> = emptySet(),
    val sortMode: SortMode = SortMode.FAVORITES_FIRST,
    val gridMode: GridMode = GridMode.STANDARD,
    val pointerSensitivity: Float = 1.6f,
    val scrollSensitivity: Float = 2.0f,
    val fourFingerScreenshotEnabled: Boolean = true,
    val autoLaunchExternalLauncher: Boolean = true,
    val restoreLastAppAfterReconnect: Boolean = false,
)

class LauncherSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("xr_settings", Context.MODE_PRIVATE)
    private val sessionPrefs = context.getSharedPreferences("xr_session", Context.MODE_PRIVATE)

    fun read(): LauncherSettings = LauncherSettings(
        favoriteComponents = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet(),
        sortMode = runCatching { SortMode.valueOf(prefs.getString("sort", SortMode.FAVORITES_FIRST.name)!!) }
            .getOrDefault(SortMode.FAVORITES_FIRST),
        gridMode = runCatching { GridMode.valueOf(prefs.getString("grid", GridMode.STANDARD.name)!!) }
            .getOrDefault(GridMode.STANDARD),
        pointerSensitivity = prefs.getFloat("pointerSensitivity", 1.6f),
        scrollSensitivity = prefs.getFloat("scrollSensitivity", 2.0f),
        fourFingerScreenshotEnabled = prefs.getBoolean("fourFingerScreenshot", true),
        autoLaunchExternalLauncher = prefs.getBoolean("autoLaunchExternalLauncher", true),
        restoreLastAppAfterReconnect = prefs.getBoolean("restoreLastAppAfterReconnect", false),
    )

    fun setFavorite(component: String, enabled: Boolean) {
        val favorites = read().favoriteComponents.toMutableSet()
        if (enabled) favorites += component else favorites -= component
        prefs.edit().putStringSet("favorites", favorites).apply()
    }

    fun write(settings: LauncherSettings) {
        prefs.edit()
            .putStringSet("favorites", settings.favoriteComponents)
            .putString("sort", settings.sortMode.name)
            .putString("grid", settings.gridMode.name)
            .putFloat("pointerSensitivity", settings.pointerSensitivity)
            .putFloat("scrollSensitivity", settings.scrollSensitivity)
            .putBoolean("fourFingerScreenshot", settings.fourFingerScreenshotEnabled)
            .putBoolean("autoLaunchExternalLauncher", settings.autoLaunchExternalLauncher)
            .putBoolean("restoreLastAppAfterReconnect", settings.restoreLastAppAfterReconnect)
            .apply()
    }

    fun clearRecent() {
        val editor = sessionPrefs.edit()
        sessionPrefs.all.keys.filter(HistoryPreferenceKeys::isRecent).forEach(editor::remove)
        editor.apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
        clearRecent()
    }

}

fun AppCatalog.Entry.componentKey(): String = "$packageName/$className"
