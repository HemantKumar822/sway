package com.sway.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 6.3 (FR-21) — instrumented real-Recents-swipe continuity evidence.
 *
 * DEVICE-GATED harness (story 4.4 LiveTapToAudioSmoke / story 6.1 soak
 * precedent): tag `fr21SwipeAway`, never runs without a real device. The
 * hermetic, CI-verifiable portion of FR-21 lives in
 * `RecentsSwipeComplianceTest` (onTaskRemoved driven on the full stack).
 *
 * Manual device matrix (execute at E6 exit-criteria time; feeds R-3):
 *  - Start playback via the production UI entry, verify the media notification.
 *  - Open Recents, swipe Sway's task away.
 *  - Assert audio keeps playing; assert the notification remains and its
 *    pause button stops playback (notification = THE stop affordance).
 *  - Repeat paused variant: swipe must dismiss into stopped state (platform
 *    default), no zombie notification afterwards.
 *  - Matrix: API 26 / 30 / 33 / 34+ x OEM skins; on API 33+ also run the
 *    POST_NOTIFICATIONS-DENIED variant (revoke in Settings first): playback +
 *    media notification must keep working per the platform exemption —
 *    record any OEM divergence as R-3 evidence for story 15.3.
 */
@RunWith(AndroidJUnit4::class)
@Ignore("Device-gated: run with -Pandroid.testInstrumentationRunnerArguments.class=... on hardware")
class RecentsSwipeContinuityDeviceTest {

    @Test
    fun recentsSwipeWhilePlaying_audioContinues_notificationIsStopAffordance() {
        // Implemented when executed against hardware (E6 exit criteria).
        // Skeleton kept compiling-only per story 4.4 precedent.
    }
}
