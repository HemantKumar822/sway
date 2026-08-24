package com.sway.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sway.core.model.SourceId
import com.sway.core.data.AppDataGraph
import com.sway.designui.theme.SwayTheme
import com.sway.designui.theme.ThemeConfig
import com.sway.music.connectivity.ConnectivityObserver
import com.sway.music.navigation.Routes
import com.sway.music.navigation.SwayNavHost
import com.sway.music.navigation.navigateToTab
import com.sway.music.navigation.rememberSwayNavController
import com.sway.music.notifications.NotificationPermissionGate
import com.sway.music.notifications.PermissionAction
import com.sway.music.screens.detail.AlbumDetailScreen
import com.sway.music.screens.detail.AlbumDetailViewModel
import com.sway.music.screens.detail.ArtistDetailScreen
import com.sway.music.screens.detail.ArtistDetailViewModel
import com.sway.music.screens.detail.CatalogPlaylistDetailScreen
import com.sway.music.screens.detail.CatalogPlaylistDetailViewModel
import com.sway.music.screens.HomeScreen
import com.sway.music.screens.menu.SongContextMenu
import com.sway.music.screens.menu.SongMenuAction
import com.sway.music.screens.menu.rawCatalogUrl
import com.sway.music.screens.menu.shareRawUrl
import com.sway.music.screens.search.SearchFilter
import com.sway.music.screens.search.SearchGroup
import com.sway.music.screens.search.SearchScreen
import com.sway.music.screens.search.SearchViewModel
import com.sway.music.screens.search.SharedPrefsRecentSearchStore
import kotlinx.coroutines.launch

