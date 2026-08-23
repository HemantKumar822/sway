package com.sway.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sway.core.model.Quality
import com.sway.core.model.RepeatMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * DataStore-preferences [SettingsRepository] — story 5.1 (FR-15, AR-7 conventions, AD-10)
 * + story 7.2 (FR-11 mode persistence: `playback.shuffle` / `playback.repeat`).
 *
 * Namespaced keys inside the one shared settings file; the repeat enum is stored as its
 * [RepeatMode.name] string and parsed strictly — unknown/missing/corrupt values read back
 * as the documented defaults (AUTO / false / OFF); an IOException while reading degrades
 * to defaults (C-8 lesson) while any other failure propagates — it is a bug, not a data
 * state. Writes serialize through [DataStore.edit], so rapid changes persist
 * last-write-wins (story 7.2 AC).
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val audioQuality: Flow<Quality> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences ->
            parseQuality(preferences[KEY_AUDIO_QUALITY]) ?: DEFAULT_QUALITY
        }

    override suspend fun setAudioQuality(quality: Quality) {
        dataStore.edit { preferences ->
            preferences[KEY_AUDIO_QUALITY] = quality.name
        }
    }

    override val shuffleEnabled: Flow<Boolean> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> readShuffle(preferences) }

    override suspend fun setShuffleEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_SHUFFLE] = enabled
        }
    }

    override val repeatMode: Flow<RepeatMode> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> parseRepeat(preferences[KEY_REPEAT]) ?: DEFAULT_REPEAT }

    override suspend fun setRepeatMode(mode: RepeatMode) {
        dataStore.edit { preferences ->
            preferences[KEY_REPEAT] = mode.name
        }
    }

    companion object {
        /** Namespaced settings keys live in the one shared file (`sway_settings`). */
        const val FILE_NAME = "sway_settings"

        val KEY_AUDIO_QUALITY: Preferences.Key<String> = stringPreferencesKey("playback.audio_quality")

        /** Story 7.2 (FR-11): shuffle flag + strict-named repeat mode. */
        val KEY_SHUFFLE: Preferences.Key<Boolean> = booleanPreferencesKey("playback.shuffle")
        val KEY_REPEAT: Preferences.Key<String> = stringPreferencesKey("playback.repeat")

        val DEFAULT_QUALITY: Quality = Quality.AUTO
        const val DEFAULT_SHUFFLE: Boolean = false
        val DEFAULT_REPEAT: RepeatMode = RepeatMode.OFF

        /**
         * Strict enum-name parse; anything but an exact name is corrupt by definition.
         * A wrong-typed stored value (garbage bytes under a typed key) also degrades
         * to the default instead of throwing at collectors.
         */
        private fun parseQuality(name: String?): Quality? =
            try {
                name?.let { candidate -> Quality.entries.firstOrNull { it.name == candidate } }
            } catch (_: Exception) {
                null
            }

        private fun parseRepeat(name: String?): RepeatMode? =
            try {
                name?.let { candidate -> RepeatMode.entries.firstOrNull { it.name == candidate } }
            } catch (_: Exception) {
                null
            }

        private fun readShuffle(preferences: Preferences): Boolean =
            try {
                preferences[KEY_SHUFFLE] ?: DEFAULT_SHUFFLE
            } catch (_: Exception) {
                DEFAULT_SHUFFLE
            }
    }
}
