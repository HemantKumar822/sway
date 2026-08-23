package com.sway.music.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Story 6.3 — exhaustive truth table of the explain-first law (AC5/AC6):
 *
 * REQUEST_SYSTEM_DIALOG is reachable ONLY when the platform supports the
 * runtime ask (API >= 33), the permission is not yet granted, AND the
 * rationale copy has been acknowledged. Any state that would let the system
 * dialog precede the rationale is unrepresentable by construction.
 */
class NotificationPermissionGateTest {

    // --- API < 33: no runtime notification permission exists ---------------

    @Test
    fun belowRequiredApi_alwaysNothingToDo_regardlessOfOtherState() {
        for (granted in listOf(true, false)) {
            for (rationale in listOf(true, false)) {
                assertEquals(
                    "api=32 granted=$granted rationale=$rationale",
                    PermissionAction.NOTHING_TO_DO,
                    NotificationPermissionGate.nextAction(
                        apiLevel = NotificationPermissionGate.REQUIRED_API - 1,
                        granted = granted,
                        rationaleAcknowledged = rationale,
                    ),
                )
            }
        }
    }

    // --- already granted: zero friction ------------------------------------

    @Test
    fun alreadyGranted_atOrAboveApi_nothingToDo_regardlessOfRationale() {
        for (rationale in listOf(true, false)) {
            assertEquals(
                PermissionAction.NOTHING_TO_DO,
                NotificationPermissionGate.nextAction(
                    apiLevel = NotificationPermissionGate.REQUIRED_API,
                    granted = true,
                    rationaleAcknowledged = rationale,
                ),
            )
            assertEquals(
                PermissionAction.NOTHING_TO_DO,
                NotificationPermissionGate.nextAction(
                    apiLevel = 100,
                    granted = true,
                    rationaleAcknowledged = rationale,
                ),
            )
        }
    }

    // --- ungranted on API 33+: the explain-first fork ----------------------

    @Test
    fun ungranted_rationaleNotYetAcknowledged_showRationaleFirst() {
        assertEquals(
            PermissionAction.SHOW_RATIONALE_THEN_REQUEST,
            NotificationPermissionGate.nextAction(
                apiLevel = NotificationPermissionGate.REQUIRED_API,
                granted = false,
                rationaleAcknowledged = false,
            ),
        )
        assertEquals(
            PermissionAction.SHOW_RATIONALE_THEN_REQUEST,
            NotificationPermissionGate.nextAction(
                apiLevel = Int.MAX_VALUE,
                granted = false,
                rationaleAcknowledged = false,
            ),
        )
    }

    @Test
    fun ungranted_rationaleAcknowledged_thenSystemDialog() {
        assertEquals(
            PermissionAction.REQUEST_SYSTEM_DIALOG,
            NotificationPermissionGate.nextAction(
                apiLevel = NotificationPermissionGate.REQUIRED_API,
                granted = false,
                rationaleAcknowledged = true,
            ),
        )
    }

    /**
     * The LAW itself: across every representable state, a system-dialog action
     * implies rationale acknowledged AND not granted AND API support. This is
     * the mechanical form of "rationale copy precedes the system dialog".
     */
    @Test
    fun law_systemDialogNeverPrecedesRationale_exhaustive() {
        for (api in intArrayOf(26, 32, 33, 34, 36)) {
            for (granted in listOf(true, false)) {
                for (rationale in listOf(true, false)) {
                    val action = NotificationPermissionGate.nextAction(api, granted, rationale)
                    if (action == PermissionAction.REQUEST_SYSTEM_DIALOG) {
                        check(api >= NotificationPermissionGate.REQUIRED_API) { "api" }
                        check(!granted) { "granted" }
                        check(rationale) { "rationale" }
                    }
                }
            }
        }
    }
}
