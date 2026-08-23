package com.sway.catalog

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

/**
 * NewPipe [Downloader] backed by the shared OkHttp stack — AD-3, AR-4, AR-2.
 *
 * AD-1 isolation: this is the ONLY place that touches `org.schabi.newpipe` downloader types
 * outside recorded fixtures; the class lives in `:catalog` exclusively.
 *
 * AD-3 single-stack: requests flow exclusively through the injected [client] which MUST be
 * derived from [CatalogHttpClient.sharedBuilder] so timeouts/proxy config are not ad-hoc.
 * The default constructor derives correctly for production; tests inject a MockWebServer-bound client.
 *
 * AR-14 request/response logging: method + truncated URL + response code + latency are logged
 * via [CatalogLog]; full bodies, query values beyond truncation, and stack traces are never
 * logged to UI. The extractor's own `ReCaptchaException` for HTTP 429 is preserved so callers
 * can map to `SwayError.RateLimited` without swallow-and-empty (AD-9).
 */
class SwayDownloaderImpl(
    private val client: OkHttpClient = CatalogHttpClient.createShared(),
) : Downloader() {

    /**
     * Executes a NewPipe [ExtractorRequest] via OkHttp.
     *
     * Mirrors the reference's hardening:
     * - Handles GET/POST/HEAD with optional [ExtractorRequest.dataToSend] bodies (empty POST body for POST without data).
     * - Propagates caller-supplied headers verbatim; injects a modern User-Agent and Accept-Language only when absent.
     * - Limits response bodies to 10 MB (metadata defense; avoids OOM on mis-routed stream URLs) — oversized returns empty string.
     * - Throws [ReCaptchaException] on HTTP 429 so the resolver/search mappers can surface `RateLimited`.
     */
    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: ExtractorRequest): Response {
        val method = request.httpMethod() ?: "GET"
        val url = request.url() ?: throw IOException("Extractor Request missing URL")
        val headers = request.headers() ?: emptyMap()
        val bodyBytes = request.dataToSend()

        val okhttpRequestBuilder = okhttp3.Request.Builder()
            .url(url)
            .method(
                method,
                when {
                    bodyBytes != null -> bodyBytes.toRequestBody(null)
                    method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
                    else -> null
                },
            )

        // Propagate headers from extractor (multi-value aware)
        headers.forEach { (key, values) ->
            values.forEach { value ->
                okhttpRequestBuilder.addHeader(key, value)
            }
        }

        // User-Agent: extractor fixtures rarely set one; provide a safe default that satisfies
        // YouTube's client sensitivity without forking per-host logic outside catalog (AD-11).
        if (!headers.containsKey("User-Agent")) {
            val isAndroidLike = url.contains("android") || url.contains("googlevideo.com")
            val userAgent = if (isAndroidLike) {
                "com.google.android.youtube/19.05.36 (Linux; U; Android 14; en_US) gzip"
            } else {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
            }
            okhttpRequestBuilder.addHeader("User-Agent", userAgent)
        }
        if (!headers.containsKey("Accept-Language")) {
            okhttpRequestBuilder.addHeader("Accept-Language", "en-US,en;q=0.9")
        }

        val shortUrl = url.take(120)
        val startMs = System.currentTimeMillis()
        CatalogLog.d("$method $shortUrl")

        try {
            val response = client.newCall(okhttpRequestBuilder.build()).execute()
            response.use { res ->
                val latencyMs = System.currentTimeMillis() - startMs
                val code = res.code
                val message = res.message

                if (code == 429) {
                    CatalogLog.w("429 RateLimited after ${latencyMs}ms: $shortUrl")
                    throw ReCaptchaException("Rate limited", url)
                }
                if (code >= 400) {
                    CatalogLog.w("HTTP $code $message after ${latencyMs}ms: $shortUrl")
                } else {
                    CatalogLog.d("HTTP $code in ${latencyMs}ms: $shortUrl")
                }

                val responseHeaders = mutableMapOf<String, MutableList<String>>()
                res.headers.forEach { (name, value) ->
                    responseHeaders.getOrPut(name) { mutableListOf() }.add(value)
                }

                // 10 MB metadata body cap — mirrors reference hardening; stream URLs are not fetched here.
                val bodyString: String = res.body.let { body ->
                    val limit = 10L * 1024 * 1024
                    val contentLength = body.contentLength()
                    if (contentLength != -1L && contentLength > limit) {
                        CatalogLog.w("body too large ($contentLength > $limit), returning empty: $shortUrl")
                        ""
                    } else {
                        try {
                            val source = body.source()
                            // Request up to limit bytes to avoid unbounded buffering.
                            source.request(limit)
                            if (source.buffer.size > limit) {
                                CatalogLog.w("body exceeded limit during read, returning empty: $shortUrl")
                                ""
                            } else {
                                body.string()
                            }
                        } catch (e: Exception) {
                            CatalogLog.w("body read threw ${e.javaClass.simpleName}: ${e.message} $shortUrl")
                            ""
                        }
                    }
                }

                // Latest URL after following redirects — OkHttp's request URL is the final one.
                val latestUrl = res.request.url.toString()

                return Response(code, message, responseHeaders, bodyString, latestUrl)
            }
        } catch (e: IOException) {
            val latencyMs = System.currentTimeMillis() - startMs
            CatalogLog.e("IOException after ${latencyMs}ms $shortUrl: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        } catch (e: ReCaptchaException) {
            // Already logged with 429 branch; rethrow for extractor callers.
            throw e
        }
    }
}
