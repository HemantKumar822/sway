# Phase 1 — Build and Module Map

**Status:** COMPLETE
**Date:** 2026-08-23
**Reference commit:** `d6636ca8ba79549643185a6e074f4da88a339880` (branch `main`)
**Blueprint:** `docs/suvmusic-research-blueprint.md` (§7, §8, §14, §15)
**Phase 0 note:** `docs/research/phase-0-acquisition.md`

All file paths below are relative to `reference/SuvMusic/` unless prefixed with our own project path. Every "verified fact" was observed directly in build files or docs at commit `d6636ca8`.

---

## Exit evidence (blueprint §15)

### Module graph

Verified from `settings.gradle.kts:19-33` (13 included Gradle modules) and each module's `dependencies {}` block.

```text
                              :app  [android APPLICATION]
        depends on ALL twelve modules below (app/build.gradle.kts:283-299)

   KMP SPINE (kotlin.multiplatform + AGP-9 KMP-aware androidLibrary + jvm("desktop"))
   ┌────────────────────────────────────────────────────────────────────┐
   │                                                                    │
   │   :core:model ──► :core:domain ◄── :core:data                      │
   │        ▲    api▲         ▲            │                            │
   │        │       │         │            │ (:core:data also uses     │
   │        │       │         └─ :composeApp│  :core:model+domain)      │
   │   :media-source ────────────────►─────┘                           │
   │        ▲ ▲ ▲                                                      │
   │   :lyric-lrclib :lyric-kugou :lyric-simpmusic                     │
   │                                                                    │
   │   :composeApp ──► :core:model, :core:domain                        │
   │   :core:db ──► (standalone SQLDelight, no project deps)            │
   └────────────────────────────────────────────────────────────────────┘

   ANDROID LIBRARY LEAVES (com.android.library + kotlin.android)
      :extractor      → external NewPipeExtractor (JitPack); NO project deps
      :media-source   → api(:core:model)
      :scrobbler      → NO project deps
      :updater        → NO project deps
```

Edge list (each verified):

| From | To | Evidence |
|---|---|---|
| `:app` | every other module | `app/build.gradle.kts:283-299` |
| `:core:domain` | `:core:model` | `core/domain/build.gradle.kts:27` |
| `:core:data` | `:core:model`, `:core:domain` | `core/data/build.gradle.kts:32-33` |
| `:core:db` | (none) | `core/db/build.gradle.kts` (only library deps) |
| `:extractor` | (none) | `extractor/build.gradle.kts:38-50` |
| `:media-source` | `:core:model` via **`api()`** (exposed transitively) | `media-source/build.gradle.kts:83` |
| `:lyric-lrclib` | `:media-source` | `lyric-lrclib/build.gradle.kts:58` |
| `:lyric-kugou` | `:media-source` | `lyric-kugou/build.gradle.kts:57` |
| `:lyric-simpmusic` | `:media-source` | `lyric-simpmusic/build.gradle.kts:57` |
| `:scrobbler` | (none) | `scrobbler/build.gradle.kts:56-73` |
| `:updater` | (none) | `updater/build.gradle.kts:48-77` |
| `:composeApp` | `:core:model`, `:core:domain` | `composeApp/build.gradle.kts:49,54` |

### Dependency inventory (area → library → version → consumers)

All versions exactly as declared in `gradle/libs.versions.toml`. Consumers verified in each `build.gradle.kts`.

