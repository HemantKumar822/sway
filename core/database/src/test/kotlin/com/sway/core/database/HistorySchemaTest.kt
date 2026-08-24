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

/** Story 8.3 — migration 3 -> 4 proof: prior data survives; history usable. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class Migration3To4Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SwayDatabase::class.java,
    )

    @Test
    fun migrate3To4_succeeds_priorDataSurvives_historyUsable() {
        val dbName = ApplicationProvider.getApplicationContext<Context>()
            .getDatabasePath(TEST_DB).absolutePath

        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL("INSERT INTO song_entities (sourceId, title, rawTitle, durationMs) VALUES ('s1','T','T',1000)")
            db.execSQL(
                "INSERT INTO playlists (playlistId, name, rawName, createdAt, updatedAt) " +
                    "VALUES ('local:p', 'Gym', 'Gym', 1, 1)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 4, true, SwayDatabase.MIGRATION_3_4)

        db.query("SELECT COUNT(*) FROM song_entities").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM playlists").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        db.execSQL(
            "INSERT INTO history (songId, playedAt) VALUES ('s1', 999)",
        )
        db.query("SELECT playedAt FROM history WHERE songId='s1'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(999L, c.getLong(0))
        }
        db.close()
    }

    @Test
    fun builtDatabase_opensAtV4_throughTheProductionPath() = runBlocking {
        val db = SwayDatabase.build(ApplicationProvider.getApplicationContext())
        assertTrue(db.openHelper.writableDatabase.version >= 4)
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test-34.db"
    }
}

/** Story 8.3 — HistoryDao contract: recency upsert, trim cap, paged reads. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HistoryDaoTest {

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

    private suspend fun seedSnapshot(id: String) {
        db.libraryDao().upsert(
            SongEntity(sourceId = id, title = "T$id", rawTitle = "Raw$id", durationMs = 60_000),
        )
    }

    @Test
    fun recencyUpsert_threePlays_oneEntryLatestPlayedAt() = runBlocking {
        val dao = db.historyDao()
        seedSnapshot("s1")
        dao.upsert(HistoryEntity("s1", playedAt = 100L))
        dao.upsert(HistoryEntity("s1", playedAt = 200L))
        dao.upsert(HistoryEntity("s1", playedAt = 300L))

        assertEquals(1, dao.count())
        assertEquals(300L, dao.page(10, 0).single().playedAt)
    }

    @Test
    fun trim_keepsExactlyMostRecent500() = runBlocking {
        val dao = db.historyDao()
        // 505 distinct plays, ascending recency.
        for (i in 1..505) {
            seedSnapshot("s$i")
            dao.upsert(HistoryEntity("s$i", playedAt = i.toLong()))
        }
        dao.trimTo(500)

        assertEquals(500, dao.count())
        val page = dao.page(505, 0)
        // Oldest five must be gone; newest present.
        assertTrue(page.none { it.song.sourceId == "s5" })
        assertTrue(page.any { it.song.sourceId == "s505" })
        assertEquals("s505", page.first().song.sourceId)
    }

    @Test
    fun pagedRead_reverseChronological_withEntryData() = runBlocking {
        val dao = db.historyDao()
        listOf("a", "b").forEach { seedSnapshot(it) }
        dao.upsert(HistoryEntity("a", playedAt = 10L))
        dao.upsert(HistoryEntity("b", playedAt = 20L))

        val rows = dao.page(10, 0)
        assertEquals(listOf("b", "a"), rows.map { it.song.sourceId })
        assertEquals(listOf(20L, 10L), rows.map { it.playedAt })
        assertEquals("Tb", rows.first().song.title)

        val observed = db.historyDao().observeRecent(10).first()
        assertEquals(listOf("b", "a"), observed.map { it.song.sourceId })
    }
}
