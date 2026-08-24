package com.sway.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 8.1 — LibraryDao contract suite (FR-30): liked flow ordered likedAt
 * DESC, NULL = not liked (excluded), set/clear via upsert preserving the
 * snapshot, batch liked-id probe.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LibraryDaoTest {

    private lateinit var db: SwayDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SwayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.libraryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun likedFlow_orderedByLikedAtDesc_nullExcluded() = runBlocking {
        dao.upsert(entity("a", likedAt = 300L))
        dao.upsert(entity("b", likedAt = 100L))
        dao.upsert(entity("c", likedAt = 200L))
        dao.upsert(entity("d", likedAt = null)) // not liked: excluded

        val liked = dao.likedSongs().first()
        assertEquals(listOf("a", "c", "b"), liked.map { it.sourceId })
    }

    @Test
    fun setClear_viaUpsert_preservesSnapshot_andClearExcludesFromFlow() = runBlocking {
        dao.upsert(entity("X", likedAt = null))
        // Like it: only likedAt changes; snapshot fields persist.
        val row = dao.byId("X")!!
        dao.upsert(row.copy(likedAt = 555L))
        val likedRow = dao.byId("X")!!
        assertEquals("Title X", likedRow.title)
        assertEquals("Artist X", likedRow.artistName)
        assertEquals(555L, likedRow.likedAt)
        assertTrue(dao.likedSongs().first().any { it.sourceId == "X" })

        // Unlike: marker cleared, ROW retained (AD-8 retention law).
        dao.upsert(likedRow.copy(likedAt = null))
        assertNull(dao.byId("X")!!.likedAt)
        assertTrue(dao.likedSongs().first().none { it.sourceId == "X" })
        assertTrue(dao.byId("X") != null)
    }

    @Test
    fun batchProbe_likedIdsAmong_returnsOnlyLiked() = runBlocking {
        dao.upsert(entity("k1", likedAt = 1L))
        dao.upsert(entity("k2", likedAt = 2L))
        dao.upsert(entity("k3", likedAt = null))

        val liked = dao.likedIdsAmong(listOf("k1", "k2", "k3", "missing"))
        assertEquals(setOf("k1", "k2"), liked.toSet())
    }

    @Test
    fun concurrentToggleSettle_finalStateConsistent_flowEmitsOrdered() = runBlocking {
        dao.upsert(entity("t1", likedAt = null))
        // Two interleaved writers settle on distinct final moments; the last
        // write wins and the flow reflects a single consistent state.
        val jobs = listOf(
            launch(Dispatchers.IO) {
                repeat(20) { i ->
                    val r = dao.byId("t1")!!
                    dao.upsert(r.copy(likedAt = 1_000L + i))
                }
            },
            launch(Dispatchers.IO) {
                repeat(20) { i ->
                    val r = dao.byId("t1") ?: return@launch
                    dao.upsert(r.copy(likedAt = 5_000L + i))
                }
            },
        )
        jobs.forEach { it.join() }

        val final = dao.byId("t1")!!
        assertTrue("settled like moment must exist", final.likedAt != null)
        val flow = dao.likedSongs().first()
        assertEquals(1, flow.size)
        assertEquals(final.likedAt, flow.single().likedAt)
    }

    private fun entity(id: String, likedAt: Long?): SongEntity =
        SongEntity(
            sourceId = id,
            title = "Title $id",
            rawTitle = "Raw $id",
            artistName = "Artist X",
            durationMs = 60_000L,
            artworkUrl = null,
            likedAt = likedAt,
        )
}
