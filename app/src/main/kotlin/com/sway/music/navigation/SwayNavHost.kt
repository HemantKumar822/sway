package com.sway.music.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sway.designui.components.OfflineBanner

/**
 * Navigation shell (story 9.3, FR-26): three bottom tabs (Home/Search/
 * Library) with pill-style selection; detail/utility destinations registered
 * now (screens fill E10/E11/E15); tab-scoped back stacks preserved via
 * saveState/restoreState; deep-link fallback parent = Library.
 */
data class TabSpec(val route: String, val label: String, val icon: ImageVector)

val TOP_TABS = listOf(
    TabSpec(Routes.HOME, "Home", Icons.Filled.Home),
    TabSpec(Routes.SEARCH, "Search", Icons.Filled.Search),
    TabSpec(Routes.LIBRARY, "Library", Icons.Filled.LibraryMusic),
)

@Composable
fun rememberSwayNavController(): NavHostController = rememberNavController()

/**
 * @param startTab initial tab (story 9.4: LIBRARY when launching offline)
 * @param offlineBannerVisible app-wide banner state (story 9.4)
 * @param miniPlayer story 12.1 slot rendered ABOVE the NavigationBar on every
 *   tab (FR-27 global presence); null = no session layer.
 */
@Composable
fun SwayNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startTab: String = Routes.HOME,
    offlineBannerVisible: Boolean = false,
    snackbarHostState: androidx.compose.material3.SnackbarHostState? = null,
    miniPlayer: (@Composable () -> Unit)? = null,
    screen: @Composable (route: String) -> Unit = { route -> PlaceholderScreen(labelFor(route)) },
    detailScreen: @Composable (route: String, id: String) -> Unit = { route, _ ->
        PlaceholderScreen(labelFor(route))
    },
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = {
            if (snackbarHostState != null) {
                androidx.compose.material3.SnackbarHost(hostState = snackbarHostState)
            }
        },
        topBar = {
            Column {
                OfflineBanner(visible = offlineBannerVisible, onDismiss = { /* state owner clears */ })
            }
        },
        bottomBar = {
            Column {
                // Story 12.1: Mini Player persists above the tab bar on ALL tabs
                // whenever a session exists (FR-27); snackbar z-order stays above.
                miniPlayer?.invoke()
                NavigationBar {
                    TOP_TABS.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigateToTab(tab.route)
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startTab,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) { screen(Routes.HOME) }
            composable(Routes.SEARCH) { screen(Routes.SEARCH) }
            composable(Routes.LIBRARY) { screen(Routes.LIBRARY) }

            // Detail + utility destinations registered now; screens fill E10/E11/E15.
            composable(Routes.ALBUM) { entry ->
                detailScreen(Routes.ALBUM, entry.arguments?.getString("albumId").orEmpty())
            }
            composable(Routes.ARTIST) { entry ->
                detailScreen(Routes.ARTIST, entry.arguments?.getString("artistId").orEmpty())
            }
            composable(Routes.CATALOG_PLAYLIST) { entry ->
                detailScreen(Routes.CATALOG_PLAYLIST, entry.arguments?.getString("playlistId").orEmpty())
            }
            composable(Routes.PLAYLIST) { entry ->
                detailScreen(
                    Routes.PLAYLIST,
                    entry.arguments?.getString("playlistId").orEmpty(),
                )
            }
            composable(Routes.LIKED) { screen(Routes.LIKED) }
            composable(Routes.HISTORY) { screen(Routes.HISTORY) }
            composable(Routes.SETTINGS) { PlaceholderScreen("Settings") }
            composable(Routes.ABOUT) { PlaceholderScreen("About") }
        }
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
        Text("$label — arriving soon", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
    }
}

internal fun labelFor(route: String): String = when (route) {
    Routes.HOME -> "Home"
    Routes.SEARCH -> "Search"
    Routes.LIBRARY -> "Library"
    Routes.ALBUM -> "Album"
    Routes.ARTIST -> "Artist"
    Routes.CATALOG_PLAYLIST -> "Catalog Playlist"
    Routes.PLAYLIST -> "Playlist"
    Routes.LIKED -> "Liked Songs"
    Routes.HISTORY -> "Play History"
    Routes.SETTINGS -> "Settings"
    Routes.ABOUT -> "About"
    else -> "Sway"
}

/** Tab-switch law (FR-26): save the origin stack, restore the target's. */
internal fun NavHostController.navigateToTab(tabRoute: String) {
    navigate(tabRoute) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}