| Area | Library / plugin | Version | Used by |
|---|---|---|---|
| Build | Android Gradle Plugin (`agp`) | 9.1.0 | all Android modules (`toml:2`) |
| Build | Kotlin (`kotlin`, also `ksp`) | 2.3.0 | all modules (`toml:3,36`) |
| Build | Gradle wrapper | 9.3.1 | whole build (`gradle/wrapper/gradle-wrapper.properties:4`) |
| Build | Compose compiler = Kotlin compose plugin | (bundled w/ Kotlin 2.3.0) | `:app`, `:updater`, `:composeApp` |
| UI | androidx.compose BOM | 2026.03.01 | `:app`, `:updater` (`toml:11`) |
| UI | material3 | 1.5.0-alpha16 | `:app`, `:updater`, `:composeApp` (via CMP) (`toml:12`) |
| UI | material3-adaptive | 1.3.0-alpha10 | `:app` (`toml:41`) |
| UI | Compose Multiplatform (`composeMultiplatform`) | 1.10.0 | `:composeApp` (`toml:44`) |
| UI | navigation-compose | 2.9.0 | `:app` (`toml:19`) |
| UI | Glance appwidget | 1.1.1 | `:app` (`toml:25`) |
| Playback | Media3 (exoplayer, session, ui, common, transformer, dash, ui-compose, datasource-cronet) | 1.10.1 | `:app` (all), `:core:domain` androidMain (exoplayer+common) (`toml:13`) |
| Media routing | mediarouter | 1.8.1 | `:app` (`toml:14`) |
| Network stack | cronet-embedded | 143.7445.0 | `:app` (`toml:130`) |
| Extraction | NewPipeExtractor (`com.github.TeamNewPipe`) | v0.26.4 | `:extractor`, `:app`, `:composeApp` desktopMain (`toml:15,125`) |
| Networking | OkHttp (+logging-interceptor) | 5.3.0 | `:app`, `:extractor`, `:media-source`, all lyric modules, `:scrobbler`, `:updater` (`toml:16`) |
| Networking | Ktor client (core, cio, okhttp, content-negotiation, websockets, kotlinx-json) | 3.4.0 | `:app`, `:media-source`, lyric×3, `:scrobbler`, `:composeApp`; okhttp-engine for Coil in `:composeApp` (`toml:27`) |
| Networking | Retrofit + converter-gson | 2.11.0 | `:app` only (`toml:26`) — see flaws note: comment claims it was removed but declaration remains (`app/build.gradle.kts:212-216`) |
| Serialization | kotlinx-serialization-json | 1.6.2 | `:app`, `:media-source`, lyric×3, `:scrobbler`, `:updater` (`toml:34`) |
| Serialization | Gson | 2.13.2 | `:app`, `:lyric-lrclib` (`toml:22`) |
| Images | Coil 3 (compose, network-okhttp, network-ktor3) | 3.4.0 | `:app` (okhttp engine), `:composeApp` (ktor3 engine) (`toml:17`) |
| Database | Room (runtime, ktx, compiler via KSP) | 2.8.4 | `:app`, `:core:data` (`toml:21`) |
| Database | SQLDelight (android/jvm driver, coroutines) | 2.1.0 | `:core:db` (`toml:46`) |
| DI | Hilt (+navigation-compose via hiltWork ref, hilt-work, hilt-compiler) | 2.59.1 | `:app`, `:extractor`, `:media-source`, lyric×3, `:scrobbler`, `:updater`, `:core:data`; androidx-hilt 1.2.0 for WorkManager (`toml:18`) |
| DI | Koin (core, android, compose, viewmodel) | 4.1.0 | `:app` (coexists with Hilt mid-migration) (`toml:45`) |
| Prefs | datastore-preferences | 1.1.1 | `:app` (`toml:20`) |
| Settings (KMP) | multiplatform-settings | 1.3.0 | catalog-declared; consumer not found in any module build file (see inferences) |
| Background | WorkManager runtime-ktx | 2.11.1 | `:app` (`toml:28`) |
| Native msg | protobuf-javalite / protobuf-kotlin-lite (+ plugin 0.9.6) | 4.33.5 | `:app` only (`toml:30,37`) |
| Desktop audio | VLCJ | 4.10.1 | `:composeApp` desktopMain via `:core:domain` desktopMain (`toml:48`) |
| Audio tagging | jaudiotagger | 3.0.1 | `:app` (`toml:31`) |
| HTML parsing | jsoup | 1.22.1 | `:app`, `:media-source` (`toml:23`) |
| Crash reporting | ACRA (core/http/toast/notification/dialog) | 5.13.1 | `:app` (core, notification, dialog applied) (`toml:33`) |
| Security | security-crypto | 1.1.0 | `:app` (`toml:24`) |
| Date/time (KMP) | kotlinx-datetime | 0.7.1 | `:core:model`, `:composeApp` (`toml:49`) |
| Coroutines | kotlinx-coroutines (+android, play-services, swing) | 1.9.0 | many modules (`toml:38`) |
| Core | androidx core/ktx & friends | 1.18.0 | nearly all modules (`toml:4`) |
| Lifecycle | lifecycle-runtime/viewmodel | 2.8.7 | `:app`, `:updater` (`toml:9`) |
| Desugaring | desugar_jdk_libs_nio | 2.0.4 | `:app` (`toml:35`) |
| Lint tools | detekt 1.23.8, ktlint 1.7.1 | — | versions declared in catalog only; no matching plugin aliases exist in `[plugins]` and none are applied (engineering inference: unused leftovers) |

