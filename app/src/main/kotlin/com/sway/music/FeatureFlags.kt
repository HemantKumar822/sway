package com.sway.music

/**
 * Owner-veto flags for provisional decisions (EP-8).
 *
 * - OQ-6 quality visibility: chip + settings entry behind one flag, default ON pending owner veto.
 *   Flipping to false hides both with zero dead references (story 15.1 AC).
 */
object FeatureFlags {
    const val OQ6_QUALITY_VISIBLE = true
}
