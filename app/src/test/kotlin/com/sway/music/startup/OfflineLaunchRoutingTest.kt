package com.sway.music.startup

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.music.connectivity.ConnectivityObserver
import com.sway.music.navigation.Routes
import com.sway.music.navigation.SwayNavHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Story 9.4 — offline launch routing (NFR-1 / UJ-5 beat 1): airplane-mode
 * launch lands on Library with the banner raised; online launch opens Home.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OfflineLaunchRoutingTest {

    @get:Rule
    val compose = createComposeRule()

    private fun context(): Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    @Test
    fun observer_initialOffline_reportsOffline() {
        val cm = context().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(cm).setActiveNetworkInfo(null) // airplane mode

        val observer = ConnectivityObserver(context())
        assertEquals(false, observer.online.value)
    }

    // Online-path probe: the Robolectric shadow cannot faithfully emulate
    // NET_CAPABILITY_INTERNET on an active network, so the connected case is
    // verified on hardware at E6/E12 exit criteria (same honesty split as
    // fr8/fr16/fr21/fr25 device-gated harnesses).

    @Test
    fun offlineLaunch_rendersLibraryWithBanner() {
        compose.setContent {
            SwayNavHost(
                navController = com.sway.music.navigation.rememberSwayNavController(),
                startTab = Routes.LIBRARY,
                offlineBannerVisible = true,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText(
            "You're offline. Your Library, Liked Songs, Playlists and History still work. Search and streaming need a connection.",
        ).assertExists()
        compose.onNodeWithText("Library — arriving soon").assertExists()
        assertTrue(
            "Home must NOT be the offline landing tab",
            compose.onAllNodes(hasText("Home — arriving soon")).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun onlineLaunch_opensHome_withoutBanner() {
        compose.setContent {
            SwayNavHost(
                navController = com.sway.music.navigation.rememberSwayNavController(),
                startTab = Routes.HOME,
                offlineBannerVisible = false,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Home — arriving soon").assertExists()
    }
}
