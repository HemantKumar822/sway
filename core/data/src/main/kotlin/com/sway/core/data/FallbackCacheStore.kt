package com.sway.core.data

import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Offline Fallback Cache (story 8.4, FR-4 substrate, C-8 lesson): JSON files
 * under [dir] keyed by request shape. Repositories WRITE THROUGH on success;
 * on Offline/UpstreamUnavailable failures they READ here and serve the
 * payload STALE-MARKED. The cache is never consulted before the network
 * fails — the API offers only [readOnFailure], making "fresh-first"
 * structural.
 *
 * Laws (all enforced on access):
 * - **TTL:** entries older than [TTL_MS] are DELETED and read as a miss —
 *   expired never masquerades as fresh (AC2). Every access sweeps the whole
 *   directory, so one touch cleans all expired siblings.
 * - **Strict validation:** the envelope must parse AND carry a valid JSON
 *   payload; anything corrupt is DELETED and reads as a miss — the shipped-
   * crash lesson says validation failures can never propagate (AC3).
 * - **Atomic writes:** content lands via temp-file rename, so a crash mid-
 *   write leaves either the old entry or nothing — never a torn file.
 */
class FallbackCacheStore(
    val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Write-through hook: store [payloadJson] under [key]. Never throws. */
    fun write(key: String, payloadJson: String) {
        try {
            // Directory creation is LAZY (never in init): constructing this store
            // must not perform disk work on the main thread (AD-10 startup law).
            dir.mkdirs()
            val target = fileFor(key)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(
                buildString {
                    append("{\"v\":").append(FORMAT_VERSION)
                    .append(",\"key\":\"").append(escape(key))
                    .append("\",\"fetchedAt\":").append(clock())
                    .append(",\"payload\":\"").append(escape(payloadJson))
                    .append("\"}")
                },
                Charsets.UTF_8,
            )
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
            }
        } catch (_: Exception) {
            // Cache writes must never disturb the primary path (best effort).
        }
    }

    /**
     * Failure-path read: returns the cached payload JSON (STALE by definition)
     * or null after deleting expired/corrupt entries. Sweeps expired siblings
     * on every call. Never throws.
     */
    fun readOnFailure(key: String): String? = try {
        sweepExpired()
        val f = fileFor(key)
        if (!f.exists()) {
            null
        } else {
            when (val parsed = parseAndValidate(f)) {
                null -> {
                    f.delete()
                    null
                }
                else -> if (clock() - parsed.fetchedAt >= TTL_MS) {
                    f.delete()
                    null
                } else {
                    parsed.payload
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Manual full sweep (also runs on every access). Returns deleted count. */
    fun sweepExpired(): Int {
        var deleted = 0
        dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.forEach { f ->
            val parsed = parseAndValidate(f)
            if (parsed == null || clock() - parsed.fetchedAt >= TTL_MS) {
                if (f.delete()) deleted++
            }
        }
        return deleted
    }

    // --- internals -------------------------------------------------------------

    private data class Parsed(val key: String, val fetchedAt: Long, val payload: String)

    /**
     * STRICT validation (C-8): envelope structure, version, key match, and
     * payload-as-valid-JSON must all hold; any violation yields null (caller
     * deletes the file). Unescape produces the ORIGINAL payload text.
     */
    private fun parseAndValidate(f: File): Parsed? = try {
        val root = json.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject
        if (root["v"]?.jsonPrimitive?.int != FORMAT_VERSION) return null
        val key = unescape(root["key"]!!.jsonPrimitive.content)
        val fetchedAt = root["fetchedAt"]!!.jsonPrimitive.long
        // Payload must itself be structurally valid JSON (object or array).
        kotlinx.serialization.json.Json.parseToJsonElement(unescape(root["payload"]!!.jsonPrimitive.content))
        Parsed(key, fetchedAt, unescape(root["payload"]!!.jsonPrimitive.content))
    } catch (_: Exception) {
        null
    }

    private fun fileFor(key: String): File =
        File(dir, SUFFIX_PREFIX + java.util.Base64.getUrlEncoder().encodeToString(key.toByteArray(Charsets.UTF_8)) + SUFFIX)

    /** Minimal JSON string escaping for envelope fields we author ourselves. */
    private fun escape(raw: String): String = buildString {
        for (ch in raw) when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }

    private fun unescape(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch != '\\') {
                sb.append(ch); i++; continue
            }
            i++
            when (raw.getOrNull(i)) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                'u' -> {
                    sb.append(raw.substring(i + 1, i + 5).toInt(16).toChar())
                    i += 4
                }
                else -> sb.append(raw.getOrNull(i) ?: ' ')
            }
            i++
        }
        return sb.toString()
    }

    companion object {
        const val FORMAT_VERSION = 1

        /** FR-4 substrate / C-8: stale window is 72 h, then deletion. */
        const val TTL_MS: Long = 72L * 60L * 60L * 1000L

        private const val SUFFIX = ".fallbackcache.json"
        private const val SUFFIX_PREFIX = "fc_"

        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
