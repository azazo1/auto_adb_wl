package com.azazo1.auto_adb_wl_client.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

val Context.dataStore by preferencesDataStore(name = "settings")

class PreferenceManager(private val context: Context) {
    private val MANUAL_ADDRESS = stringPreferencesKey("manual_address")
    private val MANUAL_PORT = stringPreferencesKey("manual_port")
    private val SERVER_ADDRESS_HISTORY = stringPreferencesKey("server_address_history")
    private val json = Json { ignoreUnknownKeys = true }

    val manualAddressFlow: Flow<String> = context.dataStore.data.map { it[MANUAL_ADDRESS] ?: "" }
    val manualPortFlow: Flow<String> = context.dataStore.data.map { it[MANUAL_PORT] ?: "21300" }
    val serverAddressHistoryFlow: Flow<List<ServerAddressHistory>> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            decodeHistory(preferences[SERVER_ADDRESS_HISTORY])
        }

    suspend fun saveManualInfo(address: String, port: String) {
        context.dataStore.edit { prefs ->
            prefs[MANUAL_ADDRESS] = address
            prefs[MANUAL_PORT] = port
        }
    }

    suspend fun addServerAddressHistory(history: ServerAddressHistory) {
        val normalizedHistory = history.normalize()
        if (normalizedHistory == null) {
            return
        }

        context.dataStore.edit { prefs ->
            val current = decodeHistory(prefs[SERVER_ADDRESS_HISTORY])
            val updated = listOf(normalizedHistory) + current.filterNot { it.matches(normalizedHistory) }
            prefs[SERVER_ADDRESS_HISTORY] = encodeHistory(updated)
        }
    }

    suspend fun removeServerAddressHistory(history: ServerAddressHistory) {
        val normalizedHistory = history.normalize() ?: return

        context.dataStore.edit { prefs ->
            val current = decodeHistory(prefs[SERVER_ADDRESS_HISTORY])
            val updated = current.filterNot { it.matches(normalizedHistory) }
            prefs[SERVER_ADDRESS_HISTORY] = encodeHistory(updated)
        }
    }

    private fun decodeHistory(rawValue: String?): List<ServerAddressHistory> {
        if (rawValue.isNullOrBlank()) {
            return emptyList()
        }

        return runCatching {
            json.decodeFromString(ListSerializer(ServerAddressHistory.serializer()), rawValue)
                .mapNotNull { it.normalize() }
                .distinctBy { it.address.lowercase() to it.port }
        }.getOrDefault(emptyList())
    }

    private fun encodeHistory(history: List<ServerAddressHistory>): String {
        return json.encodeToString(ListSerializer(ServerAddressHistory.serializer()), history)
    }

    private fun ServerAddressHistory.normalize(): ServerAddressHistory? {
        val normalizedAddress = address.trim()
        val normalizedPort = port.trim()
        if (normalizedAddress.isEmpty() || normalizedPort.isEmpty()) {
            return null
        }
        return copy(address = normalizedAddress, port = normalizedPort)
    }

    private fun ServerAddressHistory.matches(other: ServerAddressHistory): Boolean {
        return address.equals(other.address, ignoreCase = true) && port == other.port
    }
}
