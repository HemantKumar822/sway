package com.sway.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Story 6.3 — instrumented permission-flow UI evidence.
 *
 * DEVICE-GATED harness (story 4.4 LiveTapToAudioSmoke / story 6.1 soak
 * precedent): tag `fr21PermissionFlow`, never runs without a real device or a
 * provisioned emulator image that renders the system permission dialog. The
 * hermetic portion lives in `NotificationPermissionFlowTest` /
 * `NotificationPermissionGateTest` (:app unit tests).
 *
 * Manual flow matrix (feeds R-3 + release checklist, story 15.3 consumes):
 *  - Fresh install on API 33+: launch -> rationale dialog copy visible BEFORE
 *    any system dialog; tap Continue -> system POST_NOTIFICATIONS dialog;
 *    grant -> controls appear on lock screen/notification shade.
 *  - Deny at the system dialog: relaunch shows rationale again (gate law);
 *    second denial ("don't ask again") -> app fully usable, media controls
 *    keep working (platform exemption), no request loops.
 *  - Below API 33 (26/30 devices): no rationale, no request, zero friction.
 */
@RunWith(AndroidJUnit4::class)
@Ignore("Device-gated: run with -Pandroid.testInstrumentationRunnerArguments.class=... on hardware")
class PermissionFlowUiDeviceTest {

    @Test
    fun explainFirstFlow_rationalePrecedesSystemDialog_onApi33Plus() {
        // Implemented when executed against hardware (E6 exit criteria).
        // Skeleton kept compiling-only per story 4.4 precedent.
    }
}
