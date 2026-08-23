package com.sway.core.model

/**
 * Title sanitization helper (AR-14, story 2.1 AC: rawTitle preserved alongside cleaned title).
 *
 * Display title is derived from rawTitle by trimming and collapsing internal whitespace.
 * The raw value is always preserved for diagnostics / future re-sanitization.
 */
internal object TitleSanitization {
    private val whitespaceRegex = Regex("\\s+")

    /** Returns a cleaned display title; if [raw] is blank, returns empty string (caller decides fallback). */
    fun sanitize(raw: String): String {
        // trim outer, collapse inner sequences to single space
        return raw.trim().replace(whitespaceRegex, " ")
    }
}

/**
 * Public helper for callers that need display-title logic outside factories (kept pure).
 */
fun sanitizeTitle(rawTitle: String): String = TitleSanitization.sanitize(rawTitle)
