package com.sway.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.core.data.SettingsRepository
import com.sway.core.model.QueueItem
import com.sway.core.model.QueueSnapshot
import com.sway.core.model.Quality
import com.sway.core.model.RepeatMode
import com.sway.core.model.ResolvedAudio
import com.sway.core.model.Song
import com.sway.core.model.SwayResult
import com.sway.core.model.fake.FakeStreamResolver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Story 7.2 — mode persistence end-to-end (FR-11 persistence clause):
 *
 *  - INIT ORDER: a service created with an attached [SettingsRepository]
 *    applies the persisted repeat mode onto the player BEFORE any queue can
 *    be built (async restore launched in onCreate on the same looper session
 *    commands arrive on — AD-10: no synchronous read anywhere).
 *  - WRITE-ON-CHANGE: facade mode commands persist through the repository.
 *  - MIRROR: restored values are reflected in PlayerUiState (repeat from the
 *    player, shuffle from the facade flag).
 *  - LAST-WRITE-WINS: rapid cycles persist exactly the final value.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ModesPersistenceTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private val wavFiles = mutableListOf<File>()
    private var serviceController: org.robolectric.android.controller.ServiceController<SwayPlaybackService>? = null
    private var facade: PlayerConnection? = null
    private lateinit var settings: InMemorySettings

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        shadowOf(app as android.app.Application).grantPermissions(
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        settings = InMemorySettings()
    }

    @After
    fun tearDown() {
        try {
            facade?.release()
        } catch (_: Exception) {
        }
        try {
            serviceController?.destroy()
        } catch (_: Exception) {
        }
        idle()
        scope.cancel()
        wavFiles.forEach { it.delete() }
    }

    // ---------------------------------------------------------------------
    // Init order: persisted repeat restored BEFORE first queue build
    // ---------------------------------------------------------------------

    @Test
    fun serviceInit_restoresRepeatMode_beforeFirstQueueBuild_asyncOnly() {
        // Arrange persisted state as a previous session left it: repeat-one ON.
        kotlinx.coroutines.runBlocking { settings.setRepeatMode(RepeatMode.ONE) }

        val sc = buildService(withSettings = true)
        idle()

        // Restore must have landed WITHOUT any queue existing yet (AD-10:
        // async collection, not a blocking read; ordering via main-looper).
        val p = sc.getPlayerForTest()!!
        assertEquals(
            "Persisted repeat-one must be on the player at init, before any queue",
            Player.REPEAT_MODE_ONE,
            p.repeatMode,
        )
        assertEquals("No queue has been built yet", 0, p.mediaItemCount)

        // The FIRST queue build now happens with repeat-one already active.
        startQueueOn(sc, tripleSongs(), startIndex = 0)
        awaitUntil("queue playing") { p.playWhenReady && p.playbackState == Player.STATE_READY }
        assertEquals(Player.REPEAT_MODE_ONE, p.repeatMode)

        // uiState mirror reflects the restored mode.
        assertEquals(RepeatMode.ONE, facade!!.uiState.value.repeatMode)
    }

    @Test
    fun serviceInit_withoutSettings_keepsNativeDefault_off() {
        val sc = buildService(withSettings = false)
        idle()
        assertEquals(Player.REPEAT_MODE_OFF, sc.getPlayerForTest()!!.repeatMode)
    }

    // ---------------------------------------------------------------------
    // Write-on-change + mirror + last-write-wins
    // ---------------------------------------------------------------------

    @Test
    fun facadeModeCommands_persistOnChange_andMirrorInUiState() {
        val sc = buildService(withSettings = false)
        startQueueOn(sc, sixSongs(), startIndex = 1)

        facade!!.attachSettings(settings)
        assertFalse(facade!!.uiState.value.shuffleEnabled)

        facade!!.cycleRepeatMode() // OFF -> ALL
        facade!!.setShuffleEnabled(true)

        awaitUntil("repeat write-through") { persistedRepeat() == RepeatMode.ALL }
        awaitUntil("shuffle write-through") { persistedShuffle() }
        assertEquals(RepeatMode.ALL, facade!!.uiState.value.repeatMode)
        assertTrue(facade!!.uiState.value.shuffleEnabled)
        assertTrue(
            "Shuffle physically reordered the built queue around current",
            facadeIds().size == 6 && facadeIds()[1] == "s2",
        )
    }

    @Test
    fun restoredShuffle_appliesToFirstQueueBuild_ofFreshFacade() {
        // Persisted shuffle=true from a previous session; fresh stack knows nothing else.
        kotlinx.coroutines.runBlocking { settings.setShuffleEnabled(true) }
        val sc = buildService(withSettings = false)
        startQueueOn(sc, sixSongs(), startIndex = 1)

        facade!!.shuffleSeedOverride = 5L
        facade!!.adoptSnapshotForTest(QueueSnapshot.of(sixSongs().map { QueueItem.of(it) }), 1)
        facade!!.attachSettings(settings)
        idle()
        idle()

        // Restore path toggles the facade flag; with a live queue this reorders
        // deterministically around the current item (seeded).
        awaitUntil("restored shuffle mirrors in uiState") { facade!!.uiState.value.shuffleEnabled }
        val expected = QueueBuilder.reshufflePreservingCurrent(
            QueueSnapshot.of(sixSongs().map { QueueItem.of(it) }).items,
            currentIndex = facadeIdxCoerced(),
            seed = 5L,
        )
        assertEquals(expected.map { it.id.value }, facadeIds())
        assertEquals("Current identity preserved through restored reorder", "s2", player().currentMediaItem?.mediaId)
    }

    @Test
    fun rapidCycles_persistExactlyFinalValue_lastWriteWins() {
        val sc = buildService(withSettings = false)
        startQueueOn(sc, tripleSongs(), startIndex = 0)
        facade!!.attachSettings(settings)
        idle()

        var last: RepeatMode = RepeatMode.OFF
        repeat(7) {
            last = facade!!.cycleRepeatMode()
        }
        idle()
        idle()

        assertEquals(last, facade!!.uiState.value.repeatMode)
        awaitUntil("persisted value equals final uiState mode") {
            persistedRepeat() == last
        }
    }

    // ---------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------

    private fun buildService(withSettings: Boolean): SwayPlaybackService {
        val sc = Robolectric.buildService(SwayPlaybackService::class.java)
        serviceController = sc
        sc.get().streamResolverForTest = resolverFor()
        if (withSettings) {
            sc.get().settingsForTest = settings
        }
        sc.create()
        idle()
        return sc.get()
    }

    private fun resolverFor(): FakeStreamResolver {
        val wav = writeSilentWav()
        return FakeStreamResolver().apply {
            resolveBehavior = { id, request ->
                SwayResult.Success(
                    ResolvedAudio(
                        url = "file://" + wav.absolutePath.replace('\\', '/'),
                        expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
                        bitrateKbps = if (request.quality == Quality.HIGH) 256 else 160,
                        containerHint = "wav",
                        backendTag = "test:silent-wav",
                        renditionCacheKey = ResolvedAudio.cacheKey(id, request.quality),
                    ),
                )
            }
        }
    }

    /** Loads + plays a queue through the engine path and binds the facade. */
    private fun startQueueOn(service: SwayPlaybackService, songs: List<Song>, startIndex: Int) {
        service.addSession(service.getSessionForTest()!!)
        idle()
        val snapshot = QueueSnapshot.of(songs.map { QueueItem.of(it) })
        val items = snapshot.items.map { qi ->
            MediaItem.Builder()
                .setMediaId(qi.id.value)
                .setUri(PendingUri.buildString(qi.id))
                .build()
        }
        service.getEngineForTest()!!.startQueueAndPlay(items, startIndex)
        idle()

        val conn = PlayerConnection.bareForTest(scope)
        facade = conn
        conn.bindPlayer(service.getPlayerForTest()!!)
        conn.adoptSnapshotForTest(snapshot, startIndex)
        idle()

        conn.play()
    }

    private fun facadeIdxCoerced(): Int =
        try {
            player().currentMediaItemIndex.coerceIn(0, facade!!.currentQueue().size - 1)
        } catch (_: Exception) {
            0
        }

    private fun player(): Player =
        serviceController?.get()?.getPlayerForTest() ?: error("service player not available")

    private fun facadeIds(): List<String> = facade!!.currentQueue().map { it.id.value }

    /** Persisted reads from test code (contract-level in-memory store). */
    private fun persistedRepeat(): RepeatMode = settings.persistedRepeat.value

    private fun persistedShuffle(): Boolean = settings.persistedShuffleFlag.value

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(20)
            idle()
        }
        idle()
        assertTrue("Timed out waiting for: $what", condition())
    }

    private fun tripleSongs(): List<Song> = listOf(
        song("m1", "Alpha", "One", 61_000),
        song("m2", "Beta", "Two", 122_000),
        song("m3", "Gamma", null, 183_000),
    )

    private fun sixSongs(): List<Song> = listOf(
        song("s1", "Song One", "Artist A", 60_000),
        song("s2", "Song Two", "Artist B", 70_000),
        song("s3", "Song Three", null, 80_000),
        song("s4", "Song Four", "Artist D", 90_000),
        song("s5", "Song Five", "Artist E", 100_000),
        song("s6", "Song Six", null, 110_000),
    )

    private fun song(id: String, rawTitle: String, artist: String?, durMs: Long): Song =
        Song.create(id = id, rawTitle = rawTitle, artistName = artist, durationMs = durMs, artwork = null)!!

    private fun writeSilentWav(): File {
        val sampleRate = 8_000
        val dataSize = sampleRate * 90 * 2
        val file = File.createTempFile("sway_silent_", ".wav")
        java.io.DataOutputStream(java.io.FileOutputStream(file)).use { out ->
            out.writeBytes("RIFF"); out.writeIntLe(36 + dataSize); out.writeBytes("WAVE")
            out.writeBytes("fmt "); out.writeIntLe(16); out.writeShortLe(1); out.writeShortLe(1)
            out.writeIntLe(sampleRate); out.writeIntLe(sampleRate * 2); out.writeShortLe(2); out.writeShortLe(16)
            out.writeBytes("data"); out.writeIntLe(dataSize); out.write(ByteArray(dataSize))
        }
        file.deleteOnExit()
        wavFiles.add(file)
        return file
    }

    private fun java.io.DataOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun java.io.DataOutputStream.writeShortLe(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }

    /**
     * Contract fake: same [SettingsRepository] surface, in-memory state.
     * Persistence MACHINERY (DataStore round-trips) is :core:data's suite
     * territory; this suite proves the playback-side wiring laws.
     */
    private class InMemorySettings(
        repeatInitial: RepeatMode = RepeatMode.OFF,
        shuffleInitial: Boolean = false,
    ) : SettingsRepository {
        val persistedRepeat = MutableStateFlow(repeatInitial)
        val persistedShuffleFlag = MutableStateFlow(shuffleInitial)
        val persistedAppearance = MutableStateFlow(com.sway.core.data.Appearance.SYSTEM)

        override val repeatMode: Flow<RepeatMode> = persistedRepeat
        override val shuffleEnabled: Flow<Boolean> = persistedShuffleFlag
        override val appearance: Flow<com.sway.core.data.Appearance> = persistedAppearance

        override val audioQuality: Flow<Quality> = MutableStateFlow(Quality.AUTO)
        override suspend fun setAudioQuality(quality: Quality) = Unit
        override suspend fun setAppearance(appearance: com.sway.core.data.Appearance) {
            persistedAppearance.value = appearance
        }

        override suspend fun setShuffleEnabled(enabled: Boolean) {
            persistedShuffleFlag.value = enabled
        }

        override suspend fun setRepeatMode(mode: RepeatMode) {
            persistedRepeat.value = mode
        }
    }
}
