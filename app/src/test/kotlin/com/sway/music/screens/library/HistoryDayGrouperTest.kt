package com.sway.music.screens.library

import com.sway.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Story 11.2 — pure day-grouping laws at LOCAL-midnight boundaries
 * (UTC-pinned for determinism): Today/Yesterday/date folding, order
 * stability, exact-boundary rows land in the right section.
 */
class HistoryDayGrouperTest {

    private val utc = ZoneOffset.UTC
    private fun song(id: String) = Song.create(id, "Song $id")!!
    private fun entry(id: String, epochMillis: Long) =
        com.sway.core.data.HistoryEntry(song(id), epochMillis)

    private fun atUtc(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).toInstant(utc).toEpochMilli()

    @Test
    fun groups_intoTodayYesterdayAndDateSections() {
        val today = LocalDate.of(2026, 8, 24)
        val yesterday = today.minusDays(1)
        val older = today.minusDays(9)
        val now = atUtc(today, 20, 0)

        val sections = HistoryDayGrouper.group(
            listOf(
                entry("a", atUtc(today, 19, 30)),
                entry("b", atUtc(today, 8, 0)),
                entry("c", atUtc(yesterday, 23, 10)),
                entry("d", atUtc(older, 12, 0)),
            ),
            nowMillis = now,
            zone = utc,
        )

        assertEquals(listOf("Today", "Yesterday", "15 Aug 2026"), sections.map { it.header })
        assertEquals(listOf("Song a", "Song b"), sections[0].entries.map { it.song.title })
    }

    @Test
    fun exactMidnightBoundary_rowAtMidnightBelongsToThatDay() {
        val today = LocalDate.of(2026, 3, 1)
        val yesterday = today.minusDays(1)
        val now = atUtc(today, 12, 0)

        val sections = HistoryDayGrouper.group(
            listOf(
                entry("midnight-today", atUtc(today, 0, 0)),
                entry("last-minute-yesterday", atUtc(yesterday, 23, 59)),
            ),
            nowMillis = now,
            zone = utc,
        )
        assertEquals("Today", sections[0].header)
        assertEquals("Song midnight-today", sections[0].entries.single().song.title)
        assertEquals("Yesterday", sections[1].header)
        assertEquals("Song last-minute-yesterday", sections[1].entries.single().song.title)
    }

    @Test
    fun orderWithinSection_preservesInputReverseChron() {
        val today = LocalDate.of(2026, 8, 24)
        val now = atUtc(today, 21, 0)
        val sections = HistoryDayGrouper.group(
            listOf(
                entry("late", atUtc(today, 20, 0)),
                entry("early", atUtc(today, 7, 15)),
            ),
            nowMillis = now,
            zone = utc,
        )
        assertEquals(listOf("Song late", "Song early"), sections.single().entries.map { it.song.title })
    }

    @Test
    fun emptyInput_emptySections_neverBlankHeader() {
        assertTrue(HistoryDayGrouper.group(emptyList(), nowMillis = 0L, zone = utc).isEmpty())
    }

    @Test
    fun timeLabel_rendersHourMinute() {
        val stamp = atUtc(LocalDate.of(2026, 8, 24), 9, 5)
        assertEquals("09:05", HistoryDayGrouper.timeLabel(stamp, utc))
    }
}
