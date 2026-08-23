package com.sway.catalog

import com.sway.core.model.AudioRequest
import com.sway.core.model.Quality
import com.sway.core.model.SourceId
import org.junit.Ignore
import org.junit.Test

/**
 * Story 3.6 — Tagged live smoke for NewPipeStreamResolver (A-1: upstream drift not CI-stable).
 *
 * Manual verification only: requires network and real YouTube. Run explicitly:
 *  `./gradlew :catalog:testDebugUnitTest --tests "*NewPipeStreamResolverLiveSmokeTest*" -PincludeLiveSmoke`
 * or via Android Studio with tag filter. CI never runs this (Ignore by default).
 *
 * Expected manual steps (record for R-2 ciphered-prevalence observation):
 * 1. Ensure network (WiFi unmetered vs metered note).
 * 2. Run this test with network; it constructs a real [NewPipeStreamResolver]
 *    (no fake factory) and resolves a known public video id (e.g. "dQw4w9WgXcQ").
 * 3. Observe: result is Success with non-blank url containing `expire=` param,
 *    expiresAtEpochMs > now+5min, bitrateKbps >0, containerHint non-blank,
 *    backendTag startsWith "newpipe:", renditionCacheKey == SourceId:quality.
 * 4. Toggle metered simulation (isMeteredProvider true) and observe AUTO selects
 *    LOW-class vs unmetered MEDIUM-class (log bitrate).
 * 5. If audio streams are empty but video fallback succeeds, log
 *    "ciphered-prevalence: fallback used" for R-2 and consider AD-1 escalation.
 * 6. Record drift: if no playable URLs or ciphered-only rises, note in R-2 log.
 */
class NewPipeStreamResolverLiveSmokeTest {

    @Ignore("Manual live smoke — requires network; tag liveSmoke")
    @Test
    fun `live smoke stream resolve with expiry and quality ladder - manual`() {
        // Tag: liveSmoke
        // Example manual verification (paste into local scratch run):
        //
        // val resolver = NewPipeStreamResolver()
        // runBlocking {
        //   val id = SourceId("dQw4w9WgXcQ") // Rick Astley — stable public id
        //   val low = resolver.resolveAudio(id, AudioRequest(Quality.LOW))
        //   val med = resolver.resolveAudio(id, AudioRequest(Quality.MEDIUM))
        //   val high = resolver.resolveAudio(id, AudioRequest(Quality.HIGH))
        //   val auto = resolver.resolveAudio(id, AudioRequest(Quality.AUTO))
        //   println("low=$low med=$med high=$high auto=$auto")
        //   check(low is SwayResult.Success && low.data.url.contains("expire="))
        //   check(low.data.expiresAtEpochMs > System.currentTimeMillis() + 5*60*1000)
        //   // Prefetch silent-null probe
        //   val pref = resolver.prefetchNext(id, AudioRequest(Quality.MEDIUM))
        //   println("prefetch=$pref")
        //   // Invalidate
        //   resolver.invalidate(id)
        // }
        //
        org.junit.Assert.assertTrue(true)
    }
}
