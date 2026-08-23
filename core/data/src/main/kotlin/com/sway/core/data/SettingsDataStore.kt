package com.sway.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Production [DataStore] for the single shared settings file (AR-7 conventions:
 * one preferences file, namespaced keys). The returned instance owns its worker scope
 * (IO + SupervisorJob) — all reads/writes run there, keeping callers on any dispatcher
 * and the startup path free of synchronous preferences work (AD-10).
 *
 * Create ONCE per process (Hilt binding arrives with the first consuming epic, AR-3):
 * a second instance over the same file fails fast by DataStore contract.
 */
object SettingsDataStore {

    fun create(context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile(DataStoreSettingsRepository.FILE_NAME) },
        )
}
