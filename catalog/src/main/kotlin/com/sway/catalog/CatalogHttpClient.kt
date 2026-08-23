package com.sway.catalog

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp client factory — AD-3, AR-4.
 *
 * AD-3 law: exactly one HTTP stack (OkHttp). A single singleton is the source of
 * truth; per-profile variants (metadata vs lean artwork) are permitted ONLY as
 * builder derivations so timeouts/proxy/logging stay consistent.
 *
 * This object owns the canonical timeout + proxy configuration. Every downloader or
 * fetcher (NewPipe downloader, future Coil artwork client, direct calls) must derive
 * from [sharedBuilder] or [createShared]; ad-hoc timeout values elsewhere are a violation.
 *
 * AR-14 conventions (sanitized logging, tag consistency) are enforced by callers via [CatalogLog].
 */
object CatalogHttpClient {

    /** Canonical connect timeout — read by instrumentation tests to prove derivation. */
    const val CONNECT_TIMEOUT_SECONDS: Long = 15

    /** Canonical read timeout. */
    const val READ_TIMEOUT_SECONDS: Long = 30

    /** Canonical write timeout. */
    const val WRITE_TIMEOUT_SECONDS: Long = 30

    /**
     * Canonical builder pre-configured with shared timeouts/proxy semantics.
     *
     * Proxy: OkHttp's default [java.net.ProxySelector.getDefault] is retained (no explicit
     * [java.net.Proxy.NO_PROXY] override) so system proxy config flows through.
     * Timeouts derive from the constants above — never inline literals elsewhere.
     */
    fun sharedBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)

    /** Shared singleton client for metadata (NewPipe extractor) — derived from [sharedBuilder]. */
    fun createShared(): OkHttpClient = sharedBuilder().build()

    /**
     * Leaner artwork client derivation — same timeout/proxy base, lighter for image fetches.
     * Not used in story 3.1 but reserved here to document the permitted derivation.
     */
    fun createArtworkVariant(): OkHttpClient = sharedBuilder()
        // Artwork fetches can tolerate shorter read timeouts; kept equal for now to prove
        // derivation rather than a second stack (AD-3: per-profile variants only via same builder).
        .build()
}
