package com.sway.playback

import androidx.media3.common.Player
import com.sway.core.data.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Service-side play recorder (story 8.3, FR-34/A-5, AR-5 rule 7): the ONLY
 * write path into History. A track is recorded once it passes
 * [QUALIFY_MS] of CUMULATIVE audible time within one queue episode —
 * pauses don't reset progress, skips/abandons below the threshold never
 * record, replays upsert (recency refresh happens inside
 * [HistoryRepository.record]).
 *
 * Ticker law: production arms a 1 s loop on the service scope; tests drive
 * [tick] manually with a fake clock, so no wall-clock dependence.
 */
class HistoryRecorder(
    private val player: Player,
    private val scope: CoroutineScope,
    private val repo: HistoryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var ticker: Job? = null

    /** Cumulative audible ms on the CURRENT item this episode. */
    private var accumulatedMs = 0L

    /** mediaId whose episode the accumulator belongs to. */
    private var episodeId: String? = null

    /** Whether THIS episode already recorded (once per track-episode). */
    private var recordedThisEpisode = false

    fun start() {
        if (ticker != null) return
        ticker = scope.launch {
            while (true) {
                delay(TICK_MS)
                tick(clock())
            }
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                newEpisode(mediaItem?.mediaId)
            }
        })
        newEpisode(player.currentMediaItem?.mediaId)
    }

    fun release() {
        ticker?.cancel()
        ticker = null
    }

    /**
     * One sample (production: every TICK_MS; tests: manual). Counts ONLY when
     * audibly playing; records exactly once per episode at >= [QUALIFY_MS].
     */
    internal fun tick(nowMs: Long) {
        val id = player.currentMediaItem?.mediaId
        if (id == null || player.isPlaying.not()) return
        if (id != episodeId) newEpisode(id)
        accumulatedMs += TICK_MS
        if (!recordedThisEpisode && accumulatedMs >= QUALIFY_MS) {
            recordedThisEpisode = true
            scope.launch { repo.record(id) }
        }
    }

    private fun newEpisode(id: String?) {
        episodeId = id
        accumulatedMs = 0L
        recordedThisEpisode = false
    }

    companion object {
        /** FR-34/A-5 recording rule: 10 s cumulative played. P-5-tunable. */
        const val QUALIFY_MS = 10_000L

        /** Production sampling cadence. */
        const val TICK_MS = 1_000L
    }
}
