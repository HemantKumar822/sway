package com.sway.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 4.4 — device tap-to-audio timing harness (FR-8 engine level, <= 3 s p95).
 *
 * Manual/device-gated placeholder following the :catalog `LiveSmokeTest`
 * precedent: [Ignore]d by default so `connectedDebugAndroidTest` stays
 * offline-safe and CI never runs it. Robolectric interim numbers are recorded
 * by `FirstAudioTimingHarnessTest`; FR-8 completion evidence lands via 12.4.
 *
 * Expected manual steps (documented for evidence):
 * 1. Attach the Baseline Device profile device/emulator with network access.
 * 2. Remove @Ignore (or run with the fr8TapToAudio tag filter).
 * 3. For each of >= 20 runs: build [SwayPlaybackService] with a real
 *    NewPipeStreamResolver, command play on an 8-item queue at index 2 through
 *    a MediaController, and record wall-clock from command to first
 *    STATE_READY/isPlaying audio output.
 * 4. Assert p95 <= 3_000 ms; record samples in the sprint Evidence log for
 *    story 12.4 completion.
 */
@RunWith(AndroidJUnit4::class)
class LiveTapToAudioSmokeTest {

    @Ignore("Device-only FR-8 harness — requires emulator/device + network; tag fr8TapToAudio")
    @Test
    fun tapToAudio_p95Under3s_deviceOnly() {
        assertTrue(true)
    }
}
