package com.sway.music.screens.library

import com.sway.core.model.Song
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Play History day grouping (story 11.2, FR-34, UX-P12 [PROVISIONAL]):
 * reverse-chron entries fold into Today / Yesterday / date sections at LOCAL
 * midnight boundaries. Pure + clock-injectable for hermetic boundary tests.
 */
object HistoryDayGrouper {

    data class Section(val header: String, val entries: List<HistoryRow>)

    data class HistoryRow(val song: Song, val playedAt: Long)

    fun group(
        entries: List<HistoryEntry>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Section> {
        if (entries.isEmpty()) return emptyList()
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val yesterday = today.minusDays(1)
        val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")

        val sections = mutableListOf<Pair<String, MutableList<HistoryRow>>>()
        for (entry in entries) { // input is already reverse-chronological
            val day = LocalDate.ofInstant(Instant.ofEpochMilli(entry.playedAt), zone)
            val header = when (day) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> day.format(dateFmt)
            }
            if (sections.lastOrNull()?.first != header) {
                sections += header to mutableListOf()
            }
            sections.last().second += HistoryRow(entry.song, entry.playedAt)
        }
        return sections.map { (header, rows) -> Section(header, rows.toList()) }
    }

    /** HH:mm stamp for a row (rendered tabular via tnum at the call site). */
    fun timeLabel(playedAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(playedAt))
}

/** Local alias keeping the screen decoupled from core:data's row type. */
typealias HistoryEntry = com.sway.core.data.HistoryEntry
