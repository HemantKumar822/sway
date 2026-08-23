package com.sway.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sway.core.model.Quality
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * DataStore-preferences [SettingsRepository] — story 5.1 (FR-15, AR-7 conventions, AD-10).
 *
 * One namespaced key ([KEY_AUDIO_QUALITY]) inside the single shared settings file; the
 * enum is stored as its [Quality.name] string. Unknown or missing values read back as
 * AUTO; an IOException while reading degrades to defaults (C-8 lesson) while any other
 * failure propagates — it is a bug, not a data state.
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

    companion object {
        /** Namespaced settings keys live in the one shared file (`sway_settings`). */
        const val FILE_NAME = "sway_settings"

        val KEY_AUDIO_QUALITY: Preferences.Key<String> = stringPreferencesKey("playback.audio_quality")

        val DEFAULT_QUALITY: Quality = Quality.AUTO

        /** Strict enum-name parse; anything but an exact name is corrupt by definition. */
        private fun parseQuality(name: String?): Quality? =
            name?.let { candidate -> Quality.entries.firstOrNull { it.name == candidate } }
    }
}
