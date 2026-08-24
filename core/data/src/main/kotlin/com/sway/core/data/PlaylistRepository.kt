package com.sway.core.data

import com.sway.core.database.PlaylistDao
import com.sway.core.database.PlaylistSongEntity
import com.sway.core.database.PlaylistWithCount
import com.sway.core.database.SongEntity
import com.sway.core.model.ArtworkRef
import com.sway.core.model.Playlist
import com.sway.core.model.PlaylistId
import com.sway.core.model.Song
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** List-surface row: playlist header + live song count (FR-31). */
data class PlaylistSummary(val playlist: Playlist, val songCount: Int)

/**
 * Owned-playlists boundary (story 8.2, FR-31/32 persistence substrate;
 * surfaces complete E11). The ONLY consumer of [PlaylistDao].
 *
 * Atomicity law (AC1): every mutation is expressed as a complete desired
 * membership ordering and applied through the DAO's single @Transaction
 * rewrite - add/remove/reorder composed together persist atomically and a
 * failure mid-way rolls back fully.
 *
 * Contiguity law (AC4): positions are always rewritten 0..n-1 with no gaps or
 * duplicates; the randomized property suite holds this over operation storms.
 *
 * Identity law (AR-8/AR-14): ids are namespaced [PlaylistId] values; songs
 * keep multi-membership across playlists; deleting a playlist cascades join
 * rows only, never song snapshots.
 */
class PlaylistRepository(
    internal val dao: PlaylistDao,
    internal val clock: () -> Long = System::currentTimeMillis,
) {

    fun observePlaylists(): Flow<List<PlaylistSummary>> =
        dao.observePlaylists().map { rows -> rows.mapNotNull { it.toSummary() } }

    /** Ordered snapshot songs of one playlist. Absent id = empty emission. */
    fun observeSongs(playlistId: PlaylistId): Flow<List<Song>> =
        dao.observeSongs(playlistId.value).map { rows -> rows.mapNotNull { it.toSong() } }

    /** Creates a playlist; duplicate names allowed by design (FR-31). */
    suspend fun create(rawName: String): SwayResult<PlaylistId> {
        val playlist = Playlist.createNew(rawName)
            ?: return SwayResult.Failure(SwayError.Parse("blank playlist name"))
        return storageGuarded {
            val now = clock()
            dao.insertPlaylist(
                com.sway.core.database.PlaylistEntity(
                    playlistId = playlist.id.value,
                    name = playlist.name,
                    rawName = playlist.rawName,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            playlist.id
        }
    }

    suspend fun rename(id: PlaylistId, rawName: String): SwayResult<Unit> {
        val updated = Playlist.createTyped(id, rawName)
            ?: return SwayResult.Failure(SwayError.Parse("blank playlist name"))
        return storageGuarded {
            dao.rename(id.value, updated.name, updated.rawName, clock())
        }
    }

    /** Delete cascades join rows only - song snapshots survive (retention law). */
    suspend fun delete(id: PlaylistId): SwayResult<Unit> = storageGuarded {
        dao.delete(id.value)
    }

    /** Append one song at the tail (snapshot-first so the join FK resolves). */
    suspend fun addSong(playlistId: PlaylistId, song: Song): SwayResult<Unit> = storageGuarded {
        ensureSnapshot(song)
        val current = dao.songsNow(playlistId.value).mapNotNull { it.toSong() }
        rewrite(playlistId, current + song)
    }

    /** Remove one membership; remaining positions re-contiguous immediately. */
    suspend fun removeSong(playlistId: PlaylistId, sourceId: String): SwayResult<Unit> = storageGuarded {
        val current = dao.songsNow(playlistId.value).mapNotNull { it.toSong() }
        rewrite(playlistId, current.filterNot { it.id.value == sourceId })
    }

    /** Reorder to the caller's exact ordering; must permute current membership. */
    suspend fun reorder(
        playlistId: PlaylistId,
        orderedSourceIds: List<String>,
    ): SwayResult<Unit> {
        val current = dao.songsNow(playlistId.value).mapNotNull { it.toSong() }
        val byId = current.associateBy { it.id.value }
        val valid = orderedSourceIds.size == byId.size && orderedSourceIds.all { it in byId }
        if (!valid) {
            return SwayResult.Failure(SwayError.Parse("reorder payload must permute current membership"))
        }
        return storageGuarded {
            rewrite(playlistId, orderedSourceIds.mapNotNull { byId[it] })
        }
    }

    // --- internals -------------------------------------------------------------

    /** Snapshot-first insert so the join FK always resolves (offline-complete, UJ-3). */
    internal suspend fun ensureSnapshot(song: Song) {
        dao.upsertSnapshot(song.toEntity())
    }

    private suspend fun rewrite(playlistId: PlaylistId, songs: List<Song>) {
        val now = clock()
        touchPlaylist(playlistId.value, now)
        dao.rewriteMembership(
            playlistId.value,
            songs.mapIndexed { index, s ->
                PlaylistSongEntity(
                    playlistId = playlistId.value,
                    songId = s.id.value,
                    position = index,
                    addedAt = now,
                )
            },
        )
    }

    private suspend fun touchPlaylist(id: String, now: Long) {
        val existing = dao.byId(id) ?: throw IllegalStateException("playlist missing")
        dao.rename(id, existing.name, existing.rawName, now)
    }
}

// --- story 8.2 test seams ------------------------------------------------------

/**
 * Test-only: appends an id WITHOUT inserting its snapshot first, so the
 * join-row insert violates the songId FK INSIDE the transaction - proving the
 * atomic-rollback law (membership untouched afterwards, typed Storage failure
 * returned). Production always snapshots first via [PlaylistRepository.addSong].
 */
internal suspend fun PlaylistRepository.appendWithoutSnapshotForTest(
    playlistId: PlaylistId,
    sourceId: String,
): SwayResult<Unit> = storageGuarded {
    val current = dao.songsNow(playlistId.value).mapNotNull { it.toSong() }
    val ghost = Song.create(id = sourceId, rawTitle = "ghost", durationMs = 1_000L)!!
    val existing = dao.byId(playlistId.value) ?: throw IllegalStateException("playlist missing")
    dao.rename(playlistId.value, existing.name, existing.rawName, clock())
    dao.rewriteMembership(
        playlistId.value,
        (current + ghost).mapIndexed { index, s ->
            PlaylistSongEntity(
                playlistId = playlistId.value,
                songId = s.id.value,
                position = index,
                addedAt = clock(),
            )
        },
    )
}

internal suspend fun PlaylistRepository.ensureSnapshotPublicForTest(song: Song): Unit =
    ensureSnapshot(song)

// --- row/value mappers ---------------------------------------------------------

private fun PlaylistWithCount.toSummary(): PlaylistSummary? =
    Playlist.createTyped(PlaylistId.parse(playlist.playlistId) ?: return null, playlist.rawName)
        ?.let { PlaylistSummary(it, songCount) }

private fun SongEntity.toSong(): Song? =
    Song.create(
        id = sourceId,
        rawTitle = rawTitle,
        artistName = artistName,
        durationMs = durationMs,
        artwork = artworkUrl?.let { ArtworkRef.of(it) },
    )

private fun Song.toEntity(): SongEntity = SongEntity(
    sourceId = id.value,
    title = title,
    rawTitle = rawTitle,
    artistName = artistName,
    albumName = null,
    durationMs = duration.millis,
    artworkUrl = artwork?.canonicalUrl,
    likedAt = null,
)
