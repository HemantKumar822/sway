package com.sway.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 8.1 — migration 1 -> 2 proof (AD-8): explicit migration validated
 * against exported schemas; data written at v1 SURVIVES into v2 (the queue
 * row is the canary); the new table accepts rows immediately after.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class Migration1To2Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SwayDatabase::class.java,
    )

    @Test
    fun migrate1To2_succeeds_queueDataSurvives_newTableUsable() {
        // Robolectric quirk: create/validate must share the SAME absolute path,
        // otherwise the SQLite driver sees two distinct database identities.
        val dbName = ApplicationProvider.getApplicationContext<Context>()
            .getDatabasePath(TEST_DB).absolutePath

        // Create at v1 and write a queue-state row (the survival canary).
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO queue_state (id, songsJson, currentIndex, positionMs, shuffleEnabled, repeatMode, savedAt) " +
                    "VALUES (1, '{\"v\":1}', 3, 42000, 1, 'ONE', 111)",
            )
        }

        // Migrate to v2 with schema validation against the exported JSON.
        val db = helper.runMigrationsAndValidate(dbName, 2, true, SwayDatabase.MIGRATION_1_2)

        // Canary survives.
        db.query("SELECT songsJson, currentIndex FROM queue_state WHERE id = 1").use { cursor ->
            assertTrue("queue_state row must survive migration", cursor.moveToFirst())
            assertEquals("{\"v\":1}", cursor.getString(0))
            assertEquals(3, cursor.getInt(1))
        }

        // New table is usable right away.
        db.execSQL(
            "INSERT INTO song_entities (sourceId, title, rawTitle, durationMs, likedAt) " +
                "VALUES ('s1', 'T', 'T', 1000, 777)",
        )
        db.query("SELECT COUNT(*) FROM song_entities WHERE likedAt IS NOT NULL").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun builtDatabase_opensAtV2_throughTheProductionPath() = runBlocking {
        val db = SwayDatabase.build(ApplicationProvider.getApplicationContext())
        assertEquals(2, db.openHelper.writableDatabase.version)
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
