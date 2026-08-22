# Phase 3 — YouTube Metadata Flow

**Status:** COMPLETE
**Date:** 2026-08-23
**Reference commit:** `d6636ca8ba79549643185a6e074f4da88a339880` (branch `main`)
**Blueprint:** `docs/suvmusic-research-blueprint.md` (§9, §14, §15 Phase 3)
**Prior notes:** `docs/research/phase-0-acquisition.md` · `phase-1-module-map.md` · `phase-2-startup.md`
**Corrections applied:** `docs/decisions/0001-blueprint-corrections.md`

All paths below are relative to `reference/SuvMusic/` unless prefixed with our project path. Labels: **[FACT]** = directly observed at commit d6636ca8; **[INFER]** = engineering inference from observed code, no runtime trace.

> **Headline correction to Phase 1 / Decision 0001 (D-02):** the "YouTubeRepository ~3,471 LOC god class" figure came from `ARCHITECTURE_REVIEW.md:51`, which is **stale at this commit**. Commit `72235a51` ("feat(youtube+hqaudio+player): modularize YT layer …", verified via `git log --follow`) split it into nine focused services under `app/src/main/java/com/suvojeet/suvmusic/data/repository/youtube/`; today's `YouTubeRepository.kt` is a **343-line facade** whose own KDoc says it is "the seam they're reached through" so "~40 call sites across the app didn't have to learn the new layout" (`YouTubeRepository.kt:39-47`). The god-class lesson stands as a budget for our design, but SuvMusic no longer exhibits the pattern in this layer at this commit.

---

## Exit evidence (blueprint §15, Phase 3)

### Request-to-model diagram — one song search

```text
SearchViewModel.searchInternal(query)                     ui/viewmodel/SearchViewModel.kt:378
      |   (ALL-filter fires FIVE parallel async{} calls; single filter fires one)
      v
YouTubeRepository.search(query, FILTER_SONGS)             data/repository/YouTubeRepository.kt:118
      |   offline guard: if (!isOnline()) return emptyList()
      v
YouTubeSearchService.search(query, filter)                .../youtube/search/YouTubeSearchService.kt:47
      |   cacheKey = "yt:$filter:<lowercased query>"                        :50
      v
NewPipeExtractor (JitPack v0.26.4)
   ServiceList.all().find { name == "YouTube" }                             :52
   ytService.getSearchExtractor(query, listOf(filter), "")                  :55
   searchExtractor.fetchPage()                                              :56
      |   HTTP via :extractor NewPipeDownloaderImpl (OkHttp + cookies)      extractor/.../NewPipeDownloaderImpl.kt:21
      v
searchExtractor.initialPage.items                         <- List<InfoItem>
      |   filterIsInstance<StreamInfoItem>()              <- EXTRACTOR TYPE, service-local :58
      v
per-item mapping -> Song.fromYouTube(videoId, title, ...) core/model/.../Song.kt:45-75
      .   blank videoId => factory returns null (item dropped)             Song.kt:58
      .   TitleSanitizer.clean(title, artist)                              Song.kt:63
      .   null thumbnail => constructed maxresdefault.jpg URL              Song.kt:67
      v
List<Song> --(non-empty)--> OfflineCache.putSearch(cacheKey, songs)       search svc :89
      v
returned to ViewModel -> SearchUiState.results            SearchViewModel.kt:407-415

FAILURE PATH (any exception inside try):                  search svc :91-96
   e.printStackTrace()
   Telemetry.report("search","youtube", e.toAppError(), ...)
   return OfflineCache.getSearch(cacheKey) ?: emptyList()
```

### Proposed independent model sketch (our own design — field lists only)

Deliberate differences from the reference are marked; all names are placeholders.

