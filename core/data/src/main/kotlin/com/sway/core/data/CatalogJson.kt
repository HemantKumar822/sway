package com.sway.core.data

import com.sway.core.model.Album
import com.sway.core.model.ArtworkRef
import com.sway.core.model.Artist
import com.sway.core.model.CatalogPlaylist
import com.sway.core.model.PagedResult
import com.sway.core.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Catalog JSON codecs (story 10.1): search pages of Songs + the three detail
 * payloads, owned by :core:data per the single-representation discipline.
 * Tolerant parse: invalid rows are dropped (siblings survive); structural
 * garbage yields empty — the cache layer already deleted corrupt files.
 */
internal object SongListJson {

    private val json = Json { ignoreUnknownKeys = true }

    fun encodePage(page: PagedResult<Song>): String = buildString {
        append("{\"songs\":[")
        page.items.forEachIndexed { i, s ->
            if (i > 0) append(",")
            append(songJson(s))
        }
        append("],\"next\":\"")
        append(page.normalizedNextPageToken ?: "")
        append("\"}")
    }

    fun decodePage(raw: String): List<Song> = try {
        val root = json.parseToJsonElement(raw).jsonObject
        root["songs"]!!.jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            Song.create(
                id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                rawTitle = o["rawTitle"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                artistName = o["artist"]?.jsonPrimitive?.content,
                durationMs = o["durationMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                artwork = o["artwork"]?.jsonPrimitive?.content?.let { ArtworkRef.of(it) },
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun songJson(s: Song): String = buildString {
        append("{\"id\":\"").append(esc(s.id.value))
        append("\",\"title\":\"").append(esc(s.title))
        append("\",\"rawTitle\":\"").append(esc(s.rawTitle))
        append("\",\"artist\":")
        if (s.artistName != null) append("\"").append(esc(s.artistName!!)).append("\"") else append("null")
        append(",\"durationMs\":").append(s.duration.millis)
        append(",\"artwork\":")
        if (s.artwork != null) append("\"").append(esc(s.artwork!!.canonicalUrl)).append("\"") else append("null")
        append("}")
    }

    fun esc(raw: String): String = buildString {
        for (ch in raw) when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
}

/** Detail payload codec: album/artist/catalogplaylist shapes. */
internal object DetailJson {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(detail: Any): String = when (detail) {
        is Album -> "{\"type\":\"album\",\"data\":${albumJson(detail)}}"
        is Artist -> "{\"type\":\"artist\",\"data\":{\"id\":\"${SongListJson.esc(detail.id.value)}\",\"name\":\"${SongListJson.esc(detail.name)}\"}}"
        is CatalogPlaylist -> "{\"type\":\"catalogplaylist\",\"data\":{\"id\":\"${SongListJson.esc(detail.id.value)}\",\"title\":\"${SongListJson.esc(detail.title)}\"}}"
        else -> throw IllegalArgumentException("unknown detail type")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> decode(type: String, raw: String): T? = try {
        val root = json.parseToJsonElement(raw).jsonObject
        if (root["type"]?.jsonPrimitive?.content != type) return null
        val data = root["data"]!!.jsonObject
        when (type) {
            "album" -> albumFrom(data) as T?
            else -> null // artist/catalogPlaylist stale-serve lands with their screens (E10.6/7 need tracks anyway)
        }
    } catch (_: Exception) {
        null
    }

    private fun albumJson(a: Album): String = buildString {
        append("{\"id\":\"").append(SongListJson.esc(a.id.value))
        append("\",\"title\":\"").append(SongListJson.esc(a.title))
        append("\",\"artist\":")
        if (a.artistName != null) append("\"").append(SongListJson.esc(a.artistName!!)).append("\"") else append("null")
        append(",\"tracks\":[")
        a.tracks.forEachIndexed { i, t ->
            if (i > 0) append(",")
            append(SongListJson.songJson(t))
        }
        append("]}")
    }

    private fun albumFrom(o: kotlinx.serialization.json.JsonObject): Album? {
        val id = o["id"]?.jsonPrimitive?.content ?: return null
        val rawTitle = o["title"]?.jsonPrimitive?.content ?: return null
        val tracks = o["tracks"]!!.jsonArray.mapNotNull { el ->
            val t = el.jsonObject
            Song.create(
                id = t["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                rawTitle = t["rawTitle"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                artistName = t["artist"]?.jsonPrimitive?.content,
                durationMs = t["durationMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                artwork = t["artwork"]?.jsonPrimitive?.content?.let { ArtworkRef.of(it) },
            )
        }
        return Album.create(
            id = id,
            rawTitle = rawTitle,
            artistName = o["artist"]?.jsonPrimitive?.content,
            tracks = tracks,
        )
    }
}
