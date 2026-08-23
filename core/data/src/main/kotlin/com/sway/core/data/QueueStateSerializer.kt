package com.sway.core.data

import com.sway.core.model.ArtworkRef
import com.sway.core.model.QueueSnapshot
import com.sway.core.model.RepeatMode
import com.sway.core.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * THE canonical QueueSnapshot (de)serializer (story 7.3, AD-8 single-
 * representation law): this object — owned by `:core:data` — is the only code
 * in the repository allowed to turn queue state into JSON and back. A scripts/
 * grep audit enforces ownership; a second snapshot shape would silently break
 * FR-25 restore.
 *
 * Format v1: full Song snapshots (id/title/rawTitle/artist/duration/artwork)
 * so restore renders the Mini Player fully offline (UJ-4/UJ-5). Parsing is
 * tolerant of unknown fields but strict about required ones: any structural
 * violation yields `null` from [fromJson] — corrupt rows degrade to "no saved
 * session", never a crash.
 */
object QueueStateSerializer {

    const val FORMAT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    data class RestoredSession(
        val snapshot: QueueSnapshot,
        val currentIndex: Int,
        val positionMs: Long,
        val shuffleEnabled: Boolean,
        val repeatMode: RepeatMode,
    )

    fun toJson(session: RestoredSession): String {
        val songs = buildJsonArray {
            session.snapshot.items.forEach { qi ->
                add(
                    buildJsonObject {
                        put("id", qi.id.value)
                        put("title", qi.song.title)
                        put("rawTitle", qi.song.rawTitle)
                        qi.song.artistName?.let { put("artist", it) }
                        put("durationMs", qi.song.duration.millis)
                        qi.song.artwork?.canonicalUrl?.let { put("artwork", it) }
                    },
                )
            }
        }
        return buildJsonObject {
            put("v", FORMAT_VERSION)
            put("songs", songs)
            put("currentIndex", session.currentIndex)
            put("positionMs", session.positionMs)
            put("shuffle", session.shuffleEnabled)
            put("repeat", session.repeatMode.name)
        }.toString()
    }

    fun fromJson(raw: String?): RestoredSession? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            if (root["v"]?.jsonPrimitive?.int != FORMAT_VERSION) return null
            val songs = root["songs"]!!.jsonArray.mapNotNull { element ->
                songFromJson(element.jsonObject)
            }
            if (songs.isEmpty()) return null
            RestoredSession(
                snapshot = QueueSnapshot.of(songs.map { com.sway.core.model.QueueItem.of(it) }),
                currentIndex = root["currentIndex"]!!.jsonPrimitive.int.coerceAtLeast(0),
                positionMs = root["positionMs"]!!.jsonPrimitive.long.coerceAtLeast(0L),
                shuffleEnabled = root["shuffle"]!!.jsonPrimitive.boolean,
                repeatMode = repeatFromName(root["repeat"]!!.jsonPrimitive.content),
            )
        } catch (_: Exception) {
            null // corrupt row == no saved session (C-8 degradation law)
        }
    }

    private fun songFromJson(obj: JsonObject): Song? =
        Song.create(
            id = obj["id"]?.jsonPrimitive?.content ?: return null,
            rawTitle = obj["rawTitle"]?.jsonPrimitive?.content ?: return null,
            artistName = obj["artist"]?.jsonPrimitive?.content,
            durationMs = obj["durationMs"]?.jsonPrimitive?.long ?: 0L,
            artwork = obj["artwork"]?.jsonPrimitive?.content?.let { ArtworkRef.of(it) },
        )

    private fun repeatFromName(name: String): RepeatMode =
        RepeatMode.entries.firstOrNull { it.name == name } ?: RepeatMode.OFF
}
