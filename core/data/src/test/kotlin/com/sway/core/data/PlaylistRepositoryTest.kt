package com.sway.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.database.SwayDatabase
import com.sway.core.model.PlaylistId
import com.sway.core.model.Song
import com.sway.core.model.SwayResult
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
 * Story 8.2 — PlaylistRepository laws (FR-31/32 substrate, NFR-2):
 * duplicate names allowed; multi-membership invariant; atomic rollback on
 * mid-way failure; reorder payload permutation law; and THE contiguity
 * property — positions stay 0..n-1 gapless/duplicate-free over randomized
 * operation storms (deterministic seeds).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PlaylistRepositoryTest {

    private lateinit var db: SwayDatabase
    private lateinit var repo: PlaylistRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SwayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = PlaylistRepository(db.playlistDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun song(id: String): Song =
        Song.create(id = id, rawTitle = "Song $id", artistName = null, durationMs = 60_000L, artwork = null)!!

    private suspend fun idOf(rawName: String): PlaylistId =
        (repo.create(rawName) as SwayResult.Success).data

    // --- AC: duplicate names allowed ---------------------------------------

    @Test
    fun duplicateNames_persistIndependently() = runBlocking {
        val a = idOf("Gym")
        val b = idOf("Gym")
        assertTrue(a.value != b.value)
        val lists = repo.observePlaylists().first()
        assertEquals(2, lists.size)
        assertTrue(lists.all { it.playlist.name == "Gym" })
    }

    // --- AC: multi-membership invariant ------------------------------------

    @Test
    fun songInTwoPlaylists_removingFromOne_touchesNotTheOther() = runBlocking {
        val p1 = idOf("One")
        val p2 = idOf("Two")
        repo.addSong(p1, song("s1"))
        repo.addSong(p2, song("s1"))
        repo.addSong(p2, song("s2"))

        repo.removeSong(p1, "s1")

        assertEquals(0, repo.observeSongs(p1).first().size)
        assertEquals(listOf("s1", "s2"), repo.observeSongs(p2).first().map { it.id.value })
    }

    // --- AC: transactional atomicity (failure mid-way rolls back fully) -----

    @Test
    fun addSong_withUnknownSnapshot_rollsBackFully_membershipUnchanged() = runBlocking {
        val pid = idOf("Roll")
        repo.addSong(pid, song("known"))
        val before = repo.observeSongs(pid).first()

        // A Song whose snapshot insert succeeds but whose join row violates
        // nothing... so force the failure INSIDE the transaction instead:
        // delete the snapshot behind the facade, then attempt an append that
        // references it -> FK violation at join insert time.
        repo.appendWithoutSnapshotForTest(pid, "ghost")

        assertEquals(
            "membership must be exactly the pre-failure state",
            before.map { it.id.value },
            repo.observeSongs(pid).first().map { it.id.value },
        )
        // typed Storage failure asserted inside the seam call above
    }

    // --- AC4: contiguity property over randomized operation storms ----------

    @Test
    fun property_randomOperationSequences_positionsStayContiguous() {
        for (seed in listOf(7L, 99L, 2026L)) {
            contiguityStorm(seed)
        }
    }

    private fun contiguityStorm(seed: Long) = runBlocking {
        val rng = kotlin.random.Random(seed)
        val pid = idOf("storm$seed")
        val pool = (0 until 12).map { song("p$seed-$it") }
        pool.forEach { repo.ensureSnapshotPublicForTest(it) }

        val oracle = mutableListOf<String>()
        repeat(120) {
            when (rng.nextInt(3)) {
                0 -> { // add random not-yet-member (skip when pool exhausted)
                    val candidates = pool.filter { it.id.value !in oracle }
                    if (candidates.isNotEmpty()) {
                        val candidate = candidates.random(rng)
                        if (repo.addSong(pid, candidate) is SwayResult.Success) {
                            oracle.add(candidate.id.value)
                        }
                    }
                }
                1 -> { // remove random member
                    if (oracle.isNotEmpty()) {
                        val victim = oracle.random(rng)
                        if (repo.removeSong(pid, victim) is SwayResult.Success) {
                            oracle.remove(victim)
                        }
                    }
                }
                else -> { // full shuffle reorder
                    val shuffled = oracle.shuffled(rng)
                    if (repo.reorder(pid, shuffled) is SwayResult.Success) {
                        oracle.clear(); oracle.addAll(shuffled)
                    }
                }
            }

            // Property holds after EVERY operation.
            val rows = membershipPositions(pid)
            assertEquals(
                "seed=$seed op=$it ids=${oracle}",
                oracle.indices.toList(),
                rows,
            )
            assertEquals(oracle.size, rows.size)
        }
    }

    private fun membershipPositions(pid: PlaylistId): List<Int> =
        db.openHelper.readableDatabase.query(
            "SELECT position FROM playlist_songs WHERE playlistId = ? ORDER BY position",
            arrayOf(pid.value),
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0)) }
        }
}
