package com.sway.core.model

/**
 * Audio quality preference — AD-7, AR-6, FR-15.
 *
 * Bitrate-target selection over returned streams (AD-7):
 * - Best stream whose average bitrate is under target, else overall max.
 * - AUTO adapts to metered/unmetered (unmetered → MEDIUM-class, metered → LOW-class);
 *   actual mapping lives in `:catalog`'s selector, this enum is just the vocabulary.
 *
 * Lives in `:core:model` so `:core:data` (SettingsRepository), `:catalog`
 * (NewPipeStreamResolver), and `:playback` all consume the same declaration
 * (AD-7: local re-declarations banned).
 *
 * Pure Kotlin — zero Android imports.
 */
enum class Quality {
    /** Adapt to network metering (WiFi → MEDIUM, metered → LOW per L6/C-6). */
    AUTO,

    /** Low bitrate target. */
    LOW,

    /** Medium bitrate target. */
    MEDIUM,

    /** High bitrate target (best available under target, else max). */
    HIGH,
}
