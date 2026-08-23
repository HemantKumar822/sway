package com.sway.core.data

import com.sway.core.database.QueueStateDao
import com.sway.core.database.QueueStateEntity
import java.io.IOException

/**
 * Playback-session persistence boundary (story 7.3, FR-25): the ONLY consumer
 * of [QueueStateDao] and the only caller of [QueueStateSerializer] — glue in
 * one place, so `:playback` and future surfaces consume plain values
 * ([RestoredSession]) and never touch Room or JSON (AD-8/AR-9 layering).
 *
 * Failure laws: a failed save degrades silently (a full disk must never crash
 * playback; the next successful save supersedes); a failed/absent load yields
 * `null` = "no saved session" (first-run law). Corrupt rows degrade inside
 * [QueueStateSerializer.fromJson].
 */
class SessionRestoreRepository(
    private val store: QueueStateStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun save(session: QueueStateSerializer.RestoredSession) {
        try {
            store.save(
                StoredQueueState(
                    songsJson = QueueStateSerializer.toJson(session),
                    currentIndex = session.currentIndex,
                    positionMs = session.positionMs,
                    shuffleEnabled = session.shuffleEnabled,
                    repeatMode = session.repeatMode.name,
                    savedAt = clock(),
                ),
            )
        } catch (_: IOException) {
            // Degrade: keep playing; the next successful save supersedes.
        }
    }

    suspend fun loadRestoredSession(): QueueStateSerializer.RestoredSession? {
        val row = try {
            store.loadOnce()
        } catch (_: IOException) {
            return null
        } ?: return null
        return QueueStateSerializer.fromJson(row.songsJson)
    }

    suspend fun clear() {
        try {
            store.clear()
        } catch (_: IOException) {
        }
    }
}