- **Track** (our Song equivalent): id (video id); displayTitle (cleaned); rawTitle kept separately (*improvement*: the reference overwrites and loses the original); artistName; artistId?; albumName?; albumId? (reference carries an album *name* only on some paths and never an id); durationMs; artwork (an Artwork value, not a bare URL string); source enum; isVideo; playlistMembershipId? (setVideoId analogue); removalToken?; addedAt timestamp.
- **Album**: id; title; artistName; artistId?; year?; artwork; trackCount?; description?; tracks.
- **Artist**: id; name; artwork; subscriberText?; description?; topTracks; albums; singles; videos; relatedArtistPreviews.
- **PlaylistSummary vs PlaylistDetail** (*splitting the reference's near-duplicate Playlist / PlaylistDisplayItem shapes into explicit summary/detail*): summary = id, title, ownerName, artwork, trackCount?; detail adds tracks plus continuation state.
- **HomeShelf**: title; layoutKind enum; items = sealed ShelfItem union (Track | Album | Artist | Playlist | ExploreLink) — with no Android resource IDs inside the shared model.
- **Artwork** (*value type instead of String?*): canonicalUrl; knownVariants list; precomputed fallback chain (rules below).
- Every fetch returns our typed result wrapper — the reference built exactly these types but never wired them into this layer (verified in the error section).

### Artwork fallback rules observed

1. Parse time: pick the **last** entry of the InnerTube thumbnails array (largest variant present) [FACT] (`internal/YouTubeJsonParser.kt:121-134`; same last-element rule for header variants :136-158).
2. Header art falls back to the cropped-square renderer when standard renderers are absent, and an album with no header art reuses its first track's thumbnail [FACT] (`internal/YouTubeContentParser.kt:263-268`, `:924-929`).
3. Model time: a YouTube song with no thumbnail URL gets one synthesized from the video id via the img.youtube.com maxresdefault pattern [FACT] (`core/model/.../Song.kt:67`).
4. Load time (media-session bitmaps): build a **descending candidate list per host family** — ytimg/youtube hosts are upgraded to maxresdefault then tried as sddefault → hqdefault → mqdefault with the original URI appended last; googleusercontent/ggpht hosts get their `=wN-hN` / `=sN` sizing params rewritten to 1080 → 720 → 544 [FACT] (`service/CoilBitmapLoader.kt:139-176`). A failing variant logs and continues to the next (:60).
5. Notification artwork upgrades lower variants to maxresdefault by string replacement before loading [FACT] (`player/MusicPlayer.kt:3668-3674`).
6. The opposite direction also exists — a util downgrades any variant to hqdefault for constrained contexts, and some UI screens construct maxres URLs straight from the video id as a guess [FACT] (`util/ImageUtils.kt:19`; `ui/screens/player/components/PlayerArtwork.kt:334`; `ui/components/SongInfoSheet.kt:395`). **[INFER]** Two competing fix-up directions plus direct construction mean the same track can load different resolutions on different surfaces; only the CoilBitmapLoader chain implements real 404-fallback.

### Test cases for missing fields (to write against OUR implementation)

1. Blank video id from parser → item dropped; never construct a Track with an empty key.
2. Null/empty thumbnails array → synthesized fallback URL; loader must walk sd → hq → mq when maxres 404s.
3. Duration absent on a two-row grid card → treated as album/playlist tile, not a zero-length track (reference rule, `YouTubeContentParser.kt:71-76`).
4. Missing artist → stable placeholder text, never a crash; byline splitting tolerates fewer than two segments.
5. Missing year → null in our model (reference maps year via 4-digit regex or passes the whole subtitle through, `YouTubeContentParser.kt:276` — we should not inherit that fuzziness).
6. Corrupt disk-cache payload containing wrong-typed elements → reject the entry and delete the file (reference behavior after their v2.5.1.0 Gson/R8 ClassCastException crash, `cache/OfflineCache.kt:127-145`).
7. Empty response body → parse yields empty list without throwing.
8. Continuation token absent/malformed → single-page result, no infinite loop (break on empty page AND cap page count).
9. HTTP 429 inside extractor transport → surfaced as ReCaptchaException by the downloader (`extractor/.../NewPipeDownloaderImpl.kt:76-79`), classified RateLimited by our mapper, stale cache served.
10. Search while offline → typed NoNetwork failure, visually distinct from "genuinely no results".

---

## Search call chain (class-by-class with citations)

| # | Class | File (under `app/src/main/java/com/suvojeet/suvmusic/` unless noted) | Role [FACT] |
|---|---|---|---|
| 1 | `SearchViewModel` | `ui/viewmodel/SearchViewModel.kt:86-92` (Koin-resolved per phase 2) | Debounced input — 250 ms for suggestions, 650 ms for full search, min length 2 (:120-136). `ResultFilter.ALL` runs songs+videos+artists+playlists+albums as five parallel `async` calls (:393-397); songs and videos merged with distinct-by-id (:405). |
| 2 | `SearchUiState` | same file :54-83 | Four separate result lists + suggestions + error string + loading flags. |
| 3 | `YouTubeRepository` | `data/repository/YouTubeRepository.kt:118-131` | Online guard, then forwards to `YouTubeSearchService`; re-exports the filter constants (:67-71). |
| 4 | `YouTubeSearchService` | `data/repository/youtube/search/YouTubeSearchService.kt:30-34` | Hilt singleton; owns OkHttp client, SessionManager, JSON helpers. |
| 5 | NewPipeExtractor SearchExtractor | invoked at :52-56 via `ServiceList` | getSearchExtractor(query, [filter], "") + fetchPage(); filters are the NewPipe tokens music_songs/music_videos/music_albums/music_playlists/music_artists (:37-41). Only `initialPage` is read (:58) — see pagination table. |
| 6 | `NewPipeDownloaderImpl` | `extractor/src/main/java/com/suvojeet/suvmusic/newpipe/NewPipeDownloaderImpl.kt` | The custom extractor transport: OkHttp-backed Downloader, injects session cookies (:45-49), selects desktop-vs-Android User-Agent (:54-62), converts HTTP 429 to ReCaptchaException (:76-79), caps bodies at 10 MB returning an empty string above that (:86-107). |
| 7 | `Song.fromYouTube` + `TitleSanitizer` | `core/model/src/commonMain/.../Song.kt:45-75`, `core/model/.../TitleSanitizer.kt` | Mapping happens **inline in the service**, item by item — no dedicated mapper class on this path. Blank videoId → null; upload-noise stripped from titles once at source; per-item exceptions logged-and-dropped (`android.util.Log.w("YouTubeSearch", "dropped search item ...")`, :84). |
| 8 | `OfflineCache` | `cache/OfflineCache.kt:81-153` | Non-empty results persisted as Gson envelope JSON keyed by a hash of the lowercased query (:73-78). |
| 9 | `Telemetry` + `toAppError()` | `data/error/ErrorMapper.kt:19-39` | Failure classification used **for reporting only** on this path (:93). |

Suggestions bypass all of this — a plain GET to suggestqueries-clients6.youtube.com scraping the JSON array out of the body, capped at 8 results (`YouTubeSearchService.kt:218-256`), no extractor involved.

## Repository surface map (operations grouped by domain)

All forwardings verified in `data/repository/YouTubeRepository.kt`; line ranges per group. Nine injected sub-services at :49-61.

| Domain | Operations | Delegates to |
|---|---|---|
| Account (:104-112) | fetchAccountInfo · getAvailableAccounts · switchAccount | YouTubeAccountService (88 LOC) |
| Search (:114-131) | search(query, filter) · searchArtists · searchPlaylists · searchAlbums · getSearchSuggestions | YouTubeSearchService (370 LOC) |
| Streams (:133-207) | getStreamUrl · getVideoStreamUrl · getVideoStreamResult · getStreamUrlForDownload · getMuxedVideoStreamUrlForDownload · getSongDetails · getRelatedSongs (dedupe by id + title/artist fingerprint, :158-188) · getBestVideoId | YouTubeStreamingService; related songs fall back search → streaming with uploader heuristics (:159-173) |
| Browse (:209-226) | getRecommendations · getHomeSections · getHomeSectionsForMood · getBrowseSections · getMoodsAndGenres · getCategoryContent | YouTubeBrowseService (343 LOC) |
| Playlists (:228-294) | getUserPlaylists · getUserEditablePlaylists · getLikedMusic · syncLikedSongs · removeFromLikedCache · getCachedPlaylist · getPlaylist · getPlaylistFlow · getAutoMixPlaylist · createPlaylist · addSong(s)ToPlaylist · addSongsToAnyPlaylist · removeSong(s)FromPlaylist · removeSongsFromAutoPlaylist · isAutoGeneratedPlaylist · isLocalPlaylist · moveSongInPlaylist · renamePlaylist · deletePlaylist | YouTubePlaylistService (590 LOC) |
| Catalog (:296-311) | getArtist · getAlbum · getLibraryArtists · getLibraryAlbums · getArtistRadioId · getArtistTopSongs | YouTubeCatalogService (95 LOC) |
| Library actions (:313-336) | rateSong · ratePlaylist · subscribe · fetchAndSyncHistory · fetchYouTubeMusicHistory · fetchYouTubeHistory · markAsWatched | YouTubeLibraryActionService (133 LOC) |
| Lyrics (:338-342) | getLyrics | YouTubeLyricsService (35 LOC) |

Static surface: FILTER_* constants and getLanguageCode (:62-75); isOnline/isLoggedIn (:100-102); one-time NewPipe.init bootstrap launched from init{} on an external scope (:82-98).

## Model inventory (our-relevant fields only)

All in `core/model/src/commonMain/kotlin/com/suvojeet/suvmusic/core/model/` — pure-Kotlin data classes; field lists paraphrased:

- **Song** (`Song.kt:15-40`): video-id identity; cleaned title; artist string; album string; duration ms; thumbnail URL?; source enum (YOUTUBE / YOUTUBE_MUSIC / LOCAL / DOWNLOADED / REMOTE, :111-117); stream URL resolved at playback time?; local URI stringified?; playlist-instance id (setVideoId)?; one-shot removal feedback token for auto-generated playlists?; artist channel id?; pre-download original source?; isVideo flag; download folder/collection bookkeeping (three fields); members-only flag; release date?; added-at timestamp; remote-source metadata blob?
  - Factory contract: blank videoId ⇒ null return (caller drops the item); title sanitized via regex bracket/segment stripping that never returns empty (falls back to raw); thumbnail synthesized from id when absent.
- **Album** (`Album.kt`): id; title; artist string; year?; thumbnailUrl?; description?; tracks list.
- **Artist** (`Artist.kt`): id; name; thumbnailUrl?; description?; subscribers as pre-formatted display text?; songs; albums; singles; isSubscribed flag; channelId?; views as display text?; videos; related-artist previews (own small data class: id/name/thumbnail/subscribers); featured playlists.
- **Playlist** (`Playlist.kt`): id; title; author; thumbnailUrl?; songs; description?; totalSongCount?.
- **PlaylistDisplayItem** (`PlaylistDisplayItem.kt`): id; name; url; uploaderName; thumbnailUrl?; songCount; description? — derives playlist id from the web URL when the id field is blank; a second near-duplicate playlist shape.
- **HomeSection / HomeItem / HomeSectionType** (`HomeSection.kt`): shelf = title + sealed item union (song / playlist-summary-with-preview-tracks / album / artist / explore tile) + layout enum inferred from shelf-title keywords (`internal/YouTubeContentParser.kt:639-645`). The explore tile carries an Android drawable Int inside commonMain — flagged in-code as needing platform indirection.
- **BrowseCategory** (`BrowseCategory.kt`): title; browseId; params?; thumbnailUrl?; color long?. The app also mints synthetic categories whose browseId carries a "SEARCH::" prefix meaning "run this text as a search instead" (`YouTubeBrowseService.kt:196-211`, fallback list :357-366).

Extractor→model mapping rules observed [FACT]: artist = first segment of a bullet-separated subtitle (`YouTubeJsonParser.kt:100-103`); year = first 4-digit segment (:105-119); duration parsed from lengthText or fixed-column text (:160+); item kind decided by navigation endpoint — watch endpoint ⇒ playable track, browse-id prefix VL/PL/RD/LM ⇒ playlist, MPRE/OLAK ⇒ album, UC ⇒ channel (`YouTubeContentParser.kt:906-911`); playable items must carry a watchEndpoint while bare browseEndpoints are skipped as non-tracks (:51-59).

## Boundary analysis (how well extractor types are hidden; leak points)

**Mechanism [FACT]:** there is **no interface** hiding NewPipeExtractor. The boundary is conventional layering:

- `org.schabi.newpipe.*` imports exist in exactly six files repo-wide (grep-verified): `:extractor`'s downloader impl; composeApp desktopMain's separate desktop search/download pair; and four `:app` data-layer files — `YouTubeRepository` (bootstrap only: NewPipe.init + Localization, :33-34), `YouTubeSearchService` (ServiceList, StreamInfoItem, :20-21), `YouTubePlaylistService` (ServiceList, ListExtractor, StreamInfoItem, :21-23), `YouTubeStreamingService` (:15-17).
- No ViewModel, UI screen, or core:* module touches extractor types; every public repository signature uses core:model types exclusively.

**Leak points / weaknesses:**

1. `:app` declares the JitPack extractor dependency directly (`app/build.gradle.kts:207`, phase-1 inventory) instead of receiving it only through `:extractor` — nothing structural stops new call sites anywhere in `:app` importing extractor types. [FACT]
2. Raw JSON strings form the *second* untyped boundary: every YouTubeApiClient method returns bare String bodies (`internal/YouTubeApiClient.kt:75-125`), handed straight to parsers. Schema drift surfaces as silent empties, not compile errors. [FACT]
3. The explore-tile model carries an Android resource Int through the shared KMP model module (`HomeSection.kt:33-42`). [FACT]
4. PlaylistDisplayItem embeds a web URL as its identity fallback (parse `list=` out of it). Wire format leaking into a model. [FACT]

**Verdict:** extractor-type containment works in practice (UI layer is clean), but it is enforced by discipline rather than by the dependency graph. Our design should make the leak structurally impossible — the extractor dependency visible only to our wrapper module.

## Pagination, caching, error handling (with exact failure-path citations)

### Pagination [FACT]

| Surface | Mechanism | Cap | Citation |
|---|---|---|---|
| Search | **None.** Only initialPage.items is read; getNextPage never called for search | first page only | `YouTubeSearchService.kt:55-58` |
| Liked-songs playlist view | while-loop over extracted continuation token → POST /browse with ctoken+continuation params; emits partial playlist every 4th page so long lists appear progressively | 200 pages (in-code comment: ~20k songs) | `YouTubePlaylistService.kt:216-236`; request builder `YouTubeApiClient.kt:111-125` |
| Browse playlists | same loop | 100 pages normally; 5 for auto-mixes (RD/RTM prefixes) because endless mixes stall the Android Auto callback window | `YouTubePlaylistService.kt:286-306` |
| Full liked-songs sync | accumulate every page in memory before one DB write so the count doesn't visibly tick up mid-sync | 500 pages | `YouTubePlaylistService.kt:104-139` |
| Public playlists (authenticated browse refuses them) | NewPipe-native paging: hasNextPage / getPage(nextPage) | until exhausted | `YouTubePlaylistService.kt:344-366` |
| Home sections | up to 3 continuations appended; breaks on empty body or exception | 3 attempts | `YouTubeBrowseService.kt:98-112` |
| Token extraction | five fallback JSON shapes scanned (continuationContents → shelf continuations → inline continuationItemRenderer → sectionList continuations → onResponseReceivedActions append items) | — | `internal/YouTubeJsonParser.kt:256-435` |

### Caching [FACT unless noted]

| Layer | Contents | TTL / eviction | Citation |
|---|---|---|---|
| OfflineCache (disk JSON under filesDir/offline_search + offline_lists) | song/artist/album/playlist search results per query; written only when non-empty | "Fresh" window 6 h, but stale entries ARE returned by the offline/error fallback paths; LRU prune at 80 entries per dir; corrupt payloads deleted on read | `cache/OfflineCache.kt:29-34`, `:63-78`, `:250-257`; staleness semantics :101-153 |
| SessionManager (DataStore + encrypted-prefs JSON mirrors) | home_cache · remote_home_cache · quick_picks_cache · library_playlists_cache · library_liked_songs_cache · recent searches | no TTL observed; wiped by explicit cache-clear flows (key list :1991-1994) | `data/SessionManager.kt:107-114`, `:2875-2901` |
| ViewModel throttle (cache-then-network) | HomeViewModel renders cached sections first, then refetches only when forced, cooldown expired, or cache empty | 30-minute fetch cooldown | `ui/viewmodel/HomeViewModel.kt:235-265` |
| Room via LibraryRepository | playlist mirror incl. liked songs (savePlaylist / replacePlaylistSongs / getCachedPlaylistSongs) — the only DB-backed metadata cache; Room v12 entities carry no dedicated YT-cache table | none | `YouTubePlaylistService.kt:92-102`, `:124-133`, `:145-157`; phase-2 entity list |
| Coil image caches | artwork bitmaps | memory 30 %, disk 150 MB | phase-2 note #1 |
| In-memory metadata LRU | **none found** for YT metadata at this commit | — | grep over app data layer |

### Error handling on metadata paths

**Confirmed: swallow-and-return-empty persists across YouTube metadata paths**, softened by telemetry reporting and disk-cache fallback. Exact sites:

| Site | On failure returns | Citation |
|---|---|---|
| Facade offline guard | emptyList() before any network call (search/artists/playlists/albums/suggestions); null for getStreamUrl | `YouTubeRepository.kt:119,122,125,128,131,134` [FACT] |
| Song search catch block | printStackTrace → Telemetry.report(e.toAppError()) → OfflineCache.getSearch ?: **emptyList()** | `YouTubeSearchService.kt:91-96` [FACT] |
| Artist / playlist / album search catch blocks | identical shape → **emptyList()** | `YouTubeSearchService.kt:133-137,171-175,208-212` [FACT] |
| Suggestions catch | **emptyList()** | `YouTubeSearchService.kt:252-255` [FACT] |
| Related songs (facade) | try/catch each source independently → emptyList(), then dedupe | `YouTubeRepository.kt:159-173` [FACT] |
| parseSongs | two nested silent catches — whole-response AND per-item (`catch (e: Exception) { }`) | `internal/YouTubeContentParser.kt:90,92` [FACT] |
| InnerTube transport | non-2xx or network error → logged, returns **empty string body** (which parses as zero items) | `internal/YouTubeApiClient.kt:51-66` [FACT] |
| getArtist / getAlbum | **null** (album retries once without the VL prefix first) | `catalog/YouTubeCatalogService.kt:34-37,46-54` [FACT] |
| Home/mood/category browse | printStackTrace → emptyList(); category content instead falls back to a literal text search of the title | `YouTubeBrowseService.kt:116-119,252-255,315-318` [FACT] |
| Recommendations | layered degradation chain: quick picks shelf → any song shelf → parsed songs → liked music → followed-artist search → trending search | `YouTubeBrowseService.kt:47-79` [FACT] |

**The typed-error apparatus exists but is not connected here.** AppResult/AppError live in core:model with KDoc stating they exist to fix exactly this flaw (`core/model/AppResult.kt:5-10`; `AppError.kt:5-14`), and the Throwable→AppError mapper lives in `app/data/error/ErrorMapper.kt:19-39`. Repo-wide usage check [FACT]: AppResult is consumed only in MusicPlayer.kt:1599, DownloadRepository.kt:1713, and RemoteAudioRepository.searchResult (:226-293). Zero uses in the YouTube metadata services — toAppError's only role there is feeding Telemetry strings.

**Consequence at the ViewModel [INFER]:** because repositories never rethrow, SearchViewModel's try/catch that maps exceptions to user-friendly messages (:489-496) can almost never fire on the YouTube tab — failures arrive as ordinary empty lists, so its error state stays null and "no network" / "schema drift" / "genuinely nothing found" are indistinguishable in the UI.

## Verified repository facts

1. The YT layer was modularized into nine sub-services behind a 343-line facade; ARCHITECTURE_REVIEW.md's god-class figure predates commit 72235a51. [FACT — file tree + git log --follow]
2. Two transports coexist: NewPipeExtractor (search + playlist/streaming fallbacks) and a hand-rolled InnerTube WEB_REMIX client — client name/version pinned in `internal/YouTubeConfig.kt:8-9` — returning raw JSON for browse/catalog/playlists/related. [FACT]
3. NewPipe.init runs once per process via a companion isInitialized flag in the repository init coroutine; a failed init is swallowed and never retried, after which extractor-backed paths fail and callers fall through to InnerTube paths. [FACT] (`YouTubeRepository.kt:62-98`)
4. Search results, artists, albums and playlists are disk-cached per query with stale-tolerant offline fallback. [FACT] (`cache/OfflineCache.kt`)
5. Search has no pagination; playlists/home paginate with caps of 200 / 100 / 5 / 3 pages depending on surface. [FACT]
6. Song.fromYouTube rejects blank ids and sanitizes titles at construction time, in commonMain, shared by all parsers. [FACT] (`Song.kt:45-75`)
7. Extractor types appear only in four app data-layer files + the downloader module + desktopMain; never in UI/ViewModel/core. [FACT — repo-wide import grep]
8. The InnerTube action layer detects HTTP-200-with-error-body rejections (status ≠ STATUS_SUCCEEDED) that would otherwise fake success for playlist edits. [FACT] (`YouTubeApiClient.kt:255-271`)
9. Album browse ids from search (OLAK…) need a VL prefix while MPRE… does not; the catalog service handles both plus one retry without the prefix. [FACT] (`YouTubeCatalogService.kt:40-55`)
10. The extractor downloader caps response bodies at 10 MB, returning empty above that — an OOM guard against stream URLs misrouted into metadata calls. [FACT] (`NewPipeDownloaderImpl.kt:86-107`)

## Engineering inferences

- **[INFER]** ResultFilter.ALL fires five parallel extractor fetches per settled query — the app's most rate-limit-prone pattern; their own 429 logging in the downloader corroborates that rate limiting happens in practice.
- **[INFER]** getRelatedSongs' title+artist fingerprint dedupe implies upstream radio/mix endpoints routinely return the same recording under multiple video ids; expect duplicates in any queue built from those endpoints.
- **[INFER]** Because parseSongs swallows per-item errors silently while the search service logs them, InnerTube-based surfaces (browse/catalog) are strictly harder to debug than the extractor path — schema drift shows up only as missing rows.
- **[INFER]** OfflineCache's R8/Gson envelope design (concrete envelope classes + serialized-name annotations + runtime element-type validation) exists because R8 full mode stripped List generics once and caused a shipped crash; any reflection-based JSON caching we build needs equivalent defense.
- **[INFER]** The 30-min home cooldown plus TTL-less SessionManager caches mean most cold starts show yesterday's shelves indefinitely until a successful refresh replaces them.

## Implications for our independent client

*(Proposed decisions — our product choices informed by this evidence, not descriptions of SuvMusic.)*

- **Proposed decision:** adopt the facade-over-focused-services shape deliberately from day one — thin repository, separate search/browse/catalog/playlist collaborators, sized well under the D-02 budgets.
- **Proposed decision:** route ALL metadata failures through our typed result type at the repository boundary. Telemetry may also fire, but callers receive Failure(NoNetwork | RateLimited | Parse | …) — never an empty list that lies. This closes exactly the gap SuvMusic documented but did not finish wiring.
- **Proposed decision:** wrap NewPipeExtractor behind our own interface in a module whose build file is the ONLY place the dependency appears; no other module can see org.schabi.* types even accidentally.
- **Proposed decision:** keep two transports conceptually (extractor for search resilience, direct InnerTube-style client for browse depth), but both present typed inputs/outputs through our wrapper — raw JSON Strings never cross a module boundary.
- **Proposed decision:** paginate search too (extractor nextPage behind a paged API) instead of capping users at page one; keep page caps for playlist fan-out, lower for endless mixes, protecting background/auto surfaces as the reference does.
- **Proposed decision:** model Artwork as a value object carrying its variant chain computed once at parse time (maxres → sd → hq → mq for img.youtube hosts; size-param rewrite for ggpht hosts) so every surface resolves identically and 404-fallback is uniform — replacing the reference's three ad-hoc fix-up sites.
- **Proposed decision:** keep a small disk cache of recent search results for offline launch — the best idea in this phase — built on a crash-safe serializer with strict entry validation; honor TTL on fresh reads and permit stale data only in explicit offline mode.
- **Proposed decision:** sanitize display titles once at the model boundary (the TitleSanitizer concept is sound) but preserve the raw title alongside for library search and "show original" affordances.

## Open questions carried forward

- [ ] Q3.1: Runtime behavior of NewPipeExtractor v0.26.4 search with vs without session cookies (logged-out result quality, regional gating) — needs a device run.
- [ ] Q3.2: Long-term header/consent requirements of the suggestqueries suggestions endpoint (not observable from source).
- [ ] Q3.3: Real-world frequency of InnerTube schema drift breaking musicResponsiveListItemRenderer parsing — measurable only via telemetry over time.
- [ ] Q3.4: Whether the 10 MB body cap ever discards legitimate large browse payloads (very large playlists fetched through the extractor fallback path). Needs a device test with a >10k-song playlist.
- [ ] Q3.5: Does any surface depend on PlaylistDisplayItem.url being non-blank (id derivation path)? Static read suggests all producers set id, but not proven exhaustively.

---

*Evidence note complete. Blueprint §15 Phase 3 exit evidence delivered above. Next phase per blueprint: Phase 4 — Stream resolution (carrying Q1.2 answer: Retrofit is live but HQ-Audio-only, confirmed in phase-2 note #1 of the DI table and this phase's boundary grep showing no Retrofit imports in YT services).*


