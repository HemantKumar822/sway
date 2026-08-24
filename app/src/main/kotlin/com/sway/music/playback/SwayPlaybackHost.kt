package com.sway.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sway.core.data.SessionRestoreRepository
import com.sway.core.data.SettingsRepository
import com.sway.core.model.QueueItem
import com.sway.core.model.Song
import com.sway.music.screens.detail.PlaybackRequest
import com.sway.playback.PlayerConnection
import com.sway.playback.PlayerUiState
import com.sway.playback.QueueBuilder
import com.sway.playback.SwayPlaybackService
import com.sway.playback.attachSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * App-side playback host (story 12.1): owns the [PlayerConnection] lifecycle,
 * the Mini-bar visibility flag and the cross-surface command surface every
 * player screen talks through.
 *
 * Lifecycle law (AR-9/NFR-1): [start] is called once AFTER first composition —
 * it binds the MediaController, attaches 7.2 settings persistence (shuffle
 * restore + write-through) and the 7.3 session-restore hook (lands PAUSED at
 * the saved moment, never auto-playing). When the controller cannot bind
 * (e.g. Robolectric unit tests), the host degrades honestly to an Idle state
 * flow — surfaces simply never materialize.
 *
 * FR-27 sync: [uiState] is the facade's own StateFlow; emission -> render is
 * one recomposition (<=250 ms budget proven by PlayerSyncLatencyTest).
 *
 * Tap-to-play (FR-8/FR-22 completion, 12.4): [play] maps a screen-emitted
 * [PlaybackRequest] onto [QueueBuilder.fromCollection] and feeds the facade's
 * uniform placeholder path — zero stream resolution happens here (AD-6), and
 * setQueue publishes currentItem synchronously so the Mini Player appears
 * optimistically within one frame of the tap.
 */
class SwayPlaybackHost(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val sessionRestore: SessionRestoreRepository,
) {

    private val connection: PlayerConnection? = run {
        // Environment boundary: Robolectric cannot host the media3 session
        // binder — its shadow calls SessionServiceConnection.onServiceConnected
        // with a NULL component, NPE-ing inside media3 on the looper before any
        // code of ours runs. JVM unit tests disable binding centrally via this
        // property (:app testOptions); devices/CI builds always take the real
        // path (default). Degraded mode = Idle state, the SAME visual law as a
        // failed bind — surfaces simply never materialize (honesty, no stubs).
        val bindingAllowed = System.getProperty("sway.sessionBinding") != "off"
        if (!bindingAllowed) {
            null
        } else {
            try {
                PlayerConnection.create(
                    context = context.applicationContext,
                    serviceComponent = ComponentName(context, SwayPlaybackService::class.java),
                    scope = scope,
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Hoisted playback truth (single source for Mini/Full/Queue surfaces). */
    val uiState: StateFlow<PlayerUiState> = connection?.uiState
        ?: kotlinx.coroutines.flow.MutableStateFlow(PlayerUiState.Idle)

    /**
     * Scrubber/position-bar scoped ticks (AD-6): exactly ONE app-level
     * subscription feeding the Mini hairline AND the Full-Player scrubber.
     */
    fun positionFlow(): Flow<Long> = connection?.positionFlow() ?: emptyFlow()

    /** Swipe-down hides the BAR ONLY — audio persists (FR-16 substrate). */
    var barHidden by mutableStateOf(false)
        private set

    fun hideBar() {
        barHidden = true
    }

    fun showBar() {
        barHidden = false
    }

    /** Post-composition startup hook (call once per Activity). */
    fun start() {
        connection?.connect()
        connection?.attachSettings(settings)
        connection?.attachSessionStore(sessionRestore)
    }

    // --- cross-surface play entry (12.4 wiring matrix) -------------------------

    /**
     * Play a screen-emitted request: ordered collection at [PlaybackRequest
     * .startIndex], then immediate playback intent. Shuffled requests arrive
     * pre-permuted by [com.sway.music.screens.detail.PlaybackRequests] — the
     * order is fed verbatim and the SESSION shuffle flag is NOT touched
     * (toggle semantics belong to the user, not the entry point).
     */
    fun play(request: PlaybackRequest) {
        val conn = connection ?: return
        val built = playbackRequestToBuiltQueue(request) ?: return // honest no-op
        barHidden = false // a new play intent re-materializes the bar
        conn.setQueue(built)
        conn.play()
    }

    // --- transport + queue commands (facade passthroughs) ----------------------

    fun togglePlayPause() {
        val conn = connection ?: return
        if (conn.uiState.value.isPlaying) conn.pause() else conn.play()
    }

    fun next() = connection?.next()

    fun previous() = connection?.previous()

    fun seekTo(positionMs: Long) = connection?.seekTo(positionMs)

    fun jumpTo(index: Int) = connection?.jumpTo(index)

    fun removeAt(index: Int) = connection?.removeAt(index)

    fun moveQueueItem(from: Int, to: Int) = connection?.moveQueueItem(from, to)

    fun clearQueue() = connection?.clearQueue()

    fun setShuffleEnabled(enabled: Boolean) = connection?.setShuffleEnabled(enabled)

    fun cycleRepeatMode() = connection?.cycleRepeatMode()

    fun playNext(song: Song) = connection?.playNext(song)

    fun addToQueue(song: Song) = connection?.addToQueue(song)

    /**
     * Queue-sheet data source (12.3): the facade-owned snapshot in live order;
     * mutations flow back through the commands above and this list refreshes
     * with them (state integrity across auto-transitions).
     */
    fun currentQueue(): List<QueueItem> = connection?.currentQueue().orEmpty()

    fun hasConnection(): Boolean = connection != null
}

/**
 * Pure FR-22 mapping: screen-emitted request -> facade BuiltQueue. Empty items
 * yield null (honest no-op — never empty-as-success); startIndex coerces into
 * bounds. Shuffled requests arrive pre-permuted and are fed verbatim.
 */
internal fun playbackRequestToBuiltQueue(
    request: PlaybackRequest,
): QueueBuilder.BuiltQueue? =
    if (request.items.isEmpty()) {
        null
    } else {
        QueueBuilder.fromCollection(
            request.items,
            request.startIndex.coerceIn(0, request.items.lastIndex),
        )
    }
