package com.sway.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Story 7.3 — Room birth proof (AD-8): the ONE database opens at version 1
 * with the exported schema committed to VCS; QueueStateDao round-trips the
 * singleton row; destructive fallback is refused (no such API call anywhere —
 * grep audit) and future migrations MUST be explicit + schema-tested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SwayDatabaseTest {

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
    fun singletonRow_roundTrips_andSecondSaveOverwrites() = runTest {
        val dao = db.queueStateDao()
        assertNull(dao.loadOnce())

        dao.save(QueueStateEntity(songsJson = "{}", currentIndex = 0, positionMs = 1L, shuffleEnabled = false, repeatMode = "OFF", savedAt = 100L))
        val first = dao.loadOnce()
        assertEquals(1, first!!.id)
        assertEquals("{}", first.songsJson)

        dao.save(QueueStateEntity(songsJson = "{\"v\":1}", currentIndex = 2, positionMs = 9L, shuffleEnabled = true, repeatMode = "ONE", savedAt = 200L))
        val second = dao.loadOnce()!!
        assertEquals("{\"v\":1}", second.songsJson)
        assertEquals(2, second.currentIndex)
        assertTrue(second.shuffleEnabled)
        assertEquals("ONE", second.repeatMode)
    }

    @Test
    fun observe_emitsCurrentRow_thenNothingAfterClear() = runBlocking {
        val dao = db.queueStateDao()
        assertEquals(null, dao.observe().first())
        dao.save(QueueStateEntity(songsJson = "x", currentIndex = 0, positionMs = 0L, shuffleEnabled = false, repeatMode = "OFF", savedAt = 1L))
        assertEquals("x", dao.observe().first()!!.songsJson)
        dao.clear()
        assertEquals(null, dao.observe().first())
        Unit
    }

    @Test
    fun exportedSchema_v1_isCommittedToVcs_andContainsQueueStateTable() {
        val schema = File("schemas/com.sway.core.database.SwayDatabase/1.json")
        assertTrue("Exported schema 1.json must exist (exportSchema=true law)", schema.exists())
        val content = schema.readText()
        assertTrue(content.contains("\"tableName\": \"queue_state\""))
        assertTrue(content.contains("\"version\": 1"))
    }
}
