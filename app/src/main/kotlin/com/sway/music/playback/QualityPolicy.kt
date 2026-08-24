package com.sway.music.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sway.core.model.Quality

/**
 * OQ-6 gate [EP-8]: the FR-15 presentation ships behind this flag, DEFAULT ON
 * pending owner veto. Flipping it to false removes the Full Player chip AND
 * every selector reference with zero dead code paths (15.1 reuses the same
 * gate for its settings entry).
 */
object QualityChipPolicy {
    const val ENABLED = true
}

/** Helper text law: honest application timing (FR-15 applies-next-resolution). */
const val QUALITY_HELPER_TEXT = "Applies from your next song."

/** Plain-language option lines (UX §8.8); order = enum order, AUTO first. */
val QUALITY_OPTIONS: List<Pair<Quality, String>> = listOf(
    Quality.AUTO to "Adjusts to your connection",
    Quality.LOW to "Uses less data",
    Quality.MEDIUM to "Balances sound and data",
    Quality.HIGH to "Best sound, uses more data",
)

/**
 * The Full Player's secondary-row chip slot content (12.4). Renders NOTHING
 * when the OQ-6 gate is off — callers need no separate branch.
 */
@Composable
fun QualityChip(current: Quality, onOpen: () -> Unit) {
    if (!QualityChipPolicy.ENABLED) return
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("quality_chip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Quality \u00B7 $current",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Modal selector (DR9/§8.8): AUTO/LOW/MEDIUM/HIGH with plain-language lines +
 * [QUALITY_HELPER_TEXT]; selection writes through the 5.1 SettingsRepository
 * path and takes effect from the NEXT resolution — stated honestly here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySelectorSheet(
    visible: Boolean,
    current: Quality,
    onSelect: (Quality) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible || !QualityChipPolicy.ENABLED) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("quality_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Audio quality", style = MaterialTheme.typography.titleMedium)
            Text(
                QUALITY_HELPER_TEXT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("quality_helper"),
            )
            Spacer(Modifier.padding(top = 8.dp))
            QUALITY_OPTIONS.forEach { (quality, description) ->
                val selected = quality == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(quality) }
                        .padding(vertical = 12.dp, horizontal = 8.dp)
                        .testTag("quality_option_${quality.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "$current selected",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Column {
                        Text(
                            quality.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}
