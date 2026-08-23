package com.sway.core.model.fake

import com.sway.core.model.AudioRequest
import com.sway.core.model.Quality
import com.sway.core.model.ResolvedAudio
import com.sway.core.model.SourceId
import com.sway.core.model.StreamResolver
import com.sway.core.model.SwayResult
import com.sway.core.model.SwayError

/**
 * Test/fake implementation of [StreamResolver] — story 2.4 compile-time contract.
 *
 * Counts [resolveAudio] invocations for FR-12 verification (exactly one up-front
 * resolve per queue, transitions just-in-time) and obeys the dedup-irrelevant
 * visible contract. [prefetchNext] returns `null` silently on failure (AD-7).
 *
 * Pure Kotlin — zero Android imports.
 */
class FakeStreamResolver(
    var resolveBehavior: suspend (SourceId, AudioRequest) -> SwayResult<ResolvedAudio> =
        { id, req -> SwayResult.Success(fakeResolvedAudio(id, req.quality)) },
    var prefetchBehavior: suspend (SourceId, AudioRequest) -> ResolvedAudio? = { _, _ -> null },
) : StreamResolver {

    var resolveCount: Int = 0
        private set

    var invalidateCount: Int = 0
        private set

    val resolvedIds: MutableList<SourceId> = mutableListOf()
    val invalidatedIds: MutableList<SourceId> = mutableListOf()
    val prefetchedIds: MutableList<SourceId> = mutableListOf()

    override suspend fun resolveAudio(trackId: SourceId, request: AudioRequest): SwayResult<ResolvedAudio> {
        resolveCount++
        resolvedIds += trackId
        return resolveBehavior(trackId, request)
    }

    override fun invalidate(trackId: SourceId) {
        invalidateCount++
        invalidatedIds += trackId
    }

    override suspend fun prefetchNext(trackId: SourceId, request: AudioRequest): ResolvedAudio? {
        prefetchedIds += trackId
        return try {
            prefetchBehavior(trackId, request)
        } catch (_: Throwable) {
            null // silent-null contract even if fake throws
        }
    }

    /** Reset counters between test phases. */
    fun resetCounts() {
        resolveCount = 0
        invalidateCount = 0
        resolvedIds.clear()
        invalidatedIds.clear()
        prefetchedIds.clear()
    }

    /** Inject a global failure for subsequent resolves. */
    fun injectResolveFailure(error: SwayError) {
        resolveBehavior = { _, _ -> SwayResult.Failure(error) }
    }

    companion object {
        /** Deterministic fake [ResolvedAudio] for a given [id] + [quality]. */
        fun fakeResolvedAudio(id: SourceId, quality: Quality = Quality.AUTO): ResolvedAudio =
            ResolvedAudio(
                url = "https://cdn.example.com/audio/${id.value}?expire=${System.currentTimeMillis() / 1000 + 3600}&quality=${quality.name}",
                expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
                bitrateKbps = when (quality) {
                    Quality.LOW -> 96
                    Quality.MEDIUM -> 160
                    Quality.HIGH -> 256
                    Quality.AUTO -> 160
                },
                containerHint = "mp4",
                backendTag = "fake:progressive:mp4",
                renditionCacheKey = ResolvedAudio.cacheKey(id, quality),
            )
    }
}
