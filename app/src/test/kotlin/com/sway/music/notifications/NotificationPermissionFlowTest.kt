package com.sway.music.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.music.MainActivity
import com.sway.music.SwayApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Story 6.3 — hermetic flow proof of explain-first at LAUNCH time (AC7):
 *
 * On a fresh Android-13+ launch with POST_NOTIFICATIONS ungranted and no
 * rationale ever shown, MainActivity must NOT fire the system permission
 * request — the gate routes to SHOW_RATIONALE_THEN_REQUEST and only the
 * user's acknowledgment (dialog Continue) reaches the system dialog.
 * Together with the exhaustive gate table
 * ([NotificationPermissionGateTest]) this mechanically enforces "rationale
 * copy precedes the system dialog".
 */
@RunWith(AndroidJUnit4::class)
@Config(application = SwayApplication::class, sdk = [36])
class NotificationPermissionFlowTest {

    @Test
    fun freshLaunch_ungranted_noSystemRequestBeforeRationale() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Let first composition + LaunchedEffect run their course.
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idle()

            scenario.onActivity { activity ->
                assertEquals(
                    "Test premise: POST_NOTIFICATIONS must be ungranted under Robolectric",
                    PackageManager.PERMISSION_DENIED,
                    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
                )
                assertNull(
                    "No system permission request may precede rationale acknowledgment " +
                        "(explain-first law, FR-21 substrate)",
                    shadowOf(activity).lastRequestedPermission,
                )
            }
        }
    }
}
