package com.zailinn.c001apk.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Device-local storage. The first snapshot is read synchronously at startup so
 * request signing and theming never observe a half-initialised state.
 */
class LocalStore(private val dataStore: DataStore<Preferences>, private val scope: CoroutineScope) {
    val prefs: StateFlow<Preferences> = dataStore.data.stateIn(
        scope, SharingStarted.Eagerly, runBlocking { dataStore.data.first() },
    )

    suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    fun <T> state(read: (Preferences) -> T): StateFlow<T> =
        prefs.map(read).stateIn(scope, SharingStarted.Eagerly, read(prefs.value))

    fun <T> readJson(prefs: Preferences, key: String, serializer: KSerializer<T>, default: T): T =
        prefs[stringPreferencesKey(key)]?.let { runCatching { storeJson.decodeFromString(serializer, it) }.getOrNull() }
            ?: default

    suspend fun <T> updateJson(key: String, serializer: KSerializer<T>, default: T, transform: (T) -> T) {
        edit { prefs ->
            val current = readJson(prefs, key, serializer, default)
            prefs[stringPreferencesKey(key)] = storeJson.encodeToString(serializer, transform(current))
        }
    }
}
