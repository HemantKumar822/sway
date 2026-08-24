package com.sway.designui.images

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.sway.core.model.ArtworkRef

/**
 * Exhausted-artwork registry (story 13.1): refs whose WHOLE candidate chain
 * failed. The connectivity trigger calls [retryAll]; slots also re-fire via
 * the [SwayAsyncImage] online-signal epoch — WITHOUT user action (FR-36).
 * Pure in-process state: a fresh process re-attempts naturally.
 */
object FailedArtworkRegistry {
    private val failed = mutableSetOf<String>()

    val keys: Set<String> get() = synchronized(failed) { failed.toSet() }

    fun markFailed(cacheKey: String) {
        synchronized(failed) { failed += cacheKey }
    }

    fun retryAll(): Int {
        val n = synchronized(failed) { failed.size.also { failed.clear() } }
        return n
    }

    fun resetForTest() = synchronized(failed) { failed.clear() }
}

/**
 * Artwork image with the AR-10 candidate-chain walk (story 13.1, FR-36).
 *
 * - The IDENTICAL-bounds branded placeholder is ALWAYS composed underneath:
 *   zero px layout shift on load, error, or exhaustion (FR-36 structural law).
 * - Load failure advances to `candidateAfter(failedUrl)`; exhausting the
 *   chain registers the ref in [FailedArtworkRegistry] and keeps placeholder.
 * - When [online] flips false->true, an exhausted slot re-fires from its
 *   canonical URL automatically (retry-trigger law; no user action).
 *
 * Zero host-specific URL logic: only the [ArtworkRef] value contract is
 * consumed here (AD-11) — hosts live at :catalog parse time.
 */
@Composable
fun SwayAsyncImage(
    artwork: ArtworkRef?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    online: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
) {
    Box(modifier = modifier, contentAlignment = contentAlignment) {
        // Identical-bounds branded placeholder — always present underneath.
        com.sway.designui.components.ArtworkPlaceholder(Modifier.fillMaxSize())

        if (artwork != null) {
            var candidateUrl by remember(artwork.cacheKey) { mutableStateOf(artwork.canonicalUrl) }
            var attemptEpoch by remember(artwork.cacheKey) { mutableIntStateOf(0) }

            // Connectivity-restored retry trigger (FR-36): false -> true bumps
            // the epoch so exhausted refs re-request their canonical URL.
            LaunchedEffect(artwork.cacheKey, online) {
                if (online && FailedArtworkRegistry.keys.contains(artwork.cacheKey)) {
                    attemptEpoch += 1
                    candidateUrl = artwork.canonicalUrl
                }
            }

            val context = LocalContext.current
            val request = ImageRequest.Builder(context)
                .data(candidateUrl)
                .memoryCacheKey("${artwork.cacheKey}#$attemptEpoch")
                .build()
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { _ ->
                    artwork.candidateAfter(candidateUrl)?.let { next ->
                        candidateUrl = next
                    } ?: FailedArtworkRegistry.markFailed(artwork.cacheKey)
                },
            )
        }
    }
}
