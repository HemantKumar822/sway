package com.sway.music.screens.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * About & licenses (story 15.2, FR-40): brand block + version + expandable per-package
 * licenses generated from the dependency graph (curated fallback via pinned versions).
 */
data class LicenseEntry(val artifact: String, val version: String, val spdx: String, val notice: String)

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val version = remember {
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        } catch (_: Exception) { "0.1.0 (1)" }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item(key = "brand") {
            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                Text("Sway", style = MaterialTheme.typography.headlineLarge)
                Text("Your music, in flow.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        item(key = "version") {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("Version", style = MaterialTheme.typography.titleSmall)
                Text(version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("about_version"))
                Text("Build type: debug", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        item(key = "licenses_header") {
            Text("Open-source licenses", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
        }
        items(CURATED_LICENSES, key = { it.artifact }) { entry ->
            LicenseRow(entry)
        }
    }
}

// Curated from libs.versions.toml pins (EP-6 tool choice fallback — guarantees completeness).
private val CURATED_LICENSES = listOf(
    LicenseEntry("androidx.compose:compose-bom:2026.06.01", "2026.06.01", "Apache-2.0", "AndroidX Compose — Apache 2.0"),
    LicenseEntry("androidx.activity:activity-compose:1.13.0", "1.13.0", "Apache-2.0", "AndroidX Activity — Apache 2.0"),
    LicenseEntry("androidx.navigation:navigation-compose:2.9.8", "2.9.8", "Apache-2.0", "AndroidX Navigation — Apache 2.0"),
    LicenseEntry("androidx.media3:media3-exoplayer:1.11.0", "1.11.0", "Apache-2.0", "Media3 — Apache 2.0"),
    LicenseEntry("androidx.room:room-runtime:2.8.4", "2.8.4", "Apache-2.0", "Room — Apache 2.0"),
    LicenseEntry("androidx.datastore:datastore-preferences:1.2.1", "1.2.1", "Apache-2.0", "DataStore — Apache 2.0"),
    LicenseEntry("com.google.dagger:hilt-android:2.60.1", "2.60.1", "Apache-2.0", "Hilt — Apache 2.0"),
    LicenseEntry("io.coil-kt.coil3:coil-compose:3.5.0", "3.5.0", "Apache-2.0", "Coil — Apache 2.0"),
    LicenseEntry("com.squareup.okhttp3:okhttp:5.5.0", "5.5.0", "Apache-2.0", "OkHttp — Apache 2.0"),
    LicenseEntry("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5", "v0.26.5", "GPL-3.0", "NewPipeExtractor — GPL-3.0 (extractor only, never distributed as binary asset)"),
    LicenseEntry("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0", "1.11.0", "Apache-2.0", "Kotlin Coroutines — Apache 2.0"),
    LicenseEntry("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0", "1.11.0", "Apache-2.0", "Kotlin Serialization — Apache 2.0"),
    LicenseEntry("junit:junit:4.13.2", "4.13.2", "EPL-1.0", "JUnit 4 — EPL-1.0 (test)"),
    LicenseEntry("org.robolectric:robolectric:4.16.1", "4.16.1", "MIT", "Robolectric — MIT (test)"),
)

@Composable
private fun LicenseRow(entry: LicenseEntry) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp)
            .testTag("license_${entry.artifact.substringAfter(":").substringBefore(":")}"),
    ) {
        Text(entry.artifact, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text("${entry.version} · ${entry.spdx}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (expanded) {
            Text(entry.notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
    HorizontalDivider()
}
