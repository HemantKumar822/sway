package com.sway.core.model

/**
 * Millisecond-typed duration preventing unit mix-ups at compile time (AR-14).
 *
 * Internally all durations are `ms`; rendering to `m:ss` happens only at the edge via [format].
 * The type is a value class so `DurationMs` and `Long` are not interchangeable.
 */
@JvmInline
value class DurationMs(val millis: Long) {
    init {
        require(millis >= 0) { "DurationMs must be non-negative (got $millis)" }
    }

    val isPositive: Boolean get() = millis > 0
    val isZero: Boolean get() = millis == 0L

    /** Seconds truncated. */
    val seconds: Long get() = millis / 1_000L

    /** Render `m:ss` at the UI edge (AR-14: durations ms internally, m:ss at edge). */
    fun format(): String {
        val totalSeconds = millis / 1_000L
        val minutes = totalSeconds / 60
        val secondsRemainder = totalSeconds % 60
        return "$minutes:${secondsRemainder.toString().padStart(2, '0')}"
    }

    operator fun plus(other: DurationMs): DurationMs = DurationMs(millis + other.millis)
    operator fun minus(other: DurationMs): DurationMs {
        val diff = millis - other.millis
        return DurationMs(if (diff < 0) 0 else diff)
    }

    companion object {
        val ZERO: DurationMs = DurationMs(0L)

        /** Parses a nullable [Long] ms value; returns `null` if null or negative. */
        fun parseOrNull(millis: Long?): DurationMs? {
            if (millis == null) return null
            if (millis < 0) return null
            return DurationMs(millis)
        }

        /** Clamps negative inputs to [ZERO] — useful for lenient mappers that must not fail on bad duration. */
        fun clamp(millis: Long): DurationMs = if (millis < 0) ZERO else DurationMs(millis)
    }
}

/** Convenience: treat a raw [Long] ms value as [DurationMs], clamping negatives to zero. */
fun Long.toDurationMs(): DurationMs = DurationMs.clamp(this)

/** Convenience for nullable. */
fun Long?.toDurationMsOrNull(): DurationMs? = if (this == null) null else DurationMs.parseOrNull(this)
