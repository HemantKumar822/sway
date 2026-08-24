package com.sway.music.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sway.core.data.Appearance
import com.sway.core.model.Quality

/**
 * Settings screen (story 15.1, FR-39): appearance System/Light/Dark persisted via
 * SettingsRepository applying immediately app-wide (150ms fade max via MotionScheme
 * colorSpec, no full-screen animation); audio-quality entry shares QualitySelectorSheet
 * when OQ-6 flag active; version row → About.
 *
 * Parameterized (state+callbacks only) per hermetic precedent.
 */
@Composable
fun SettingsScreen(
    appearance: Appearance,
    onAppearanceSelected: (Appearance) -> Unit,
    onOpenAbout: () -> Unit,
    quality: Quality? = null,
    onQualityClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))

        Appearance.entries.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onAppearanceSelected(option) }
                    .padding(vertical = 4.dp)
                    .testTag("appearance_option_${option.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = appearance == option, onClick = { onAppearanceSelected(option) })
                Text(option.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // Quality entry — behind OQ-6 flag (hidden with zero dead refs when vetoed).
        if (quality != null && onQualityClick != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onQualityClick)
                    .padding(vertical = 12.dp)
                    .testTag("settings_quality_row"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Audio quality", style = MaterialTheme.typography.titleSmall)
                    Text(quality.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
        }

        TextButton(onClick = onOpenAbout, modifier = Modifier.testTag("settings_about_row")) {
            Text("About — version & licenses")
        }
    }
}

private val Appearance.label: String
    get() = when (this) {
        Appearance.SYSTEM -> "System"
        Appearance.LIGHT -> "Light"
        Appearance.DARK -> "Dark"
    }
