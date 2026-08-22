# SuvMusic Research Notebook and Independent Client Blueprint

**Status:** Research and architecture planning
**Date:** 2026-08-21
**Reference repository:** [suvojeet-sengupta/SuvMusic](https://github.com/suvojeet-sengupta/SuvMusic)
**Reference branch:** `main`
**Project rule:** Study the reference implementation; build an independent client with its own code, product identity, and UI.

## 1. Purpose of this document

This document is the working map for understanding SuvMusic before building our own music client. It is intentionally more detailed than a normal project README. It should become the shared reference for the user, future AI agents, implementation tasks, issue breakdowns, testing, and architectural decisions.

The document answers:

- What are we trying to build?
- Why are we studying SuvMusic?
- What does SuvMusic contain today?
- Which ideas should we preserve?
- Which systems should we remove or replace?
- How does information travel from search to playback?
- What should be investigated directly in the cloned repository?
- In what order should the independent client be designed and implemented?
- What questions must be answered before each phase?
- What must an AI agent prove before moving to the next phase?

This is a research blueprint, not a request to build the application immediately.

## 2. Product goal

We want to create an independent Android music client that uses YouTube/YouTube Music as the primary catalogue and playback source, while providing a different, polished interface inspired by our own design goals.

The intended product direction is:

```text
YouTube Music catalogue
        +
our own data layer
        +
our own playback experience
        +
our own liquid-glass-inspired UI
        +
local library and playlists
```

The app is not intended to be a copy of SuvMusic. SuvMusic is our reference for architecture, edge cases, feature boundaries, and practical Android music-player problems. The new client must have its own name, visual language, code organization where appropriate, and product decisions.

## 3. Scope and non-goals

### In scope

- Android application development with Kotlin and Jetpack Compose
- YouTube/YouTube Music metadata discovery
- song, album, artist, and playlist models
- artwork loading and caching
- stream-resolution boundary
- Media3-based playback
- queue management
- background playback
- notification and lock-screen controls
- local library
- local playlists
- listening history
- a new UI design
- step-by-step study of the reference repository

### Not in the first version

- SuvMusic’s HQ Audio source
- remote 320-kbps server routing
- source switching between HQ Audio and YouTube
- custom C/C++ audio engine
- parametric equalizer
- spatial audio
- pitch and time-stretch processing
- Listen Together
- cloud backup
- Spotify importing
- social integrations
- advanced recommendation engine
- multiple lyric providers
- ringtone creation
- widgets
- Android Auto browsing
- casting
- sophisticated download management

These may become future projects, but they must not complicate the first architecture.

## 4. Important boundary: facts, inferences, and decisions

Every future research note should label information using one of these categories:

### Verified repository fact

Something directly observed in the repository, its source code, its documentation, its build files, or its official changelog.

### External technical fact

Something verified in official Android, Kotlin, Media3, Room, or library documentation.

### Engineering inference

An interpretation of how the code works when a complete execution trace is not yet available. Inferences must be labelled and later checked against source code.

### Our product decision

A deliberate choice for the independent client. It should not be described as if SuvMusic made that choice.

### Open question

Something that requires code inspection, a local build, a device test, legal/product clarification, or a future design decision.

This separation prevents accidental copying of assumptions.

## 5. Reference repository overview

The current SuvMusic repository presents itself as a high-fidelity Android music application built around Jetpack Compose, a multi-module structure, Room, Media3, image caching, networking, extraction, and a custom native audio engine. It also includes multiple external service integrations and a separate HQ Audio path.

The repository’s major top-level areas include:

```text
app
composeApp
core
extractor
media-source
lyric-kugou
lyric-lrclib
lyric-simpmusic
scrobbler
updater
docs
gradle
```

There are also build files, the Gradle wrapper, release material, changelogs, configuration, and a NewPipeExtractor-related submodule/reference visible in the Android app area.

The repository documentation describes sections for application architecture, modular architecture, dependency injection, data flow, playback, native audio, multi-source streaming, lyrics, downloads, social features, database management, UI, testing, performance, and deployment.

The repository is licensed under GPL-3.0. This matters if any source code is copied or incorporated. Studying ideas and behavior is different from copying implementation code. Before reusing code, the license, attribution, source-distribution, and compatibility consequences must be reviewed carefully.

Reference sources:

- [SuvMusic repository](https://github.com/suvojeet-sengupta/SuvMusic)
- [SuvMusic changelog](https://github.com/suvojeet-sengupta/SuvMusic/blob/main/CHANGELOG.md)
- [SuvMusic releases](https://github.com/suvojeet-sengupta/SuvMusic/releases)

## 6. First architecture map

The reference application can be understood as several connected systems:

```text
User interface
      ↓
Screen state and navigation
      ↓
Repositories and application logic
      ↓
┌──────────────┬─────────────�─────────┬───────────────┐
│ YouTube data │ HQ Audio source │ Local storage │
└──────────────┴─────────────────┴───────────────┘
      ↓
Audio-source selection and stream resolution
      ↓
Media3 / ExoPlayer
      ↓
Media session and Android controls
      ↓
Phone speakers, wired headphones, or Bluetooth
```

The independent client should initially simplify this to:

```text
Our UI
   ↓
Our screen state
   ↓
Our repositories
   ↓
YouTube data and extraction boundary
   ↓
YouTube stream-resolution boundary
   ↓
Media3 playback service
   ↓
Android audio and media controls
```

The database and image cache support the system but should not become hidden dependencies of every screen.

## 7. Module responsibilities

### `app`

Likely Android-specific responsibilities include application startup, Android services, permissions, notifications, manifest configuration, and platform integrations. This must be confirmed by reading its source tree and Gradle file.

**Our plan:** keep an Android application module, but keep it thin. It should connect Android to the shared application code rather than contain all business logic.

### `composeApp`

This is the main visible Compose application area in the current repository structure. It is the main reference for screen organization, navigation, themes, components, and layout behavior.

**Our plan:** create a new UI layer with a different visual system. Study screen responsibilities and state handling; do not copy visual assets, layout code, names, or branding.

### `core`

This is the likely shared center for models, database, repositories, settings, and reusable application logic. Exact ownership must be confirmed from package names and module dependencies.

**Our plan:** maintain a small core containing stable models, shared result/error types, playback state contracts, and library interfaces.

### `extractor`

This contains the YouTube-related extraction boundary or supporting implementation. The reference uses NewPipe Extractor for metadata extraction and stream-related work.

**Our plan:** study the boundary and failure handling. Keep the dependency behind our own interfaces so the rest of the app is not tied to external response formats.

### `media-source`

This exists because SuvMusic supports multiple audio sources, including YouTube, HQ Audio, and local audio.

**Our plan:** retain the concept of an audio-source interface, but implement only the YouTube path first. Local files can be a later source.

### `lyric-*`

These modules separate different lyric providers.

**Our plan:** postpone lyrics. When added, use a provider interface so one failing provider does not break playback.

### `scrobbler`

External listening-history integrations belong here.

**Our plan:** omit initially.

### `updater`

This supports application update behavior.

**Our plan:** omit initially. Store normal app updates outside the core music architecture.

## 8. Dependency strategy

The first project should use a deliberately small dependency set:

| Area | Candidate technology | Why it exists | First release? |
|---|---|---|---|
| Language | Kotlin | Main Android language | Yes |
| UI | Jetpack Compose | Build screens declaratively | Yes |
| UI foundation | Material 3 | Accessibility and baseline components | Yes, customized |
| Async work | Coroutines and Flow | Network/database work without freezing UI | Yes |
| Playback | AndroidX Media3 | Player, session, queue, system controls | Yes |
| Extraction | NewPipe Extractor or approved equivalent | Interpret YouTube data | Research first |
| Networking | Ktor or OkHttp | Network transport | Yes, choose one primary path |
| Images | Coil | Artwork loading and caching | Yes |
| Database | Room | Structured local library | Yes |
| Preferences | DataStore | Small settings and flags | Yes |
| Dependency injection | Hilt or Koin | Shared object creation | Choose one |
| Native audio | C/C++ and JNI | Advanced processing | No |
| Lyrics | Provider modules | Lyrics | No |
| Social | WebSocket or service-specific libraries | Listen Together/social features | No |

Versions must be read from the current reference build files and checked against official documentation at implementation time. Do not copy an old version number from a changelog into a new project automatically.

## 9. End-to-end data flow

### Search

```text
User enters query
      ↓
Search ViewModel receives intent
      ↓
Repository calls catalog service
      ↓
Extractor/network layer requests YouTube data
      ↓
Parser converts external results
      ↓
Repository returns clean app models
      ↓
UI displays songs, albums, artists, and playlists
```

### Song model

The app should own its own model, conceptually similar to:

```text
Song
  stable source ID
  title
  artist name and ID if available
  album name and ID if available
  duration
  artwork variants
  source information
```

The exact model must be designed independently after inspecting the reference models. The model should not expose raw extractor classes to the UI.

### Artwork

```text
Metadata response
      ↓
thumbnail URL variants
      ↓
our Artwork model
      ↓
Coil request
      ↓
memory/disk cache
      ↓
Compose image
```

Artwork URL normalization and fallback behavior must be tested because the reference changelog records high-resolution thumbnail work and artwork failures.

### Playback

```text
User taps Song
      ↓
Playback coordinator receives Song
      ↓
stream resolver obtains a current playable URL
      ↓
Song becomes Media3 MediaItem
      ↓
PlaybackService sends it to ExoPlayer
      ↓
MediaSession exposes state and controls
      ↓
Android plays audio
```

Metadata lookup and stream resolution are separate operations. Search may succeed while stream resolution fails.

## 10. Playback architecture

The independent client should use one long-lived playback service:

```text
Compose UI
      ↓ commands and observation
MediaController
      ↓
MediaSessionService
      ├── MediaSession
      └── ExoPlayer
```

Android’s official Media3 guidance recommends placing the player and media session in a service for background playback. See:

- [Jetpack Media3 overview](https://developer.android.com/media/media3)
- [Background playback with MediaSessionService](https://developer.android.com/media/media3/session/background-playback)
- [MediaSession reference](https://developer.android.com/reference/androidx/media3/session/MediaSession)

The first player must prove:

- play
- pause
- seek
- next
- previous
- queue order
- shuffle
- repeat
- background playback
- notification controls
- lock-screen controls
- recovery from a stale stream URL

Advanced audio processing must not be placed between ExoPlayer and the audio output until the basic path is stable.

## 11. Local data architecture

Room should hold structured user data. Files should hold large media. Coil should own image caching. Memory should hold temporary UI state.

### First database concepts

```text
SongEntity
PlaylistEntity
PlaylistSongEntity
HistoryEntity
DownloadEntity (later)
```

The source ID should be stable and unique. Playlist membership should be represented separately so one song can belong to multiple playlists.

### Permanent versus temporary data

| Data | Storage | Reason |
|---|---|---|
| liked song | Room | User expects it to remain |
| local playlist | Room | Structured user data |
| playback history | Room | User-facing history |
| theme preference | DataStore | Small setting |
| current stream URL | Memory/short-lived cache | May expire |
| search results | Memory/cache | Can be refreshed |
| artwork bitmap | Coil cache | Image cache responsibility |
| downloaded audio | File storage | Large unstructured data |
| downloaded-file status | Room | Structured tracking |

Room’s model is based on entities, DAOs, and a database class; Android recommends Room over direct SQLite for structured app data. [Android Room documentation](https://developer.android.com/training/data-storage/room)

## 12. UI map

### Main destinations

```text
Home
Search
Library
```

### Detail destinations

```text
Album
Artist
Playlist
```

### Global playback surfaces

```text
Mini player
Full player
Queue
```

### Supporting destinations

```text
Settings
About
```

The mini player should remain available above ordinary navigation. The player itself should be backed by the global playback service, not by the screen that happens to display it.

## 13. Independent UI direction

The target visual direction is a refined, artwork-aware, liquid-glass-inspired Android interface.

The effect should be treated as a system, not as transparency applied randomly:

```text
Artwork background
      ↓
blur and color extraction
      ↓
dark/light scrim
      ↓
translucent surface
      ↓
edge highlight and depth
      ↓
readable content
```

The UI should prioritize:

- readability over decoration
- consistent spacing
- clear hierarchy
- smooth but restrained motion
- strong artwork presentation
- accessible contrast
- responsive layouts for different screen sizes

SuvMusic is useful as a study reference for player transitions, artwork backgrounds, expressive shapes, navigation treatment, and performance work. The independent client must create its own components and visual rules.

## 14. What to study directly in the cloned repository

When the repository is available locally, inspect in this order:

### Build and module files

- `settings.gradle.kts`
- root `build.gradle.kts`
- `gradle.properties`
- `gradle/libs.versions.toml` if present
- every module’s `build.gradle.kts`
- `.gitmodules`

Record:

- included modules
- module dependencies
- plugin versions
- Android SDK levels
- Kotlin version
- Compose version
- Media3 version
- Room version
- extractor version
- image-loader version
- native build settings

### Application entry and dependency setup

Find:

- `Application` class
- `MainActivity`
- manifest
- service declarations
- dependency-injection modules
- navigation root

Record the startup sequence:

```text
process starts
      ↓
Application created
      ↓
dependencies created
      ↓
database/player services created
      ↓
Activity starts
      ↓
navigation and first screen appear
```

### YouTube/data code

Find:

- repository interfaces and implementations
- catalog/browse/playlist services
- parser classes
- source models
- app models
- continuation/pagination handling
- retry and error mapping
- stream resolver

Trace one real search call and one real playback-resolution call.

### Playback code

Find:

- ExoPlayer creation
- playback service
- MediaSession creation
- MediaController creation
- queue manager
- media item conversion
- notification provider
- audio focus and noisy-device handling
- stream retry and renewal

### Database code

Find:

- Room database class
- entities
- DAOs
- migrations
- repositories
- database version history
- cache tables
- download tables

### UI code

Find:

- navigation routes
- root scaffold
- bottom navigation
- mini-player component
- full-player screen
- Home/Search/Library routes
- detail screens
- state holders and ViewModels
- common UI components

The result of this inspection should be a source-backed module and call graph, not merely a folder summary.

## 15. Research phases

### Phase 0 — Repository acquisition and safety

Goal: obtain the reference source without modifying it.

Tasks:

- clone the repository into a separate reference folder
- record commit hash and branch
- inspect license
- check submodules
- do not build changes inside the reference checkout
- create a separate project for the independent client

Exit evidence:

- repository opens
- commit recorded
- submodules understood
- license recorded
- clean working tree

### Phase 1 — Build and module map

Goal: understand how the reference project is assembled.

Tasks:

- read settings and build files
- map modules and dependencies
- identify essential and optional libraries
- note native build requirements

Exit evidence:

- module graph
- dependency inventory
- build prerequisites
- list of modules excluded from our first project

### Phase 2 — Application startup

Goal: understand what happens before the first screen appears.

Tasks:

- trace Application startup
- trace dependency creation
- trace database creation
- trace player/service initialization
- trace navigation startup

Exit evidence:

- startup sequence diagram
- first-screen state source
- initialization failure points

### Phase 3 — YouTube metadata

Goal: understand search, parsing, artwork, and details.

Tasks:

- trace song search
- trace album/artist/playlist search
- inspect parser models
- inspect pagination
- inspect errors and empty states
- trace artwork URL selection

Exit evidence:

- request-to-model diagram
- clean independent model proposal
- artwork fallback rules
- test cases for missing fields

### Phase 4 — Stream resolution

Goal: understand how a song ID becomes a playable URL.

Tasks:

- locate stream resolution code
- record the inputs and outputs
- identify format selection
- identify URL expiry behavior
- identify retry and renewal
- separate YouTube from HQ Audio logic

Exit evidence:

- stream-resolution sequence
- independent interface definition
- failure-state table
- no HQ Audio dependency in our design

### Phase 5 — Playback foundation

Goal: prove one song can play reliably.

Tasks:

- create Media3 player
- create playback service
- create media session
- connect UI through MediaController
- test notification and lock screen
- test queue and background behavior

Exit evidence:

- one-song playback test
- background playback test
- notification controls test
- stale-stream recovery test

### Phase 6 — Local data

Goal: make user actions persistent.

Tasks:

- design entities
- create DAOs
- create migrations
- save likes
- create playlists
- save history
- restore queue state

Exit evidence:

- schema diagram
- migration plan
- repository tests
- restart-and-restore test

### Phase 7 — Functional shell

Goal: connect data, playback, and basic screens.

Tasks:

- Home
- Search
- Library
- album/artist/playlist details
- mini player
- full player
- queue

Exit evidence:

- search-to-playback flow works
- saved song appears in Library
- playlist can be created and played
- app survives navigation while audio continues

### Phase 8 — Visual system

Goal: build the independent design language.

Tasks:

- colors and typography
- surfaces and glass treatment
- artwork color extraction
- mini-player transition
- full-player transition
- motion rules
- accessibility and contrast

Exit evidence:

- reusable component catalogue
- design tokens
- screen-by-screen visual review
- performance check during animation

### Phase 9 — Hardening

Goal: handle real-world failure.

Tasks:

- offline states
- slow network
- empty results
- extractor changes
- expired streams
- image failures
- database migrations
- process death
- audio focus changes
- Bluetooth route changes

Exit evidence:

- failure matrix
- retry policy
- logging plan
- regression tests

### Phase 10 — Optional features

Only after the core is stable:

- lyrics
- downloads
- widgets
- Android Auto
- recommendations
- import/export
- advanced audio effects

Each optional feature requires a separate decision and should not silently expand the core architecture.

## 16. Questions the project must answer

### Product questions

- What is the app’s name and identity?
- Is the first release online-only, or should it support local files?
- Is account login required?
- What library actions are essential?
- Should playlists be local-only or synchronized with YouTube?
- Which languages must be supported?
- What Android versions are supported?

### Technical questions

- Which exact reference commit are we studying?
- Which extraction dependency is legally and technically appropriate?
- Which parts of the reference use submodules?
- What is the minimum stream format Media3 can play reliably?
- How are expired stream URLs renewed?
- Which network library should be primary?
- Hilt or Koin?
- Is Compose Multiplatform needed, or is Android-only Compose enough?
- What is the minimum Room schema?
- How will migrations be tested?

### Playback questions

- Does playback continue after the screen closes?
- What happens when the app is removed from recents?
- What happens after a phone restart?
- How does the player handle Bluetooth disconnects?
- How does it handle phone calls?
- What happens if the current stream fails?
- How is the last position restored?
- How are shuffle and repeat represented?

### UI questions

- What is the primary navigation model?
- Is the mini player always visible?
- What is the full-player expansion behavior?
- How much blur is practical on a mid-range phone?
- How do we preserve text contrast over bright artwork?
- Which animations are essential and which are decoration?
- How does the design adapt to tablets and landscape?
- How will accessibility labels and touch targets be handled?

### Legal and service questions

- What are the current terms governing the selected data and playback access method?
- Are any third-party service restrictions relevant to the intended distribution?
- Which code, if any, is being reused rather than independently reimplemented?
- What license obligations apply to reused code or libraries?
- How will attribution and notices be included?
- What user data leaves the device?

These questions should be answered before the relevant implementation phase, not all at once.

## 17. AI-agent workflow

Future AI agents should work from this document and follow a controlled loop:

```text
Read phase goal
      ↓
Inspect relevant reference code
      ↓
Write an evidence note
      ↓
Propose independent design
      ↓
Implement one narrow change
      ↓
Run focused tests
      ↓
Record result and open questions
```

An agent must not:

- copy an entire reference module without review
- introduce HQ Audio into the first client
- change multiple architectural layers in one unreviewed task
- claim a path is understood without tracing code
- replace a failed extraction or playback test with a mock without documenting it
- add a dependency without explaining its purpose
- mix reference code and independent code in the same working directory casually

Each future issue should contain:

- objective
- source files to inspect
- expected behavior
- non-goals
- acceptance criteria
- tests
- evidence to record
- follow-up questions

Issues should be created later from the phases in this document. They are intentionally not generated yet.

## 18. First implementation target

The first technical target is not a finished app. It is a narrow vertical proof:

```text
Known YouTube item
      ↓
metadata object
      ↓
artwork displayed
      ↓
stream resolved
      ↓
Media3 plays one song
      ↓
background playback works
```

After that, add search, then the database, then the library, then the UI system.

## 19. Definition of architectural success

The architecture is successful when:

- the UI does not know extractor implementation details
- the extractor does not know UI details
- stream URLs are treated as temporary
- the player survives navigation
- the database survives app restarts
- artwork loading is cached independently
- HQ Audio is absent from the first release path
- a failed network request produces a useful state instead of a crash
- one module can be tested without starting the whole app
- a future agent can change one layer without rewriting all other layers

## 20. Current conclusion

SuvMusic is valuable as a mature reference because it exposes the real complexity of a modern music app: external metadata, artwork, stream resolution, background playback, queues, local storage, caching, UI state, failures, and optional services.

Our strategy is therefore:

```text
Study the reference deeply
        ↓
Record verified behavior
        ↓
Separate general ideas from SuvMusic-specific code
        ↓
Remove HQ Audio and unnecessary features
        ↓
Design our own boundaries
        ↓
Prove the data and playback path
        ↓
Add persistence
        ↓
Build our own UI
        ↓
Harden and expand carefully
```

The immediate next research action is to obtain a clean local clone, record the exact commit, and inspect the actual Gradle files, module dependencies, application entry point, navigation root, YouTube repository, stream resolver, playback service, Room database, and main screens. The next notebook update should be source-file-specific rather than another high-level summary.