### Build prerequisites

Verified:

- JDK **21** — every module sets `sourceCompatibility`/`jvmTarget` to Java/JVM 21 (e.g., `app/build.gradle.kts:91-96`, `extractor/build.gradle.kts:26-36`); all four CI workflows pin `java-version: "21"` (`.github/workflows/build.yml:20`, `debug.yml:20`, `release.yml:19`, `windows-build.yml:30`).
- AGP 9.1.0 + Gradle 9.3.1 wrapper (`gradle/libs.versions.toml:2`, `gradle/wrapper/gradle-wrapper.properties:4`).
- compileSdk **37**, minSdk **26** everywhere (`app`, all libraries); targetSdk 37 set only by `:app` (`app/build.gradle.kts:16-23`). `android.suppressUnsupportedCompileSdk=37` silences the AGP warning (`gradle.properties:32`).
- NDK **27.0.12077973** + CMake **3.22.1**, required **only by `:app`** (`app/build.gradle.kts:102-109`).
- Repositories: google, mavenCentral, a Google-hosted maven mirror, **JitPack**, and Sonatype snapshots (`settings.gradle.kts:1-16`).
- `android.newDsl=false` and `android.builtInKotlin=false` are mandatory workarounds for protobuf-plugin/KSP vs AGP-9 compatibility (`gradle.properties:24-29`).

### Modules excluded from our first project (proposed decisions, not facts)

| Reference module | Excluded because |
|---|---|
| `:lyric-kugou`, `:lyric-lrclib`, `:lyric-simpmusic` | Lyrics are blueprint non-goals for first release (blueprint §3). |
| `:scrobbler` | Last.fm/Discord social integration omitted initially (blueprint §7). |
| `:updater` | Self-update flow is store/distribution concern, not music architecture (blueprint §7). |
| `:composeApp` | Mid-migration KMP/desktop artifact; our client is Android-only Compose first. |
| `:core:db` (SQLDelight) | Second database created by an unfinished migration; we keep exactly one DB (Room). |
| native code under `app/src/main/cpp` | Custom C++ DSP (spatial audio, limiter…) is blueprint non-goal (§3); plain Media3 suffices. |

---

## Verified repository facts

