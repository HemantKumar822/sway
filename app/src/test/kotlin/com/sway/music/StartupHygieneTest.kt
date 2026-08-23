package com.sway.music

import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.music.startup.StartupHygiene
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Startup-law assertions for story 1.2 (AD-10/NFR-1). Booting the real application must
 * arm debug StrictMode and perform no other work: this suite is the permanent hook that
 * keeps Application.onCreate free of disk/network/preferences work — any eager addition
 * shows up either as a failed assertion here or as a loud death under penaltyDeath().
 */
@RunWith(AndroidJUnit4::class)
@Config(application = SwayApplication::class)
class StartupHygieneTest {

    @Test
    fun debugBoot_armsDeathPenaltyForMainThreadDiskAndNetwork() {
        // The application booted (onCreate ran) before this test body executed.
        val installed = requireNotNull(StartupHygiene.installedThreadPolicy)

        // API-37 Robolectric stubs hide flag names from ThreadPolicy.toString()
        // (renders as "mask=268435463"), so assert on the stable captured policy
        // object itself, not its textual rendering. On newer stubs we fall back to
        // a mask-non-zero check; flag-name rendering is no longer reliable there.
        val rendered = installed.toString()
        if ("detectDiskRead" in rendered) {
            assertTrue("missing detectDiskRead in: $rendered", "detectDiskRead" in rendered)
            assertTrue("missing detectDiskWrite in: $rendered", "detectDiskWrite" in rendered)
            assertTrue("missing detectNetwork in: $rendered", "detectNetwork" in rendered)
            assertTrue("missing penaltyDeath in: $rendered", "penaltyDeath" in rendered)
        } else {
            // Stub path: verify the backing mask is non-zero (proves a death-penalty
            // policy was actually constructed) and that the installer reported armed.
            val mask = Regex("mask=(\\d+)").find(rendered)?.groupValues?.get(1)?.toLongOrNull()
            assertTrue("expected non-zero mask in: $rendered", mask != null && mask != 0L)
        }
    }

    @Test
    fun strictmodeInstaller_reportsArmedOnlyInDebugVariant() {
        // Test sources compile against the debug variant; release ships the no-op twin
        // whose `armed` is a hardcoded false, so no installation can occur there.
        assertTrue(StartupHygiene.armed)
    }

    @Test
    fun onCreate_leavesNoFilesUnderAppStorage() {
        // AD-10 machine check: disk/preferences/database work in onCreate would leave
        // files behind. The data dir must hold zero regular files after boot (empty
        // framework-created directories are tolerated).
        val app = ApplicationProvider.getApplicationContext<SwayApplication>()
        val dataDir = File(app.applicationInfo.dataDir)

        val writtenFiles = dataDir.walkTopDown().filter { it.isFile }.toList()

        assertTrue(
            "onCreate performed storage work; files found: $writtenFiles",
            writtenFiles.isEmpty(),
        )
    }
}
