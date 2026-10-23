package com.example.lianxu.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    companion object {
        val DEEPSEEK_API_KEY = stringPreferencesKey("deepseek_api_key")
    }

    val apiKeyFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[DEEPSEEK_API_KEY]
    }

    suspend fun saveApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[DEEPSEEK_API_KEY] = apiKey
        }
    }
}
