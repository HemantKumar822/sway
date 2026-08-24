package com.sway.core.data

import android.content.Context
import android.database.SQLException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.database.LibraryDao
import com.sway.core.database.SongEntity
import com.sway.core.database.SwayDatabase
import com.sway.core.model.RepeatMode
import com.sway.core.model.Song
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 8.1 — LibraryRepository laws (FR-30 substrate, NFR-2): typed Storage
 * failures never masquerade as empty success; ordering law; batch probe;
 * concurrent writers settle consistently.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LibraryRepositoryTest {

    private lateinit var db: SwayDatabase
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SwayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = LibraryRepository(db.libraryDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun likeSnapshot_ordering_andUnlikeRetention() = runBlocking {
        repo.setLiked(song("a"))
        kotlinx.coroutines.delay(5)
        repo.setLiked(song("c"))
        kotlinx.coroutines.delay(5)
        repo.setLiked(song("b"))

        val snap = repo.likedSnapshot()
        assertTrue(snap is SwayResult.Success)
        assertEquals(listOf("b", "c", "a"), (snap as SwayResult.Success).data.map { it.id.value })
        assertEquals(
            listOf("b", "c", "a"),
            repo.observeLiked().first().map { it.id.value },
        )

        repo.clearLiked(com.sway.core.model.SourceId.parse("c")!!)
        val after = repo.likedSnapshot() as SwayResult.Success
        assertEquals(listOf("b", "a"), after.data.map { it.id.value })
    }

    @Test
    fun firstRun_snapshotIsEmptySuccess_notFailure() = runBlocking {
        val snap = repo.likedSnapshot()
        assertTrue(snap is SwayResult.Success)
        assertEquals(0, (snap as SwayResult.Success).data.size)
    }

    @Test
    fun batchProbe_likedIdsAmong() = runBlocking {
        repo.setLiked(song("k1"))
        repo.setLiked(song("k2"))
        val ids = listOf("k1", "k2", "k3").mapNotNull { com.sway.core.model.SourceId.parse(it) }
        val result = repo.likedIdsAmong(ids)
        assertTrue(result is SwayResult.Success)
        assertEquals(setOf("k1", "k2"), (result as SwayResult.Success).data.map { it.value }.toSet())
    }

    @Test
    fun daoFailure_surfacesTypedStorage_neverEmptyAsSuccess() = runBlocking {
        val failing = LibraryRepository(FailingLibraryDao())
        val snap = failing.likedSnapshot()
        assertTrue("snapshot must be typed Failure", snap is SwayResult.Failure)
        assertEquals(SwayError.Storage, (snap as SwayResult.Failure).error)

        val set = failing.setLiked(song("z"))
        assertTrue(set is SwayResult.Failure && set.error == SwayError.Storage)

        val clear = failing.clearLiked(com.sway.core.model.SourceId.parse("z")!!)
        assertTrue(clear is SwayResult.Failure && clear.error == SwayError.Storage)

        val batch = failing.likedIdsAmong(listOf(com.sway.core.model.SourceId.parse("z")!!))
        assertTrue(batch is SwayResult.Failure && batch.error == SwayError.Storage)
    }

    @Test
    fun concurrentLikeWriters_settleConsistently_flowMatchesFinalState() = runBlocking {
        val song = song("hot")
        val writers = (1..2).map { n ->
            async(Dispatchers.IO) {
                repeat(15) { i ->
                    repo.setLiked(song)
                }
            }
        }
        writers.awaitAll()

        val flowSongs = repo.observeLiked().first()
        assertEquals(1, flowSongs.size)
        assertEquals("hot", flowSongs.single().id.value)
        val snap = repo.likedSnapshot() as SwayResult.Success
        assertEquals(flowSongs.map { it.id.value }, snap.data.map { it.id.value })
    }

    private fun song(id: String): Song =
        Song.create(id = id, rawTitle = "Song $id", artistName = null, durationMs = 60_000L, artwork = null)!!

    /** DAO double whose every call throws a storage-class exception. */
    private class FailingLibraryDao : LibraryDao {
        override fun likedSongs(): kotlinx.coroutines.flow.Flow<List<SongEntity>> =
            throw SQLException("disk full")
        override suspend fun likedSongsNow(): List<SongEntity> = throw SQLException("disk full")
        override suspend fun byId(sourceId: String): SongEntity? = throw SQLException("disk full")
        override suspend fun upsert(entity: SongEntity) = throw SQLException("disk full")
        override suspend fun likedIdsAmong(sourceIds: List<String>): List<String> =
            throw SQLException("disk full")
        override suspend fun deleteById(sourceId: String) = throw SQLException("disk full")
    }
}
