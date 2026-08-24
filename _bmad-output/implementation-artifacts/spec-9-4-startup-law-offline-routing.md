---
title: 'Story 9.4 - Startup law & offline launch routing'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: <9.3-commit>
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 9.4)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AR-9/AD-10 -> NFR-1)'
---

## Intent

Startup never awaits network/data: splash dismisses on composition; post-composition host invokes the 7.3 session-restore hook; connectivity truth raises/clears the OfflineBanner app-wide; offline launches route to Library with banner raised (NFR-1 completes here).

## Code Map

`app/src/main/kotlin/com/sway/music/connectivity/ConnectivityObserver.kt` -- StateFlow<Boolean> online; single synchronous initial probe (fails OPEN to online rather than wrongly flagging offline), registerDefaultNetworkCallback live updates best-effort.
`core/database/src/main/kotlin/com/sway/core/database/SwayDatabaseFacade.kt` -- SwayDatabaseHandles + top-level builders (file/in-memory) so storage types stay inside core:data/core:database.
`core/data/src/main/kotlin/com/sway/core/data/AppDataGraph.kt` -- owned-data facade: library/playlists/history/sessionRestore repos over Room-backed stores; singleton from(context) + inMemory(test).
`app/src/main/kotlin/com/sway/music/MainActivity.kt` -- real shell wiring: SwayTheme(dark from system) -> NotificationPermissionRationale (6.3 preserved) -> collect online -> startTab = LIBRARY when offline at launch -> Scaffold(SwayNavHost(banner)) -> LaunchedEffect(Unit) post-composition restore hook (runCatching-degraded).

## Design Notes

1. Restore-hook contract verified at repository level (7.3 suites) + composition-order law (LaunchedEffect runs post-first-frame by construction); visual Mini consumption = E12.
2. Macrobenchmark cold-start gate: DEFERRED as a device-gated item (no hardware in this loop); recorded for E14 NFR-1 regression run — same honesty pattern as fr8/fr16/fr21/fr25 harnesses.
3. Robolectric shadow cannot emulate NET_CAPABILITY_INTERNET faithfully → connected-case probe deferred to device matrix; offline detection IS hermetically proven.

## Verification

OfflineLaunchRoutingTest 3/3 green (observer offline via shadow-null network; offline launch renders Library slot + full banner copy AND asserts Home absent; online config opens Home without banner). :app 19 green; StartupHygieneTest untouched-green (NFR-1 substrate).
