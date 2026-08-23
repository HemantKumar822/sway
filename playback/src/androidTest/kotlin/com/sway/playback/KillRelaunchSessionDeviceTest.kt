package com.sway.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 7.3 (FR-25) — instrumented kill-and-relaunch suite.
 *
 * DEVICE-GATED harness (4.4 LiveSmoke / 6.1 soak / 6.3 swipe precedent):
 * tag `fr25KillRelaunch`, never runs without a real device. The hermetic,
 * CI-verifiable portion of FR-25 lives in `SessionPersistenceTest`
 * (destroy+recreate over a shared store under Robolectric).
 *
 * Manual device matrix (feeds R-3 + story 15.3 release checklist):
 *  - Start playback (queue >= 5 items), scrub mid-track, background the app.
 *  - `adb shell am kill com.sway.music` (process death, FGS survives kill? on
 *    API 26+ the service may keep running — force-stop variant instead:
 *    `adb shell am force-stop` after pausing, which is the UJ-4 shape).
 *  - Relaunch from launcher: Mini Player marker visible immediately (restored
 *    paused session), position within +/-5 s, shuffle/repeat flags as left.
 *  - Tap play: audio resumes at the restored moment (never restarts, never
 *    auto-played before the tap).
 *  - First-install variant: no session marker, clean empty state.
 *  - Matrix: API 26 / 30 / 33 / 34+ x low-storage (save-failure degradation)
 *    x battery-saver on/off.
 */
@RunWith(AndroidJUnit4::class)
@Ignore("Device-gated: run with -Pandroid.testInstrumentationRunnerArguments.class=... on hardware")
class KillRelaunchSessionDeviceTest {

    @Test
    fun killAndRelaunch_restoresPausedSession_withinTolerance() {
        // Implemented when executed against hardware (E6/E12 exit criteria).
        // Skeleton kept compiling-only per story 4.4 precedent.
    }
}
