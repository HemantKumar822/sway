package com.sway.playback

import android.net.Uri
import com.sway.core.model.SourceId

/**
 * Placeholder URI scheme (sway pending) — AD-6 rule 6, AR-5.
 *
 * **Single-owner law:** `sway://pending/<sourceId>` is defined in exactly ONE
 * object ([PendingUri]) in `:playback`, and this object is `internal` — only
 * `:playback` code may construct, mutate, or string-sniff placeholders. Any
 * other module attempting to reference the scheme or this type cannot compile
 * (internal visibility), and any stray `sway://` / `PendingUri` occurrence in
 * tracked code files outside the playback module sources fails the repo grep audit
 * (`scripts/check_placeholder_scheme.sh`, wired as a CI step beside the
 * module edge audit). Resolution state is owned service-side; queue entries
 * are Source-ID placeholders until just-in-time resolve (FR-12).
 */
internal object PendingUri {

    private const val SCHEME = "sway"
    private const val HOST = "pending"

    /** Canonical prefix — for audits only; prefer [build] / [extractSourceId]. */
    const val PREFIX: String = "sway://pending/"

    /**
     * Build a placeholder URI for [id].
     *
     * Example: SourceId("abc123") -> PREFIX + "abc123"
     */
    fun build(id: SourceId): Uri = Uri.parse("$PREFIX${id.value}")

    /** String variant for places that need a raw String (e.g. MediaItem uri). */
    fun buildString(id: SourceId): String = "$PREFIX${id.value}"

    /**
     * True if [uri] is a sway pending placeholder.
     */
    fun isPending(uri: String?): Boolean =
        uri != null && uri.startsWith(PREFIX)

    /** Overload for [Uri]. */
    fun isPending(uri: Uri?): Boolean =
        uri != null && uri.toString().startsWith(PREFIX)

    /**
     * Extract the [SourceId] from a pending URI, or null if not a valid placeholder
     * or the id segment is blank.
     */
    fun extractSourceId(uri: String?): SourceId? {
        if (uri == null) return null
        if (!uri.startsWith(PREFIX)) return null
        val idPart = uri.removePrefix(PREFIX).substringBefore("?").substringBefore("#").trim()
        return SourceId.parse(idPart)
    }

    /** Overload for [Uri]. */
    fun extractSourceId(uri: Uri?): SourceId? = extractSourceId(uri?.toString())
}
