package com.sway.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sway.catalog.CatalogHttpClient
import com.sway.core.data.AppDataGraph
import com.sway.core.model.Quality
import com.sway.core.model.SourceId
import com.sway.designui.images.FailedArtworkRegistry
import com.sway.designui.images.SwayImages
import com.sway.designui.theme.Atmospherics
import com.sway.designui.theme.SwayTheme
import com.sway.designui.theme.ThemeConfig
import com.sway.music.connectivity.ConnectivityObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sway.music.navigation.Routes
import com.sway.music.navigation.SwayNavHost
import com.sway.music.navigation.navigateToTab
import com.sway.music.navigation.rememberSwayNavController
import com.sway.music.notifications.NotificationPermissionGate
import com.sway.music.notifications.PermissionAction
import com.sway.music.playback.QualityChip
import com.sway.music.playback.QualitySelectorSheet
import com.sway.music.playback.SwayPlaybackHost
import com.sway.music.screens.detail.AlbumDetailScreen
import com.sway.music.screens.detail.AlbumDetailViewModel
import com.sway.music.screens.detail.ArtistDetailScreen
import com.sway.music.screens.detail.ArtistDetailViewModel
import com.sway.music.screens.detail.CatalogPlaylistDetailScreen
import com.sway.music.screens.detail.CatalogPlaylistDetailViewModel
import com.sway.music.screens.detail.PlaybackRequests
import com.sway.music.screens.detail.PlaybackRequest
import com.sway.music.screens.player.FullPlayerScreen
import com.sway.music.screens.player.MiniPlayerBar
import com.sway.music.screens.player.QueueSheet
import com.sway.music.screens.library.HistoryScreen
import com.sway.music.screens.library.LikedSongsScreen
import com.sway.music.screens.library.LibraryHubScreen
import com.sway.music.screens.library.PlaylistEditorScreen
import com.sway.music.screens.library.PlaylistEditorUiState
import com.sway.music.screens.library.PlaylistEditorViewModel
import com.sway.music.screens.library.RepositoryPlaylistEditorOps
import com.sway.music.screens.HomeScreen
import com.sway.music.screens.menu.SongContextMenu
import com.sway.music.screens.menu.SongMenuAction
import com.sway.music.screens.menu.rawCatalogUrl
import com.sway.music.screens.menu.shareRawUrl
import com.sway.music.screens.search.SearchFilter
import com.sway.music.screens.search.SearchGroup
import com.sway.music.screens.search.SearchPhase
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
            // 13.1: Image pipeline — init once in composition scope (AD-10: no disk/network in onCreate).
            // Caches are lazy inside Coil; this post-first-frame construction keeps startup free.
            val appCtx = LocalContext.current.applicationContext
            remember {
                try {
                    SwayImages.init(appCtx, CatalogHttpClient.createArtworkVariant(), appCtx.cacheDir)
                } catch (_: Exception) {
                }
            }
            // 13.2: atmosphere holds the playing cover's palette; drives SwayTheme dynamicSeed
            // (MONO default until 15.1 persistence — dynamic path exercised via seed injection).
            var atmosphere by remember { mutableStateOf<com.sway.designui.theme.Atmosphere?>(null) }
            SwayTheme(config = ThemeConfig(darkTheme = dark), dynamicSeed = atmosphere?.seed) {
                NotificationPermissionRationale()

                val online by connectivity.online.collectAsStateWithLifecycle()
                // 13.1: connectivity-restored retry trigger — clears exhausted registry so placeholders re-fire.
                // SwayAsyncImage also bumps its attemptEpoch when online flips and the key was failed.
                LaunchedEffect(online) {
                    if (online) {
                        // Small delay lets SwayAsyncImage's LaunchedEffect observe the key before clear.
                        kotlinx.coroutines.delay(120)
                        FailedArtworkRegistry.retryAll()
                    }
                }
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
                    // Story 11.4: live history count for Home + hub tiles.
                    val historyEntries by graph.history.observeRecent()
                        .collectAsStateWithLifecycle(initialValue = emptyList())
                    val nowMillis = remember { System.currentTimeMillis() }
                    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
                    val appContext = applicationContext

                    // --- Stories 12.1-12.4: playback host + player surfaces ---
                    // Settings ride the graph (5.1 factory; DataStore stays
                    // behind the core:data seam — AR-1/AR-3).
                    val settings = graph.settings
                    val playbackHost = remember {
                        SwayPlaybackHost(appContext, searchScope, settings, graph.sessionRestore)
                    }
                    LaunchedEffect(Unit) {
                        playbackHost.start() // AR-9 post-composition hook (FR-25/FR-27)
                    }
                    val playback by playbackHost.uiState.collectAsStateWithLifecycle()
                    val audioQuality by settings.audioQuality
                        .collectAsStateWithLifecycle(initialValue = Quality.AUTO)
                    var fullOpen by remember { mutableStateOf(false) }
                    var queueOpen by remember { mutableStateOf(false) }
                    var qualityOpen by remember { mutableStateOf(false) }

                    // AD-6 tick scoping: ONE position subscription, alive only
                    // while a session exists; feeds Mini hairline + scrubber.
                    val livePosition = if (playback.currentItem != null) {
                        playbackHost.positionFlow()
                            .collectAsStateWithLifecycle(initialValue = 0L).value
                    } else {
                        0L
                    }

                    val currentLiked = playback.currentItem?.let { cur ->
                        likedSongs.any { it.id == cur.song.id }
                    } ?: false

                    // 13.2: atmosphere extraction — keyed by canonicalUrl, off-main, cached LRU 32.
                    // Fallback neutral ensures scrim AA even when extraction fails.
                    LaunchedEffect(playback.currentItem?.song?.artwork?.cacheKey, dark) {
                        val ref = playback.currentItem?.song?.artwork
                        if (ref == null) {
                            atmosphere = null
                        } else {
                            try {
                                val loader = if (SwayImages.isInitialized) SwayImages.loader() else null
                                val result = if (loader != null) {
                                    withContext(Dispatchers.Default) {
                                        Atmospherics.loadSeedBitmap(loader, appCtx, ref, dark)
                                    }
                                } else null
                                atmosphere = result ?: withContext(Dispatchers.Default) { Atmospherics.fallback(dark) }
                            } catch (_: Exception) {
                                atmosphere = withContext(Dispatchers.Default) { Atmospherics.fallback(dark) }
                            }
                        }
                    }

                    // 13.2 PROVISIONAL: status-bar echo when full player open with atmosphere (SDK <35 guard).
                    if (fullOpen && atmosphere != null && Build.VERSION.SDK_INT < 35) {
                        SideEffect {
                            try {
                                @Suppress("DEPRECATION")
                                window.statusBarColor = atmosphere!!.backdrop.toArgb()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    fun toggleLike(song: com.sway.core.model.Song) = searchScope.launch {
                        if (likedSongs.any { it.id == song.id }) {
                            graph.library.clearLiked(song.id)
                            snackbarHostState.showSnackbar("Removed from Liked Songs")
                        } else {
                            graph.library.setLiked(song)
                            snackbarHostState.showSnackbar("Added to Liked Songs")
                        }
                    }

                    BackHandler(enabled = queueOpen) { queueOpen = false }
                    BackHandler(enabled = fullOpen && !queueOpen) { fullOpen = false }

                    menuSongState?.let { selected ->
                        SongContextMenu(
                            song = selected,
                            liked = likedSongs.any { it.id == selected.id },
                            playlists = playlistSummaries,
                            onAction = { action ->
                                when (action) {
                                    SongMenuAction.PLAY_NEXT -> searchScope.launch {
                                        playbackHost.playNext(selected)
                                        snackbarHostState.showSnackbar("Playing next")
                                    }
                                    SongMenuAction.ADD_TO_QUEUE -> searchScope.launch {
                                        playbackHost.addToQueue(selected)
                                        snackbarHostState.showSnackbar("Added to queue")
                                    }
                                    SongMenuAction.OPEN_QUEUE -> queueOpen = true
                                    SongMenuAction.TOGGLE_LIKE -> toggleLike(selected)
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

                    // Overlays stack ABOVE the shell (Full > Queue > Quality);
                    // the Mini layer lives INSIDE the shell's bottomBar slot.
                    Box(modifier = Modifier.fillMaxSize()) {
                    SwayNavHost(
                        navController = navController,
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        startTab = startTab,
                        offlineBannerVisible = !online,
                        // Snackbar z-order ABOVE tab bar (UX-DR5 substrate).
                        snackbarHostState = snackbarHostState,
                        // Story 12.1: global Mini layer (FR-27) above the tabs.
                        miniPlayer = {
                            MiniPlayerBar(
                                state = playback,
                                visible = !playbackHost.barHidden && playback.currentItem != null,
                                positionMs = livePosition,
                                onTogglePlayPause = playbackHost::togglePlayPause,
                                onNext = playbackHost::next,
                                onExpand = { fullOpen = true },
                                onOpenQueue = { queueOpen = true },
                                onHide = { playbackHost.hideBar() },
                                accentColor = atmosphere?.seed?.let { seed ->
                                    // Vibrant accent for hairline/thumb when atmosphere present.
                                    try {
                                        androidx.compose.ui.graphics.Color(seed.vibrant ?: seed.dominant)
                                    } catch (_: Exception) { null }
                                },
                                online = online,
                            )
                        },
                        screen = { route ->
                            when (route) {
                                Routes.HOME -> HomeScreen(
                                    likedCount = likedSongs.size,
                                    playlistCount = playlistSummaries.size,
                                    historyCount = historyEntries.size,
                                    onSearchClick = { navController.navigateToTab(Routes.SEARCH) },
                                    onLikedClick = { navController.navigateToTab(Routes.LIBRARY) },
                                    onPlaylistsClick = { navController.navigateToTab(Routes.LIBRARY) },
                                    onHistoryClick = { navController.navigateToTab(Routes.LIBRARY) },
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
                                    // FR-22 matrix entry: Songs-group context at tap.
                                    onSongClick = { song ->
                                        val songs = (searchState.phase as? SearchPhase.Results)
                                            ?.content?.songs?.items.orEmpty()
                                        val idx = songs.indexOfFirst { it.id == song.id }
                                        playbackHost.play(
                                            if (idx >= 0) {
                                                PlaybackRequests.build(
                                                    songs,
                                                    PlaybackRequests.Mode.FromIndex(idx),
                                                )
                                            } else {
                                                PlaybackRequest(items = listOf(song), startIndex = 0, shuffled = false)
                                            },
                                        )
                                    },
                                    onSongLongClick = { menuSongState = it },
                                    onAlbumClick = { album -> navController.navigate(Routes.album(album.id.value)) },
                                    onArtistClick = { artist -> navController.navigate(Routes.artist(artist.id.value)) },
                                    onPlaylistClick = { pl ->
                                        navController.navigate(Routes.catalogPlaylist(pl.id.value))
                                    },
                                )
                                // Stories 11.1/11.2: owned-data surfaces (instant, offline).
                                Routes.LIKED -> LikedSongsScreen(
                                    songs = likedSongs,
                                    onPlaybackRequest = { playbackHost.play(it) },
                                    onSongLongClick = { menuSongState = it },
                                )
                                Routes.HISTORY -> HistoryScreen(
                                    entries = historyEntries,
                                    nowMillis = nowMillis,
                                    onPlaybackRequest = { playbackHost.play(it) },
                                    onSongLongClick = { menuSongState = it },
                                )
                                // Story 11.4: Library hub aggregation.
                                Routes.LIBRARY -> LibraryHubScreen(
                                    likedSongs = likedSongs,
                                    playlists = playlistSummaries,
                                    historyCount = historyEntries.size,
                                    onPlaybackRequest = { playbackHost.play(it) },
                                    onOpenPlaylist = { pid, _ ->
                                        navController.navigate(Routes.playlist(pid))
                                    },
                                    onOpenLiked = { navController.navigate(Routes.LIKED) },
                                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                                    onCreatePlaylist = { name -> searchScope.launch {
                                        graph.playlists.create(name)
                                        snackbarHostState.showSnackbar("Created \"$name\"")
                                    } },
                                    onSongLongClick = { menuSongState = it },
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
                                        onPlaybackRequest = { playbackHost.play(it) },
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
                                        onPlaybackRequest = { playbackHost.play(it) },
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
                                        onPlaybackRequest = { playbackHost.play(it) },
                                        onSongLongClick = { menuSongState = it },
                                    )
                                }
                                // Story 11.3: owned-playlist detail & editor.
                                Routes.PLAYLIST -> {
                                    val editorVm = remember(id) {
                                        PlaylistEditorViewModel(
                                            playlistId = id,
                                            ops = RepositoryPlaylistEditorOps(graph.playlists),
                                            scope = searchScope,
                                        )
                                    }
                                    val songs by editorVm.songs.collectAsStateWithLifecycle()
                                    val editMode by editorVm.editMode.collectAsStateWithLifecycle()
                                    val summary = playlistSummaries
                                        .firstOrNull { it.playlist.id.value == id }
                                    PlaylistEditorScreen(
                                        name = summary?.playlist?.name ?: "Playlist",
                                        state = PlaylistEditorUiState(songs = songs, editMode = editMode),
                                        likedSongs = likedSongs,
                                        playlists = playlistSummaries,
                                        onPlaybackRequest = { playbackHost.play(it) },
                                        onToggleEditMode = { editorVm.toggleEditMode() },
                                        onRemoveAt = { index -> editorVm.removeAt(index) },
                                        onMove = { from, to -> editorVm.move(from, to) },
                                        onRename = { newName ->
                                            searchScope.launch { editorVm.rename(newName) }
                                        },
                                        onDelete = {
                                            searchScope.launch {
                                                editorVm.delete { navController.popBackStack() }
                                            }
                                        },
                                        onAddBatch = { songsToAdd ->
                                            searchScope.launch { editorVm.addBatch(songsToAdd) }
                                        },
                                        onSongLongClick = { menuSongState = it },
                                    )
                                }
                            }
                        },
                    )

                    // Story 12.2: Full Player overlay (state never lost on collapse).
                    FullPlayerScreen(
                        state = playback,
                        visible = fullOpen && playback.currentItem != null,
                        positionMs = livePosition,
                        liked = currentLiked,
                        onCollapse = { fullOpen = false },
                        onTogglePlayPause = playbackHost::togglePlayPause,
                        onNext = playbackHost::next,
                        onPrevious = playbackHost::previous,
                        onSeek = { playbackHost.seekTo(it) },
                        onToggleShuffle = {
                            playbackHost.setShuffleEnabled(!playback.shuffleEnabled)
                        },
                        onCycleRepeat = { playbackHost.cycleRepeatMode() },
                        onToggleLike = {
                            playback.currentItem?.let { toggleLike(it.song) }
                        },
                        onOpenQueue = { queueOpen = true },
                        qualityChip = {
                            QualityChip(current = audioQuality, onOpen = { qualityOpen = true })
                        },
                        atmosphere = atmosphere,
                        online = online,
                    )

                    // Story 12.3: Queue sheet over everything.
                    QueueSheet(
                        visible = queueOpen && playback.currentItem != null,
                        items = playbackHost.currentQueue(),
                        currentId = playback.currentItem?.id,
                        onJump = { playbackHost.jumpTo(it) },
                        onRemoveAt = { playbackHost.removeAt(it) },
                        onMove = { from, to -> playbackHost.moveQueueItem(from, to) },
                        onClearQueue = { playbackHost.clearQueue() },
                        onDismiss = { queueOpen = false },
                        atmosphere = atmosphere,
                        online = online,
                    )

                    // Story 12.4: quality selector (OQ-6 gate lives in the policy).
                    QualitySelectorSheet(
                        visible = qualityOpen,
                        current = audioQuality,
                        onSelect = { selected ->
                            searchScope.launch { settings.setAudioQuality(selected) }
                        },
                        onDismiss = { qualityOpen = false },
                    )
                    }
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
