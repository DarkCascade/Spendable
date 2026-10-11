package com.darkcascade.spendable.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val jevEnabled: Boolean = false,
    val jevApiKey: String = "",
    val lastBalance: String = "",
)

/** Small app settings store. The Jev key stays in app-private storage and is excluded from backups. */
class SettingsStore(private val context: Context) {
    private val jevEnabledKey = booleanPreferencesKey("jev_enabled")
    private val jevApiKeyKey = stringPreferencesKey("jev_api_key")
    private val lastBalanceKey = stringPreferencesKey("last_balance")

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(p[jevEnabledKey] ?: false, p[jevApiKeyKey].orEmpty(), p[lastBalanceKey].orEmpty())
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setJev(enabled: Boolean, apiKey: String) {
        context.dataStore.edit { it[jevEnabledKey] = enabled; it[jevApiKeyKey] = apiKey.trim() }
    }

    suspend fun setLastBalance(balance: String) {
        context.dataStore.edit { it[lastBalanceKey] = balance }
    }
}
