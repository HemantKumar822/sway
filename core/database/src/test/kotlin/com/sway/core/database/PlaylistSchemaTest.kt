package com.sway.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 8.2 — migration 2 -> 3 proof (AD-8): likes (v2 data) and queue state
 * (v1 data) survive; playlist tables usable immediately after.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class Migration2To3Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SwayDatabase::class.java,
    )

    @Test
    fun migrate2To3_succeeds_priorDataSurvives_newTablesUsable() {
        val dbName = ApplicationProvider.getApplicationContext<Context>()
            .getDatabasePath(TEST_DB).absolutePath

        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                "INSERT INTO queue_state (id, songsJson, currentIndex, positionMs, shuffleEnabled, repeatMode, savedAt) " +
                    "VALUES (1, '{}', 0, 0, 0, 'OFF', 1)",
            )
            db.execSQL(
                "INSERT INTO song_entities (sourceId, title, rawTitle, durationMs, likedAt) " +
                    "VALUES ('s1', 'T', 'T', 1000, 42)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 3, true, SwayDatabase.MIGRATION_2_3)

        db.query("SELECT COUNT(*) FROM song_entities WHERE likedAt IS NOT NULL").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
        }
        db.query("SELECT songsJson FROM queue_state WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
        }
        db.execSQL(
            "INSERT INTO playlists (playlistId, name, rawName, createdAt, updatedAt) " +
                "VALUES ('local:x', 'Gym', 'Gym', 5, 5)",
        )
        db.query("SELECT COUNT(*) FROM playlists").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun builtDatabase_opensAtV3_throughTheProductionPath() = runBlocking {
        val db = SwayDatabase.build(ApplicationProvider.getApplicationContext())
        assertEquals(3, db.openHelper.writableDatabase.version)
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test-23.db"
    }
}

/**
 * Story 8.2 — PlaylistDao contract suite: atomic rewrite primitive,
 * duplicate names allowed, delete cascades join rows only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PlaylistDaoTest {

    private lateinit var db: SwayDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SwayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun duplicateNames_allowed_independentRows() = runBlocking {
        val dao = db.playlistDao()
        listOf("local:1", "local:2").forEach { id ->
            dao.insertPlaylist(PlaylistEntity(id, "Gym", "Gym", createdAt = 1L, updatedAt = 1L))
        }
        val rows = dao.observePlaylists().first()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.playlist.name == "Gym" })
    }

    @Test
    fun rewriteMembership_atomic_addRemoveReorder_contiguousPositions() = runBlocking {
        seedPlaylistWithSongs(listOf("s1", "s2", "s3"))

        val dao = db.playlistDao()
        // Remove middle + reorder remainder in ONE transaction.
        dao.rewriteMembership(
            PL,
            listOf(member("s3", 0), member("s1", 1)),
        )
        val songs = dao.songsNow(PL)
        assertEquals(listOf("s3", "s1"), songs.map { it.sourceId })
        assertEquals(listOf(0, 1), positions())

        // Remove ALL (empty rewrite must still clear).
        dao.rewriteMembership(PL, emptyList())
        assertTrue(dao.songsNow(PL).isEmpty())
    }

    @Test
    fun deleteCascades_joinRowsOnly_songSnapshotsRetained() = runBlocking {
        seedPlaylistWithSongs(listOf("s1"))
        db.libraryDao().upsert(entity("s1")) // ensure snapshot exists anyway

        db.playlistDao().delete(PL)
        assertEquals(0, count("playlist_songs"))
        assertEquals(1, count("song_entities"))
    }

    // --- helpers -----------------------------------------------------------

    private companion object {
        const val PL = "local:p1"
    }

    private fun member(songId: String, position: Int): PlaylistSongEntity =
        PlaylistSongEntity(PL, songId, position, addedAt = 1L)

    private suspend fun positions(): List<Int> =
        db.openHelper.readableDatabase
            .query("SELECT position FROM playlist_songs WHERE playlistId = ? ORDER BY position", arrayOf(PL))
            .use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }

    private fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { c ->
            c.moveToFirst(); c.getInt(0)
        }

    private fun entity(id: String) = SongEntity(
        sourceId = id, title = "T$id", rawTitle = "Raw$id", durationMs = 1000L,
    )

    private suspend fun seedPlaylistWithSongs(ids: List<String>) {
        val dao = db.playlistDao()
        dao.insertPlaylist(PlaylistEntity(PL, "Mix", "Mix", 1L, 1L))
        ids.forEach { id -> dao.upsertSnapshot(entity(id)) }
        dao.rewriteMembership(PL, ids.mapIndexed { i, id -> member(id, i) })
    }
}
