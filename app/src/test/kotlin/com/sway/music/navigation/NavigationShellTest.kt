package com.sway.music.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 9.3 — navigation shell laws (FR-26): every destination reachable
 * <=2 taps from launch; detail push/back behaves predictably. Navigation is
 * driven programmatically (touch-injection under Robolectric is unreliable);
 * the UI still asserts real composed output per route.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class NavigationShellTest {

    @get:Rule
    val compose = createComposeRule()

    private fun currentRoute(controller: NavHostController?): String? =
        controller?.currentBackStackEntry?.destination?.route

    @Test
    fun everyDestinationReachable_withinTwoTaps_andBackBehaves() {
        var controller: NavHostController? = null
        compose.setContent {
            val nav = rememberSwayNavController()
            controller = nav
            SwayNavHost(navController = nav)
        }
        compose.waitForIdle()
        assertEquals(Routes.HOME, currentRoute(controller))
        compose.onNodeWithText("Home — arriving soon").assertExists()

        // Tap 1 -> Library; Tap 2 -> Settings. Two taps from launch.
        compose.runOnIdle { controller!!.navigate(Routes.LIBRARY) }
        compose.waitForIdle()
        compose.onNodeWithText("Library — arriving soon").assertExists()

        compose.runOnIdle { controller!!.navigate(Routes.SETTINGS) }
        compose.waitForIdle()
        compose.onNodeWithText("Settings — arriving soon").assertExists()

        // Back pops predictably: Settings -> Library.
        compose.runOnIdle { controller!!.popBackStack() }
        compose.waitForIdle()
        assertEquals(Routes.LIBRARY, currentRoute(controller))

        // All registered detail/utility routes resolve (probe each).
        listOf(
            Routes.ALBUM, Routes.ARTIST, Routes.CATALOG_PLAYLIST,
            Routes.PLAYLIST, Routes.LIKED, Routes.HISTORY, Routes.ABOUT,
        ).forEach { route ->
            compose.runOnIdle { controller!!.navigate(route) }
            compose.waitForIdle()
            assertTrue("route $route must resolve", currentRoute(controller) == route)
            compose.runOnIdle { controller!!.popBackStack() }
            compose.waitForIdle()
        }
        assertEquals("back returns to Library", Routes.LIBRARY, currentRoute(controller))
        compose.onNodeWithText("Library — arriving soon").assertExists()
    }

    @Test
    fun tabScopedNavigation_preservesStartDestinationState_law() {
        var controller: NavHostController? = null
        compose.setContent {
            val nav = rememberSwayNavController()
            controller = nav
            SwayNavHost(navController = nav)
        }
        compose.waitForIdle()

        // Home -> Search -> Home: start destination restores (saveState/restoreState
        // options on tab navigation; full scroll-preservation proof lands with E10 content).
        compose.runOnIdle { controller!!.navigateToTab(Routes.SEARCH) }
        compose.waitForIdle()
        assertEquals(Routes.SEARCH, currentRoute(controller))

        compose.runOnIdle {
            controller!!.navigateToTab(Routes.HOME)
        }
        compose.waitForIdle()
        assertEquals(Routes.HOME, currentRoute(controller))
        compose.onNodeWithText("Home — arriving soon").assertExists()
    }

    private fun assertTrue(message: String, condition: Boolean) =
        org.junit.Assert.assertTrue(message, condition)
}
