package com.azazo1.auto_adb_wl_client.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "settings")

class PreferenceManager(private val context: Context) {
    private val MANUAL_ADDRESS = stringPreferencesKey("manual_address")
    private val MANUAL_PORT = stringPreferencesKey("manual_port")

    val manualAddressFlow: Flow<String> = context.dataStore.data.map { it[MANUAL_ADDRESS] ?: "" }
    val manualPortFlow: Flow<String> = context.dataStore.data.map { it[MANUAL_PORT] ?: "21300" }

    suspend fun saveManualInfo(address: String, port: String) {
        context.dataStore.edit { prefs ->
            prefs[MANUAL_ADDRESS] = address
            prefs[MANUAL_PORT] = port
        }
    }
}
