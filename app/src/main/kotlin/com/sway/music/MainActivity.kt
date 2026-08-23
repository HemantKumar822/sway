package com.sway.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import com.sway.music.notifications.NotificationPermissionGate
import com.sway.music.notifications.PermissionAction

/**
 * Launcher activity rendering a bare composable screen (story 1.1 skeleton).
 * Hilt entry point since story 1.2 (AR-3); navigation shell and theming land in
 * epic 9; startup law (AD-10) applies from day one.
 *
 * Story 6.3: hosts the explain-first POST_NOTIFICATIONS flow — the DECISION
 * law lives in [NotificationPermissionGate] (unit-tested); this activity only
 * executes it (rationale copy -> acknowledgment -> system dialog). Below API
 * 33 or when granted, everything evaluates to a no-op.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                Text(text = "Sway")
            }
            NotificationPermissionRationale()
        }
    }

    @Composable
    private fun NotificationPermissionRationale() {
        var showRationale by remember { mutableStateOf(false) }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* denial degradation is silent: media controls are platform-exempt */ }

        LaunchedEffect(Unit) {
            when (
                NotificationPermissionGate.nextAction(
                    apiLevel = Build.VERSION.SDK_INT,
                    granted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED,
                    rationaleAcknowledged = false,
                )
            ) {
                PermissionAction.SHOW_RATIONALE_THEN_REQUEST -> showRationale = true
                // Unreachable on fresh composition (law: request needs prior
                // acknowledgment); kept total so the `when` stays exhaustive.
                PermissionAction.REQUEST_SYSTEM_DIALOG ->
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                PermissionAction.NOTHING_TO_DO -> Unit
            }
        }

        if (showRationale) {
            AlertDialog(
                onDismissRequest = { showRationale = false },
                title = { Text(stringResource(R.string.notif_permission_rationale_title)) },
                text = { Text(stringResource(R.string.notif_permission_rationale_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRationale = false
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                    ) { Text(stringResource(R.string.notif_permission_rationale_continue)) }
                },
            )
        }
    }
}
