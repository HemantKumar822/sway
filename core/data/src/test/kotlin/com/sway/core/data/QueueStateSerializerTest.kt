package com.sway.core.data

import com.sway.core.model.QueueSnapshot
import com.sway.core.model.RepeatMode
import com.sway.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Story 7.3 — canonical serializer law (AD-8 single representation):
 * pure-JVM property-style checks over [QueueStateSerializer]. The ONE place
 * queue state becomes JSON and back; corruption degrades to null, never
 * throws.
 */
class QueueStateSerializerTest {

    private fun session(
        repeat: RepeatMode = RepeatMode.OFF,
        shuffle: Boolean = false,
        index: Int = 1,
        position: Long = 42_000L,
    ): QueueStateSerializer.RestoredSession {
        val songs = listOf(
            Song.create(id = "q1", rawTitle = "First", artistName = "A", durationMs = 100_000, artwork = null)!!,
            Song.create(id = "q2", rawTitle = "Second", artistName = null, durationMs = 200_000, artwork = null)!!,
            Song.create(id = "q3", rawTitle = "Third", artistName = "C", durationMs = 300_000, artwork = null)!!,
        )
        return QueueStateSerializer.RestoredSession(
            snapshot = QueueSnapshot.of(songs.map { com.sway.core.model.QueueItem.of(it) }),
            currentIndex = index,
            positionMs = position,
            shuffleEnabled = shuffle,
            repeatMode = repeat,
        )
    }

    @Test
    fun roundTrip_preservesEverything() {
        val original = session(repeat = RepeatMode.ONE, shuffle = true, index = 2, position = 123_456L)
        val restored = QueueStateSerializer.fromJson(QueueStateSerializer.toJson(original))
        assertNotNull(restored)
        assertEquals(3, restored!!.snapshot.size)
        assertEquals(listOf("q1", "q2", "q3"), restored.snapshot.items.map { it.id.value })
        assertEquals(2, restored.currentIndex)
        assertEquals(123_456L, restored.positionMs)
        assertTrue(restored.shuffleEnabled)
        assertEquals(RepeatMode.ONE, restored.repeatMode)
    }

    @Test
    fun nullableFields_roundTrip_cleanly_andTitlesSurviveSanitizerStable() {
        val restored = QueueStateSerializer.fromJson(QueueStateSerializer.toJson(session()))!!
        assertNull(restored.snapshot.itemAt(1)!!.song.artistName)
        assertEquals("First", restored.snapshot.itemAt(0)!!.song.title)
        assertEquals("First", restored.snapshot.itemAt(0)!!.song.rawTitle)
    }

    @Test
    fun corruptJson_yieldsNull_neverThrows() {
        assertNull(QueueStateSerializer.fromJson("not json at all"))
        assertNull(QueueStateSerializer.fromJson("{\"v\":1}"))
        assertNull(QueueStateSerializer.fromJson(""))
        assertNull(QueueStateSerializer.fromJson(null))
    }

    @Test
    fun wrongFormatVersion_yieldsNull() {
        val raw = QueueStateSerializer.toJson(session())
            .replace("\"v\":1", "\"v\":99")
        assertNull(QualityGuard(raw))
    }

    /** Helper keeps the version-mismatch case inside the serializer contract. */
    private fun QualityGuard(raw: String): Any? = QueueStateSerializer.fromJson(raw)

    @Test
    fun outOfRangeIndexAndPosition_coerceOnParse() {
        val json = QueueStateSerializer.toJson(session(index = 1, position = 42_000L))
            .replace("\"currentIndex\":1", "\"currentIndex\":-5")
            .replace("\"positionMs\":42000", "\"positionMs\":-9")
        val restored = QueueStateSerializer.fromJson(json)!!
        assertEquals(0, restored.currentIndex)
        assertEquals(0L, restored.positionMs)
    }

    @Test
    fun invalidSongRow_dropped_siblingsSurvive_blankIdLaw() {
        val json = """
            {"v":1,"songs":[
                {"id":"","title":"Broken","rawTitle":"Broken","durationMs":1},
                {"id":"q1","title":"First","rawTitle":"First","durationMs":100000}
            ],"currentIndex":0,"positionMs":0,"shuffle":false,"repeat":"OFF"}
        """.trimIndent()
        val restored = QueueStateSerializer.fromJson(json)
        assertNotNull("valid siblings must still restore", restored)
        assertEquals(1, restored!!.snapshot.size)
        assertEquals("q1", restored.snapshot.itemAt(0)!!.id.value)
    }
}
