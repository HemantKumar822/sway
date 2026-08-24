package com.sway.designui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sway.core.model.SwayErrorUiState

/**
 * Typed error surfaces (story 9.2, FR-37/FR-14/NFR-2, UX §8.10): short cause
 * line + Retry >=48 dp. The RETRY CALLBACK belongs to the caller, so prior
 * scroll/query state is preserved by construction. Copy may rotate after
 * repeated failures via [messageOverride].
 */
@Composable
fun ErrorPanel(
    category: SwayErrorUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    area: Boolean = false,
    messageOverride: String? = null,
) {
    val copy = messageOverride ?: defaultCopy(category)
    Column(
        modifier = if (area) {
            modifier.fillMaxSize().padding(24.dp)
        } else {
            modifier.fillMaxWidth().padding(vertical = 8.dp)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = copy,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier
                .padding(top = 16.dp)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Retry" },
        ) {
            Text("Retry")
        }
    }
}

/** Empty state (FR-37 quintet): an invitation to act, never a blank screen. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun defaultCopy(category: SwayErrorUiState): String = when (category) {
    SwayErrorUiState.Offline -> "You're offline. Check your connection and retry."
    SwayErrorUiState.RateLimited -> "Too many requests right now. Give it a moment."
    SwayErrorUiState.UpstreamUnavailable -> "The stream didn't load. Retry?"
    SwayErrorUiState.ContentNotFound -> "This content is gone."
    SwayErrorUiState.Parse -> "Something came back malformed. Retry?"
    SwayErrorUiState.Storage -> "Local storage hiccup. Retry?"
    SwayErrorUiState.Unknown -> "Something went wrong. Retry?"
}
