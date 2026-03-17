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
import kotlinx.coroutines.runBlocking

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object ThemeHelper {
    private val THEME_KEY = intPreferencesKey("theme_mode")

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

    fun getSavedTheme(context: Context): Int {
        return runBlocking {
            context.settingsDataStore.data.map { prefs ->
                prefs[THEME_KEY] ?: MODE_SYSTEM
            }.first()
        }
    }

    suspend fun saveTheme(context: Context, mode: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[THEME_KEY] = mode
        }
    }
}