/**
 * App shell (stories 6.3 / 9.1 / 9.3 / 9.4 / 9.5): two-mode SwayTheme, the
 * three-tab navigation shell, connectivity-driven offline routing (offline
 * launch -> Library tab with banner), the post-composition session-restore
 * hook (7.3 output; visual Mini Player arrives E12), and the explain-first
 * notification permission flow.
 *
 * Startup law (NFR-1/AD-10): composition NEVER awaits data or network; the
 * restore coroutine runs after the first frame.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = AppDataGraph.from(applicationContext, com.sway.catalog.NewPipeCatalogSource())
        val connectivity = ConnectivityObserver(this)

        setContent {
            val dark = isSystemInDarkTheme()
            SwayTheme(config = ThemeConfig(darkTheme = dark)) {
                NotificationPermissionRationale()

                val online by connectivity.online.collectAsStateWithLifecycle()
                // Story 9.4: offline launch routes to Library with the banner.
                var startTab by remember { mutableStateOf(if (online) Routes.HOME else Routes.LIBRARY) }

                var restoredLabel by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    val saved = runCatching { graph.sessionRestore.loadRestoredSession() }.getOrNull()
                    restoredLabel = saved?.let {
                        "${it.snapshot.size} songs · ${it.positionMs} ms · paused"
                    } ?: "no saved session"
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Story 10.2: Search tab owns the grouped-results core.
                    val searchScope = rememberCoroutineScope()
                    val searchVm = remember {
                        SearchViewModel(
                            repository = graph.catalog,
                            recents = SharedPrefsRecentSearchStore(applicationContext),
                            scope = searchScope,
                        )
                    }
                    val searchState by searchVm.state.collectAsStateWithLifecycle()
                    // Story 10.4: reconnect restores failed searches without restart.
                    LaunchedEffect(online) { searchVm.setOnline(online) }

                    // Stories 10.5–10.7: detail surfaces over the catalog repo.
                    val navController = rememberSwayNavController()

                    // Story 10.8: context-menu state + owned-data flows.
                    var menuSongState by remember { mutableStateOf<com.sway.core.model.Song?>(null) }
                    val likedSongs by graph.library.observeLiked()
                        .collectAsStateWithLifecycle(initialValue = emptyList())
                    val playlistSummaries by graph.playlists.observePlaylists()
                        .collectAsStateWithLifecycle(initialValue = emptyList())
                    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
                    val appContext = applicationContext

                    menuSongState?.let { selected ->
                        SongContextMenu(
                            song = selected,
                            liked = likedSongs.any { it.id == selected.id },
                            playlists = playlistSummaries,
                            onAction = { action ->
                                when (action) {
                                    SongMenuAction.PLAY_NEXT ->
                                        searchScope.launch { snackbarHostState.showSnackbar("Playing next") }
                                    SongMenuAction.ADD_TO_QUEUE ->
                                        searchScope.launch { snackbarHostState.showSnackbar("Added to queue") }
                                    SongMenuAction.TOGGLE_LIKE -> searchScope.launch {
                                        if (likedSongs.any { it.id == selected.id }) {
                                            graph.library.clearLiked(selected.id)
                                            snackbarHostState.showSnackbar("Removed from Liked Songs")
                                        } else {
                                            graph.library.setLiked(selected)
                                            snackbarHostState.showSnackbar("Added to Liked Songs")
                                        }
                                    }
                                    SongMenuAction.GO_TO_ALBUM -> selected.albumId?.let {
                                        navController.navigate(Routes.album(it.value))
                                    }
                                    SongMenuAction.GO_TO_ARTIST -> selected.artistId?.let {
                                        navController.navigate(Routes.artist(it.value))
                                    }
                                    SongMenuAction.SHARE_URL -> shareRawUrl(
                                        appContext,
                                        rawCatalogUrl(selected.id.value),
                                    )
                                    else -> Unit // ADD_TO_PLAYLIST resolves through the picker
                                }
                                if (action != SongMenuAction.ADD_TO_PLAYLIST) menuSongState = null
                            },
                            onAddToPlaylist = { pid, song -> searchScope.launch {
                                val name = playlistSummaries
                                    .firstOrNull { it.playlist.id.value == pid }?.playlist?.name ?: "playlist"
                                graph.playlists.addSong(com.sway.core.model.PlaylistId(pid), song)
                                snackbarHostState.showSnackbar("Added to $name")
                                menuSongState = null
                            } },
                            onCreatePlaylistAndAdd = { name, song -> searchScope.launch {
                                val created = graph.playlists.create(name)
                                if (created is com.sway.core.model.SwayResult.Success) {
                                    graph.playlists.addSong(created.data, song)
                                }
                                snackbarHostState.showSnackbar("Added to $name")
                                menuSongState = null
                            } },
                            onDismiss = { menuSongState = null },
                        )
                    }

                    SwayNavHost(
                        navController = navController,
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        startTab = startTab,
                        offlineBannerVisible = !online,
                        // Snackbar z-order ABOVE tab bar (UX-DR5 substrate).
                        snackbarHostState = snackbarHostState,
                        screen = { route ->
                            when (route) {
                                Routes.HOME -> HomeScreen(
                                    likedCount = likedSongs.size,
                                    playlistCount = playlistSummaries.size,
                                    historyCount = 0,
                                    onSearchClick = { navController.navigateToTab(Routes.SEARCH) },
                                    onLikedClick = {},
                                    onPlaylistsClick = {},
                                    onHistoryClick = {},
                                )
                                Routes.SEARCH -> SearchScreen(
                                    state = searchState,
                                    onQueryChanged = searchVm::onQueryChanged,
                                    onSubmit = searchVm::onSubmit,
                                    onChipSelected = { filter: SearchFilter ->
                                        searchVm.onChipSelected(filter)
                                    },
                                    onRetry = searchVm::onRetry,
                                    onLoadMore = { group: com.sway.music.screens.search.SearchGroup ->
                                        searchVm.onLoadMore(group)
                                    },
                                    onClearQuery = searchVm::onClearQuery,
                                    onRecentSelected = searchVm::onRecentSelected,
                                    onClearRecents = searchVm::onClearRecents,
                                    onSongClick = { },
                                    onSongLongClick = { menuSongState = it },
                                    onAlbumClick = { album -> navController.navigate(Routes.album(album.id.value)) },
                                    onArtistClick = { artist -> navController.navigate(Routes.artist(artist.id.value)) },
                                    onPlaylistClick = { pl ->
                                        navController.navigate(Routes.catalogPlaylist(pl.id.value))
                                    },
                                )
                                else -> Unit // NavHost destinations own these routes
                            }
                        },
                        detailScreen = { route, id ->
                            when (route) {
                                Routes.ALBUM -> {
                                    val vm = remember(id) {
                                        AlbumDetailViewModel(graph.catalog, SourceId(id), searchScope)
                                    }
                                    AlbumDetailScreen(
                                        state = vm.state.collectAsStateWithLifecycle().value,
                                        onRetry = vm::retry,
                                        onPlaybackRequest = { /* queue wiring completes E12 */ },
                                        onArtistClick = { artistId ->
                                            navController.navigate(Routes.artist(artistId.value))
                                        },
                                        onSongLongClick = { menuSongState = it },
                                    )
                                }
                                Routes.ARTIST -> {
                                    val vm = remember(id) {
                                        ArtistDetailViewModel(graph.catalog, SourceId(id), searchScope)
                                    }
                                    ArtistDetailScreen(
                                        state = vm.state.collectAsStateWithLifecycle().value,
                                        onRetry = vm::retry,
                                        onPlaybackRequest = { /* queue wiring completes E12 */ },
                                        onSongLongClick = { menuSongState = it },
                                    )
                                }
                                Routes.CATALOG_PLAYLIST -> {
                                    val vm = remember(id) {
                                        CatalogPlaylistDetailViewModel(graph.catalog, SourceId(id), searchScope)
                                    }
                                    CatalogPlaylistDetailScreen(
                                        state = vm.state.collectAsStateWithLifecycle().value,
                                        onRetry = vm::retry,
                                        onPlaybackRequest = { /* queue wiring completes E12 */ },
                                        onSongLongClick = { menuSongState = it },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Story 6.3 (FR-21 substrate / AC5–AC7): explain-first POST_NOTIFICATIONS
     * flow. The DECISION law lives in NotificationPermissionGate (unit-tested);
     * this composable only executes it (rationale copy -> acknowledgment ->
     * system dialog). Below API 33 or when granted, everything is a no-op.
     */
    @Composable
    private fun NotificationPermissionRationale() {
        var showDialog by remember { mutableStateOf(false) }
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
                PermissionAction.SHOW_RATIONALE_THEN_REQUEST -> showDialog = true
                PermissionAction.REQUEST_SYSTEM_DIALOG ->
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                PermissionAction.NOTHING_TO_DO -> Unit
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(stringResource(R.string.notif_permission_rationale_title)) },
                text = { Text(stringResource(R.string.notif_permission_rationale_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDialog = false
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                    ) { Text(stringResource(R.string.notif_permission_rationale_continue)) }
                },
            )
        }
    }
}
