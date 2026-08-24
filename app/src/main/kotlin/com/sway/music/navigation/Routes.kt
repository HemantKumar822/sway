package com.sway.music.navigation

/**
 * Typed route table (story 9.3, FR-26): three top-level tabs; detail and
 * utility destinations registered NOW so every screen E10/E11/E15 builds is
 * reachable within two taps from launch. Deep-link fallback parent = Library
 * (UX landing-mode rule 7).
 */
object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"

    const val ALBUM = "album/{albumId}"
    const val ARTIST = "artist/{artistId}"
    const val CATALOG_PLAYLIST = "catalogPlaylist/{playlistId}"
    const val PLAYLIST = "playlist/{playlistId}"
    const val LIKED = "liked"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    fun album(id: String) = "album/$id"
    fun artist(id: String) = "artist/$id"
    fun catalogPlaylist(id: String) = "catalogPlaylist/$id"
    fun playlist(id: String) = "playlist/$id"
}
