package com.sway.core.model

/**
 * Playback repeat-mode vocabulary (FR-11) — canonical value object living in
 * `core:model` (AD-7: no redeclarations; `:playback` maps it to media3's
 * native ints behind its facade, `:core:data` persists its NAME strictly).
 */
enum class RepeatMode {
    /** No looping: end of queue ends playback. */
    OFF,

    /** Loop the whole queue: past the last item wraps to the first. */
    ALL,

    /** Replay the current item indefinitely; prefetch is disabled (4.4 guard). */
    ONE,
}
