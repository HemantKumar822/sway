package com.sway.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.sway.core.model.Quality
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Story 5.1 acceptance suite: real PreferenceDataStore instances over temp files,
 * plain JVM (the repository touches no android.* API, so no Robolectric needed).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DataStoreSettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Store + repo over a fresh temp file; tied to this test's backgroundScope (auto-cancelled). */
    private fun TestScope.newRepo(): Pair<DataStore<Preferences>, DataStoreSettingsRepository> {
        val file = File(tmp.newFolder(), DataStoreSettingsRepository.FILE_NAME + ".preferences_pb")
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            produceFile = { file },
        )
        return store to DataStoreSettingsRepository(store)
    }

    @Test
    fun freshStore_readsDefaultAuto() = runTest {
        val (_, repo) = newRepo()
        assertEquals(Quality.AUTO, repo.audioQuality.first())
    }

    @Test
    fun persistedQuality_isReadByFreshInstance_afterRestart() = runTest {
        val file = File(tmp.newFolder(), DataStoreSettingsRepository.FILE_NAME + ".preferences_pb")
        val scopeA = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val storeA = PreferenceDataStoreFactory.create(scope = scopeA) { file }
        DataStoreSettingsRepository(storeA).setAudioQuality(Quality.MEDIUM)

        // Simulate process death: shut the first instance down completely.
        val jobA = requireNotNull(scopeA.coroutineContext[Job])
        jobA.cancelAndJoin()
        advanceUntilIdle()

        // Fresh process, fresh DataStore handle, same file — async first read only.
        val storeB = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            produceFile = { file },
        )
        assertEquals(Quality.MEDIUM, DataStoreSettingsRepository(storeB).audioQuality.first())
    }

    @Test
    fun roundTrip_eachEnumValue_persistsDistinctly() = runTest {
        val (_, repo) = newRepo()
        for (quality in Quality.entries) {
            repo.setAudioQuality(quality)
            assertEquals(quality, repo.audioQuality.first())
        }
    }

    @Test
    fun corruptStoredValue_fallsBackToAuto_andRecoversOnNextValidWrite() = runTest {
        val (store, repo) = newRepo()
        repo.setAudioQuality(Quality.HIGH)

        // Foreign hand corrupts the stored enum name.
        store.edit { preferences ->
            preferences[DataStoreSettingsRepository.KEY_AUDIO_QUALITY] = "BOGUS"
        }
        assertEquals(Quality.AUTO, repo.audioQuality.first())

        repo.setAudioQuality(Quality.LOW)
        assertEquals(Quality.LOW, repo.audioQuality.first())
    }

    @Test
    fun ioFailureWhileReading_degradesToAuto_insteadOfCrashingCollectors() = runTest {
        assertEquals(Quality.AUTO, DataStoreSettingsRepository(FailingStore(IOException("unreadable"))).audioQuality.first())
    }

    @Test
    fun nonIoFailure_propagates_itIsABugNotADataState() = runTest {
        try {
            DataStoreSettingsRepository(FailingStore(IllegalStateException("bug"))).audioQuality.first()
            fail("non-IOException must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("bug", expected.message)
        }
    }

    @Test
    fun rapidSuccessiveWrites_lastWriteWins() = runTest {
        val (_, repo) = newRepo()
        repo.setAudioQuality(Quality.LOW)
        repo.setAudioQuality(Quality.HIGH)
        assertEquals(Quality.HIGH, repo.audioQuality.first())
    }

    @Test
    fun midSessionChange_propagatesThroughFlow_withoutAnyResolverContact() = runTest {
        val (_, repo) = newRepo()
        val emissions = mutableListOf<Quality>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.audioQuality.take(3).collect(emissions::add)
        }
        runCurrent()

        // Mid-session change: the repository exposes no invalidation/refresh surface at all;
        // consumers pick the new value up at their NEXT resolution (FR-15).
        repo.setAudioQuality(Quality.LOW)
        repo.setAudioQuality(Quality.HIGH)
        collector.join()

        assertEquals(listOf(Quality.AUTO, Quality.LOW, Quality.HIGH), emissions)
    }

    /** Store double whose reads always fail with [error]; updateData must never be reached. */
    private class FailingStore(private val error: Throwable) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw AssertionError("update not expected in this scenario")
    }
}
