package com.sway.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Story 8.4 — Offline Fallback Cache laws (FR-4 substrate / C-8):
 * fresh entries serve STALE-flagged on failure paths only; 72 h expiry
 * deletes + misses (never masquerades); corrupt entries are validated then
 * deleted with zero crashes; every access sweeps expired siblings; atomic
 * overwrite leaves no residue.
 */
class FallbackCacheStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L

    private fun store(): FallbackCacheStore =
        FallbackCacheStore(tmp.newFolder(), clock = { now })

    /** Deterministic JSON payload builder: entries are "key=value" pairs. */
    private fun payload(vararg entries: String): String =
        entries.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}",
        ) { e ->
            val k = e.substringBefore('=')
            val v = e.substringAfter('=')
            "\"$k\":\"$v\""
        }

    // --- AC1: fresh entry served (stale by definition on failure path) -------

    @Test
    fun freshEntry_readOnFailure_returnsPayload() {
        val s = store()
        val p = payload("query=lofi", "page=0")
        val key = "search:songs:lofi:0"
        s.write(key, p)

        val hit = s.readOnFailure(key)
        assertNotNull(hit)
        assertEquals(p, hit)
        Json.parseToJsonElement(hit!!).jsonObject // structurally valid JSON
    }

    // --- AC2: 72 h expiry deletes + misses ----------------------------------

    @Test
    fun expiredEntry_deletedAndMissed_neverMasquerades() {
        val s = store()
        s.write("k", payload("a=1"))
        now += FallbackCacheStore.TTL_MS + 1   // cross the 72 h line

        assertNull(s.readOnFailure("k"))
        assertTrue(
            "expired file must be deleted from disk",
            s.dir.listFiles().isNullOrEmpty(),
        )
    }

    @Test
    fun sweepOnAccess_cleansExpiredSiblings_ofOtherKeys() {
        val s = store()
        s.write("old1", payload("x=1"))
        s.write("old2", payload("x=2"))
        now += FallbackCacheStore.TTL_MS + 1
        s.write("fresh", payload("x=3"))   // written after the jump: still fresh

        assertEquals(2, s.sweepExpired())
        assertNull(s.readOnFailure("old1"))
        assertNull(s.readOnFailure("old2"))
        assertEquals(payload("x=3"), s.readOnFailure("fresh"))
    }

    // --- AC3: corruption -> validate-fail -> delete -> miss, no crash ---------

    @Test
    fun corruptedEnvelope_deleted_missReturned_noCrash() {
        val s = store()
        s.write("c1", payload("ok=yes"))
        s.dir.listFiles()!!.first().writeText("{{{not json at all")

        assertNull(s.readOnFailure("c1"))
        assertTrue(s.dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun wrongVersion_orInvalidPayloadJson_treatedAsCorrupt_andDeleted() {
        val s = store()
        s.write("v9", payload("ok=yes"))
        val versionFile = s.dir.listFiles()!!.first()
        versionFile.writeText(versionFile.readText().replace("\"v\":1", "\"v\":9"))
        assertNull("wrong envelope version must read as miss", s.readOnFailure("v9"))
        assertFalse(versionFile.exists())

        // Valid envelope, but the PAYLOAD is not valid JSON -> strict validation.
        s.write("p1", payload("ok=yes"))
        val payloadFile = s.dir.listFiles()!!.first()
        val text = payloadFile.readText()
        val start = text.indexOf("\"payload\":\"") + "\"payload\":\"".length
        val end = text.lastIndexOf("\"}")
        payloadFile.writeText(text.substring(0, start) + "NOT\\u0020JSON" + text.substring(end))

        assertNull("invalid payload JSON must validate-fail to a miss", s.readOnFailure("p1"))
        assertFalse(payloadFile.exists())
    }

    // --- AC4/structural: cache never preferred over fresh ---------------------

    @Test
    fun apiSurface_offersOnlyFailurePathReads_noFreshReadExists() {
        val methods = FallbackCacheStore::class.java.declaredMethods.map { it.name }
        assertTrue(
            "the ONLY read API must be readOnFailure (fresh-first structural)",
            methods.none { it == "read" || it == "readFresh" || it.startsWith("readIf") },
        )
        assertTrue(methods.contains("readOnFailure"))
        assertTrue(methods.contains("write"))
    }

    // --- atomic overwrite -------------------------------------------------------

    @Test
    fun overwrite_replacesContent_noTmpResidue() {
        val s = store()
        s.write("k", payload("v=one"))
        s.write("k", payload("v=two"))
        assertEquals(payload("v=two"), s.readOnFailure("k"))
        assertTrue(s.dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