- **13 Gradle modules** are included: `:app`, `:updater`, `:media-source`, `:scrobbler`, `:lyric-simpmusic`, `:lyric-lrclib`, `:lyric-kugou`, `:extractor`, `:core:model`, `:core:data`, `:core:domain`, `:core:db`, `:composeApp` (`settings.gradle.kts:19-33`). The blueprint's single "`core`" folder is actually four modules.
- No composite builds / `includeBuild` anywhere in upstream build scripts; the only Gradle mention of NewPipeExtractor is the JitPack catalog alias `com.github.TeamNewPipe:NewPipeExtractor:v0.26.4` (`gradle/libs.versions.toml:125`; grep over `*.kts/*.toml/*.properties` returns only this line).
- The git submodule `app/NewPipeExtractor` (pinned `4701a17…`) is uninitialized locally and referenced by **no** Gradle file (`.gitmodules:1-3`; `git submodule status` shows `-` prefix; directory empty).
- Module types:
  - `:app` — `com.android.application` + kotlin.android + compose + Hilt + KSP + protobuf + serialization (`app/build.gradle.kts:4-12`).
  - `:core:model`, `:core:domain`, `:core:db`, `:composeApp` — Kotlin Multiplatform with `androidLibrary { }` (AGP 9 KMP-aware plugin) + `jvm("desktop")` target (`core/model/build.gradle.kts:1-26`, `composeApp/build.gradle.kts:3-28`, `core/db/build.gradle.kts:1-23`).
  - `:core:data`, `:extractor`, `:media-source`, `:scrobbler`, `:updater`, lyric×3 — classic `com.android.library` + `kotlin.android` (their respective build files' `plugins {}` blocks).
- `:app` versionName **2.6.4.0**, versionCode **43**; debug build has `.debug` applicationId suffix; release enables minify + shrinkResources with R8 full mode (`app/build.gradle.kts:22-23,70-88`; `gradle.properties:36`).
- `:app` ABI-filtered to arm64-v8a + armeabi-v7a and resource-configs `en`,`hi` (`app/build.gradle.kts:27-33`).
- Compose compiler stability configuration: `composeCompiler { stabilityConfigurationFile = rootProject.file("compose-stability.conf") }` in `:app` (`app/build.gradle.kts:112-114`) and `:updater` (`updater/build.gradle.kts:38-40`); config file exists at repo root.
- `:app` opts into experimental Material3 APIs via freeCompilerArgs (`app/build.gradle.kts:119-122`).
- Room schema location exported to `$projectDir/schemas` in `:core:data` (`core/data/build.gradle.kts:8-10`); Room compiler runs through KSP there and in `:app`.
- Protobuf lite (java+kotlin builtins) generated inside `:app` only (`app/build.gradle.kts:126-142`), used for Listen-Together-style message serialization per its own comment.
- `:composeApp` produces a Windows desktop distribution (MSI/EXE, ProGuard disabled with documented rationale, packageVersion 2.5.9 tracking app 2.6.4.0) (`composeApp/build.gradle.kts:78-117`).
- `:core:domain` androidMain pulls Media3 exoplayer+common as the shared `MusicPlayer` expect/actual backend; desktopMain pulls VLCJ (`core/domain/build.gradle.kts:31-53`).
- Last.fm API keys injected as BuildConfig fields from env/local.properties into `:app`, `:media-source`, and `:scrobbler` independently (`app/build.gradle.kts:35-50`, `media-source/build.gradle.kts:18-29`, `scrobbler/build.gradle.kts:14-28`).
- `:app` consumes Cronet via embedded artifacts with an explicit exclusion of the Play-Services Cronet variant (`app/build.gradle.kts:198-201`).
- DEVELOPER.md's migration-status table is stale relative to reality: it lists `:core:domain` as "legacy ⏳ pending" (`DEVELOPER.md:47`) although its build file already uses the KMP-aware plugin, and omits `:core:db` entirely.
- ARCHITECTURE_REVIEW.md lists a `core:ui` module among existing structure (`ARCHITECTURE_REVIEW.md:39`) which does not appear in `settings.gradle.kts` — doc/reality drift.

---

## Engineering inferences (not fully traced; check in later phases)

- **Inference:** the `app/NewPipeExtractor` submodule is vestigial for building — kept so the author can read/patch extractor source locally — while actual resolution is JitPack. F-Droid's recipe proves the substitution approach works if ever needed (see Q0.2/Q0.3 below).
- **Inference:** `multiplatform-settings`, `sqldelight-*` catalog entries beyond `:core:db`, and detekt/ktlint versions appear declared-but-unused today; they are staged for later phases of the KMP migration (comments in the catalog literally say "replaces DataStore … in Phase 3c", `libs.versions.toml:203`).
- **Inference:** `:app` depending on both `:core:data` (Room repositories) *and* `:core:db` (SQLDelight) suggests dual persistence paths wired side-by-side during the migration; which one actually serves reads/writes needs a Phase 2/6 trace.
- **Inference:** Retrofit's presence in `:app` despite the "removed" comment implies dead dependency weight, not active use — but some call sites may still exist; verify in Phase 3 before drawing conclusions.
- **Inference:** because `:media-source` exposes `:core:model` via `api()`, the lyric modules get models transitively without declaring them — a deliberate convenience that couples lyrics to media-source even when unused.
- **Inference:** CI never initializes the submodule either (no checkout-with-submodules step observed in workflow names/snippets we grepped), consistent with JitPack being the real source of truth for builds.

---

## Author-admitted flaws

### From SYSTEM_DESIGN_FLAWS.md (system-level; answers Q0.1)

The document's own thesis: *"the entire system hangs off private, uncontrolled, ever-changing external services, resolved live on each device, with no backend to cache, coordinate, observe, or hotfix"* (`SYSTEM_DESIGN_FLAWS.md:184-189`).

1. **Private-API single point of failure** — metadata/streams come from YouTube's undocumented internal API; when signatures change "playback breaks for every user at once" (`SYSTEM_DESIGN_FLAWS.md:62-71`).
2. **Ephemeral, just-in-time stream URLs** — time-limited, often IP-bound URLs expire mid-session; retry logic and the "Silent Handshake" audio-sink workaround are called band-aids (`SYSTEM_DESIGN_FLAWS.md:73-84`).
3. **Fan-out third-party fragility** — RemoteAudio fallback API, 3 lyric providers, Last.fm, Discord, sync server: each an independent failure point; combined reliability multiplies downward; failures are swallowed so users see blank screens (`SYSTEM_DESIGN_FLAWS.md:86-98`).
4. **Anti-bot/rate-limit/account risk with zero request coordination** — every device scrapes YouTube directly; aggregate footprint invites blocking (`SYSTEM_DESIGN_FLAWS.md:100-110`).
5. **Online-first, no graceful degradation** — errors swallowed → empty UI; in-memory caches lost on cold start make offline launches look broken (`SYSTEM_DESIGN_FLAWS.md:112-121`).
6. **Device-dependent audio pipeline** — audio/video stream merging plus hardware-offload toggling cause per-chipset playback failures (`SYSTEM_DESIGN_FLAWS.md:123-130`).
7. **No backend ⇒ no kill-switch/hotfix/observability** — ACRA sees crashes but swallowed errors are invisible; you can only watch breakage (`SYSTEM_DESIGN_FLAWS.md:132-143`).
8. **Real-time features fragile** — Listen Together dies with sync-server latency/uptime (`SYSTEM_DESIGN_FLAWS.md:144-147`).
9. **Half-finished migrations create runtime ambiguity** — Hilt+Koin and Room+SQLDelight run side-by-side, widening startup behavior matrix (`SYSTEM_DESIGN_FLAWS.md:149-153`).
10. **ToS/legal exposure** — using YouTube's internal API likely violates ToS; provider can break access at will (`SYSTEM_DESIGN_FLAWS.md:155-158`).

### From ARCHITECTURE_REVIEW.md (code-level)

Overall verdict: "not a messy monolith … partway through a modernization migration"; bricks fine, renovation half-done (`ARCHITECTURE_REVIEW.md:9-24, 230-235`).

1. **God classes**: `YouTubeRepository` 3,471 lines, `SessionManager` 3,137, `MusicPlayer` 2,953, `MusicPlayerService` 1,821; MusicPlayer does ~8 jobs incl. transport, stream resolution, retry, quality pick, history, recommendations, Discord presence (`ARCHITECTURE_REVIEW.md:51-76`).
2. **Parallel half-finished migrations**: Hilt→Koin (bridge class named), Room→SQLDelight (both schemas maintained), Retrofit→Ktor, Android-only→KMP; "half-done migration is riskier than either end" (`ARCHITECTURE_REVIEW.md:78-97`).
3. **Coupling to concrete classes**: player/service inject concrete `YouTubeRepository` though repository interfaces already exist in `core/domain` (`ARCHITECTURE_REVIEW.md:99-113`).
4. **Business logic in wrong layers**: stream-retry inside MusicPlayer; lyrics-provider selection in PlayerViewModel; audio-focus implemented twice (`ARCHITECTURE_REVIEW.md:114-128`).
5. **Swallow-and-return-empty error handling**: catch-log-return-emptyList pattern makes "no results" indistinguishable from "offline"; fix proposed is Result/AppError types (`ARCHITECTURE_REVIEW.md:130-156`).
6. **Scattered config/cache state**: hardcoded endpoints, device IDs, cache keys spread across repositories/services (`ARCHITECTURE_REVIEW.md:158-163`).

### From DEVELOPER.md (skim)

Notable mainly for process debt: migration-status table stale vs. actual build files (see verified-facts bullet above); otherwise it documents the AGP-10 blocker ("do not bump AGP to 10 until every ⏳ row is ✅", `DEVELOPER.md:55`).

---

## Answers to Phase-0 open questions

### Q0.1 — What does the author admit is broken?

Answered above: ten system-level flaws (`SYSTEM_DESIGN_FLAWS.md`) dominated by dependence on YouTube's private API, just-in-time ephemeral stream URLs, swallowed errors, and unfinished parallel migrations; six code-level flaws (`ARCHITECTURE_REVIEW.md`) dominated by four God classes and coupling to concrete repositories. Highest-leverage lessons for us: typed error results instead of empty-list swallowing; one DI system, one database, one HTTP stack; stream resolution behind an interface with prefetch/renewal.

### Q0.2 — How is NewPipeExtractor consumed?

**Verified:** as a plain Maven dependency from **JitPack** (`com.github.TeamNewPipe:NewPipeExtractor:v0.26.4`, `gradle/libs.versions.toml:125`), consumed by `:extractor` (`extractor/build.gradle.kts:42`), `:app` (`app/build.gradle.kts:207`), and `:composeApp` desktopMain (`composeApp/build.gradle.kts:72`). There is **no** `includeBuild`/composite wiring upstream. The git submodule at `app/NewPipeExtractor` is pinned but uninitialized and unreferenced by Gradle.

**Bonus fact (F-Droid):** distribution builds replace the JitPack artifact at build time by injecting an `includeBuild(...)` with `dependencySubstitution` into settings.gradle.kts via prebuild sed commands against srclib `NewPipeExtractor@v0.26.0` (`com.suvojeet.suvmusic.yml:30-33`). Note the version skew: F-Droid substitutes v0.26.0 while the catalog pins v0.26.4 (`yml:26` vs `libs.versions.toml:15`).

### Q0.3 — Does com.suvojeet.suvmusic.yml reveal distribution constraints?

Yes, briefly: AntiFeatures **NonFreeNet** (talks to YouTube/KuGou/LrcLib — third-party non-free services) and **Tracking** (ACRA crash reporting) (`yml:1-3`, maintainer notes `yml:76-78`); license GPL-3.0-or-later (`yml:6`). Relevance to us: (a) F-Droid forbids JitPack, so any F-Droid distribution requires vendoring/substitution of the extractor; (b) the NonFreeNet classification is intrinsic to any YT-Music-scraping client, not a SuvMusic defect; (c) recorded Builds entries stop at 2.0.2.0 while CurrentVersion claims 2.6.4.0 (`yml:18-75` vs `82-83`) — the F-Droid listing appears behind upstream releases.

---

## Implications for our independent client

*(Proposed decisions — our product choices informed by this evidence, not descriptions of SuvMusic.)*

- **Proposed decision:** adopt a small fixed module set — `:app` (thin), `:core:model`, `:core:domain`, `:core:data` (Room), `:extractor` — mirroring the reference's clean spine while skipping every migration-in-flight artifact (no second DI, no second DB, no desktop target).
- **Proposed decision:** choose **one** stack per concern up front: Hilt *or* Koin (evidence favors Hilt on pure-Android: mature KSP toolchain, used by 9 of 13 reference modules), Room (not SQLDelight), Ktor *or* OkHttp/Retrofit as primary transport. The reference's biggest self-admitted pain is running two of each.
- **Proposed decision:** consume NewPipeExtractor as a versioned JitPack dependency pinned in our own catalog, wrapped immediately behind our own interface (reference wraps it in `:extractor` with no other module touching it — good pattern worth keeping conceptually).
- **Proposed decision:** require JDK 21 and accept compileSdk 37-era tooling, but avoid AGP-9-KMP-aware plugins entirely since we have no KMP targets; use standard `com.android.library`.
- **Proposed decision:** model every repository return as a typed result/error union from day one — the single cheapest fix for the flaw the author flags most emphatically across both review docs.
- **Proposed decision:** design stream resolution as a standalone `StreamResolver` behind an interface with renewal-on-expiry (and optionally next-track prefetch) rather than folding it into the player — directly counter Flaw 2 and Review-Flaw 1/4.
- **Proposed decision:** skip CMake/NDK, protobuf, Cronet, Glance, mediarouter, transformer, WorkManager-based flows, and ACRA in v1; revisit each only when the corresponding feature lands.

---

## Open questions carried forward

- [ ] Q1.1: Which persistence layer actually serves data at runtime — Room (`:core:data`) or SQLDelight (`:core:db`) — and does `HiltKoinBridge.kt` really route component creation? (Phase 2 startup trace.)
- [ ] Q1.2: Is Retrofit genuinely dead in `:app` despite remaining declared? Find live call sites, if any. (Phase 3.)
- [ ] Q1.3: What do the six native `.cpp` translation units actually expose to Kotlin (JNI surface), in case any behavior (e.g., loudness normalization) must be reproduced in JVM code? (Low priority; Phase 9/optional.)
- [ ] Q1.4: Why does `:app` declare `implementation(project(":composeApp"))` — how many screens are already delegated to commonMain? (Phase 2/7.)
- [ ] Q1.5: Does the JitPack NewPipeExtractor v0.26.4 artifact differ behaviorally from F-Droid's substituted v0.26.0 srclib? Only relevant if we pursue F-Droid distribution. (Deferred until distribution planning.)
- [ ] Q1.6: `protobuf` messages in `:app` — what protocol/schema do they serve (Listen Together?), confirming it is safely out of our v1 scope. (Phase 2 skim.)

---

*Evidence note complete. Next phase per blueprint §15: Phase 2 — Application startup.*
