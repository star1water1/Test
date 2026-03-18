package com.novelcharacter.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object ThemeHelper {
    private val THEME_KEY = intPreferencesKey("theme_mode")
    private const val PREFS_NAME = "theme_cache"
    private const val PREFS_THEME_KEY = "cached_theme_mode"

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    fun applyTheme(mode: Int) {
        when (mode) {
            MODE_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            MODE_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    /**
     * Returns cached theme synchronously from SharedPreferences (safe for main thread).
     * The cache is updated whenever saveTheme() is called.
     */
    fun getSavedTheme(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(PREFS_THEME_KEY, MODE_SYSTEM)
    }

    suspend fun saveTheme(context: Context, mode: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[THEME_KEY] = mode
        }
        // Update synchronous cache for getSavedTheme()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREFS_THEME_KEY, mode)
            .apply()
    }

    /**
     * Migrate DataStore value to SharedPreferences cache on first launch.
     * Call this once during Application.onCreate() in a coroutine.
     */
    suspend fun migrateCacheIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(PREFS_THEME_KEY)) {
            val stored = context.settingsDataStore.data.map { it[THEME_KEY] ?: MODE_SYSTEM }.first()
            prefs.edit().putInt(PREFS_THEME_KEY, stored).apply()
        }
    }
}
