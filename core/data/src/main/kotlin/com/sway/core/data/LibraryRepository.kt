package com.sway.core.data

import com.sway.core.database.LibraryDao
import com.sway.core.database.SongEntity
import com.sway.core.model.ArtworkRef
import com.sway.core.model.Song
import com.sway.core.model.SourceId
import com.sway.core.model.SwayError
import com.sway.core.model.SwayResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Likes boundary over the library tables (story 8.1, FR-30 persistence
 * substrate; cross-surface SYNC completes in E12). The ONLY consumer of
 * [LibraryDao]; surfaces consume plain models + typed results (NFR-2).
 *
 * Failure law: storage failures surface as [SwayError.Storage] — never an
 * empty list masquerading as success. The observe flow is Room-backed and
 * emits the ordered liked list; command results are [SwayResult] so callers
 * render honest states.
 */
class LibraryRepository(
    private val dao: LibraryDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Liked songs, most-recently-liked first (FR-33 order law). */
    fun observeLiked(): Flow<List<Song>> = dao.likedSongs().map { rows -> rows.mapNotNull { it.toSong() } }

    /** Point-in-time snapshot with typed storage failures (NFR-2). */
    suspend fun likedSnapshot(): SwayResult<List<Song>> = guarded {
        dao.likedSongsNow().mapNotNull { it.toSong() }
    }

    /**
     * Like [song]: insert-or-update preserving any existing snapshot fields we
     * re-derive from the model anyway (single source: the caller's Song truth).
     */
    suspend fun setLiked(song: Song): SwayResult<Unit> = guarded {
        dao.upsert(song.toEntity(likedAt = clock()))
    }

    /** Remove the like marker; the snapshot row itself is retained (AD-8 retention). */
    suspend fun clearLiked(sourceId: SourceId): SwayResult<Unit> = guarded {
        val existing = dao.byId(sourceId.value) ?: return@guarded Unit
        dao.upsert(existing.copy(likedAt = null))
    }

    /** Batch probe for sync surfaces: which of [ids] are currently liked. */
    suspend fun likedIdsAmong(ids: List<SourceId>): SwayResult<Set<SourceId>> = guarded {
        if (ids.isEmpty()) {
            emptySet()
        } else {
            dao.likedIdsAmong(ids.map { it.value }).mapNotNull { SourceId.parse(it) }.toSet()
        }
    }

    private inline fun <T> guarded(block: () -> T): SwayResult<T> = storageGuarded(block)
}

/** Row -> model (like state intentionally NOT part of the core:model Song). */
private fun SongEntity.toSong(): Song? =
    Song.create(
        id = sourceId,
        rawTitle = rawTitle,
        artistName = artistName,
        durationMs = durationMs,
        artwork = artworkUrl?.let { ArtworkRef.of(it) },
    )

/** Model -> row for like writes; [likedAt] is the only like-state carrier. */
private fun Song.toEntity(likedAt: Long?): SongEntity = SongEntity(
    sourceId = id.value,
    title = title,
    rawTitle = rawTitle,
    artistName = artistName,
    albumName = null,
    durationMs = duration.millis,
    artworkUrl = artwork?.canonicalUrl,
    likedAt = likedAt,
)