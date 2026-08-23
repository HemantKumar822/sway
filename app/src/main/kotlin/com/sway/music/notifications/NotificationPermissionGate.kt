package com.sway.music.notifications

/**
 * Story 6.3 — the explain-first POST_NOTIFICATIONS decision law (FR-21
 * substrate / story AC5–AC7).
 *
 * Mechanical law: the system permission dialog is NEVER reachable before the
 * rationale copy has been acknowledged ([PermissionAction.REQUEST_SYSTEM_DIALOG]
 * requires [nextAction]'s `rationaleAcknowledged = true`). Below API 33 the
 * runtime permission does not exist and an already-granted state asks nothing
 * — zero-friction paths return [PermissionAction.NOTHING_TO_DO].
 *
 * Pure decision, no Android framework imports: exhaustively table-tested in
 * NotificationPermissionGateTest; MainActivity only executes the returned
 * action (rationale dialog -> system request).
 */
enum class PermissionAction {
    /** Show rationale copy first; only after acknowledgment may the system dialog launch. */
    SHOW_RATIONALE_THEN_REQUEST,

    /** Rationale already acknowledged — safe to launch the system dialog. */
    REQUEST_SYSTEM_DIALOG,

    /** Granted already, or platform needs no runtime ask — do nothing. */
    NOTHING_TO_DO,
}

object NotificationPermissionGate {

    /** POST_NOTIFICATIONS exists from Android 13 (API 33) onward. */
    const val REQUIRED_API: Int = 33

    /**
     * Single decision point of the explain-first flow.
     *
     * @param apiLevel current [android.os.Build.VERSION.SDK_INT]
     * @param granted whether POST_NOTIFICATIONS is currently granted
     * @param rationaleAcknowledged whether the user has seen/acted on the rationale copy
     */
    fun nextAction(
        apiLevel: Int,
        granted: Boolean,
        rationaleAcknowledged: Boolean,
    ): PermissionAction = when {
        apiLevel < REQUIRED_API || granted -> PermissionAction.NOTHING_TO_DO
        rationaleAcknowledged -> PermissionAction.REQUEST_SYSTEM_DIALOG
        else -> PermissionAction.SHOW_RATIONALE_THEN_REQUEST
    }
}
