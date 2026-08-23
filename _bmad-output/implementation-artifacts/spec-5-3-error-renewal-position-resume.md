---
title: 'Story 5.3 - Error-triggered renewal with position resume'
type: 'feature'
created: '2026-08-23'
status: 'done'
review_loop_iteration: 0
baseline_commit: cfc030c3e09e2eef0f1b504337d032d6c5324f78
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 5.3)'
  - '{project-root}/_bmad-output/planning-artifacts/prd.md (FR-13)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (AD-7 layer 2; NFR-3; P-5; SM-2 bounds table)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-2-read-time-validation-layer.md (layer-1 precedent)'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Layer 1 (story 5.2) prevents stale URLs from being handed to the player, but a URL can still die mid-play (CDN purge between handoff and last byte). When ExoPlayer raises a source-class error (HTTP 403/410 family arriving via `Player.STATE_IDLE` + `PlaybackException` source error codes), the listener currently hears a dead stream. AD-7 defense layer 2 requires: detect the error, classify it, renew the stream invisibly (invalidate + deduped forceRefresh resolve), swap the fresh URL in place, land back within +/-3 s of the last audible position, and restore (or preserve) the playing intent — the listener must not perceive a restart. Bounded retries prevent hot loops; exhausted budgets surface the typed `SwayError` category on `PlayerUiState.failedTrack`; non-expiry-class errors surface immediately without renewal attempts.

**Approach:** Extend `JitResolveEngine` (:playback, the single owner of resolution paths) with an error-event path: `onPlayerError` -> internal `handlePlayerError(errorCode)` seam. Pure classification/renewal law lives in `JitPolicy` (named, P-5-tunable constants: source-error code class, `RESUME_TOLERANCE_MS`, `MAX_RENEWALS_PER_EPISODE`). On a retryable source-class error over a RESOLVED current item with audible-progress evidence (captured position > 0 or observed playing), the engine synchronously captures resume position + play intent, then runs a single-flight-per-source renewal coroutine: bounded `invalidate(trackId)` + `AudioRequest.refresh()` resolve attempts (<= MAX_RENEWALS_PER_EPISODE per progress-episode), applying Success via `replaceMediaItem` (mediaId scan) + `seekTo(captured)` + `prepare()` + conditional `play()`, publishing typed `FailedTrack` on Failure/exhaustion through the existing `onFailure`/`latestFailure` slots. Budgets reset on successful progress (isPlaying/READY observation). `SwayPlaybackService`'s idle self-stop gains an error-awareness guard so an error-driven `STATE_IDLE` never trips NFR-10 self-stop while the renewal layer owns recovery.

## Boundaries & Constraints

**Always:**
- Resume position is captured synchronously at error time (live read preferred, ticker snapshot fallback) and restored via explicit `seekTo` within +/-3 s (mechanism restores exactly).
- Renewal rides the shared resolver path: `invalidate` then `forceRefresh = true` resolve (same shape as 5.2's read-time renewal).
- Exactly ONE renewal pipeline per SourceId at a time (single-flight map); concurrent duplicate errors coalesce into one resolve (dedup verified under gating).
- Retries hard-bounded by `MAX_RENEWALS_PER_EPISODE` (= 2) resolve attempts per SourceId per progress-episode; budget resets ONLY on successful-progress observation; exhausted budget surfaces the typed category instead of resolving.
- Non-source-class (fatal) errors publish typed failure immediately with ZERO renewal attempts.
- Errors over PLACEHOLDER current items never enter renewal (JIT worker owns placeholders).
- Every failure travels typed (`FailedTrack`) — never a crash, never an escaped exception.

**Ask First:** N/A — headless run; engineering decisions recorded in Design Notes.

**Never:** No changes outside `:playback` main/test sources plus docs/spec/sprint-status (no :catalog, no :core:model — existing vocabulary sufficient); no watchdog/stall logic (5.4 owns the 3 s/15 s ladder); no UI work beyond the existing `failedTrack` slot; no SettingsRepository wiring (quality stays AUTO per 5.1/5.2 decisions).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| AC1: mid-play 403 | source-class error (code 2004) at captured position 45 s, was playing | invalidate + deduped forceRefresh resolve; fresh URL replaces item (mediaId scan); seek lands within +/-3 s of 45 s; playing intent restored; zero perceived restart | Bounded |
| AC2: two simultaneous 410s, same SourceId | second `handlePlayerError` while first renewal gated in-flight | Exactly ONE fresh resolve executes (single-flight coalescing) | Duplicates skipped |
| AC3: renewal keeps failing | refresh resolve Failure x2 (budget) | Typed `SwayError` surfaces on `latestFailure` -> `uiState.failedTrack`; NO third attempt; further triggers draw zero resolves until progress resets budget | Typed value, no hot loop |
| Budget reset | successful progress observed (isPlaying/READY) after a spent episode | Next error-triggered renewal runs again with full budget | N/A |
| Fatal error class | e.g. decoding error 4001 / remote 1001 | Immediate typed `Unknown` failure; ZERO invalidate/resolve calls; player untouched | Typed value |
| Was paused | error at captured position with playWhenReady = false | Renewal proceeds (audible position existed); stays PAUSED after resume-position restore | N/A |
| Placeholder current item | error while item still rides PendingUri | Skipped — JIT transition path owns placeholders (also neutralizes environmental prepare noise) | Silent skip |
| No audible-progress evidence | resolved item, position 0, playing never observed | Skipped — renewal is layer 2 for MID-play death; pre-play failures belong to layer 1/JIT/watchdog backstop | Silent skip |
| Idle self-stop interplay | error-driven STATE_IDLE inside service (playing or paused) | Self-stop NOT triggered while player error present; renewal layer owns recovery | N/A |
| Item vanished mid-renewal | mediaId scan misses after resolve | Skip silently (4.4 semantics); no spurious failure | Silent skip |
| SM-2 record | 20 forced-expiry trials, varied captured positions | 20/20 resume within +/-3 s; record emitted to test output | N/A |

</frozen-after-approval>

## Code Map

- `playback/src/main/kotlin/com/sway/playback/JitResolveEngine.kt` -- JitPolicy: `SOURCE_ERROR_CODE_MIN/MAX`, `RESUME_TOLERANCE_MS`, `MAX_RENEWALS_PER_EPISODE`, `isExpiryRetryableSourceError(errorCode)`, `mapFatalPlayerError(code)` helpers; engine: `onPlayerError` override -> `handlePlayerError(errorCode)` (capture sourceId/position/play-intent -> eligibility -> budget -> single-flight renewal job -> bounded invalidate+refresh-resolve loop -> apply-or-publish), progress-ticker snapshot vars updated by extended listener callbacks (`onIsPlayingChanged`, `onPlaybackStateChanged`, `onMediaItemTransition`), `noteSuccessfulProgress()` budget reset; KDoc citing FR-13/AD-7 layer 2/NFR-3.
- `playback/src/main/kotlin/com/sway/playback/SwayPlaybackService.kt` -- idle self-stop condition gains `player.playerError == null` guard (error-driven IDLE owned by renewal/watchdog layers); KDoc story-5.3 note.
- `playback/src/test/kotlin/com/sway/playback/ErrorTriggeredRenewalTest.kt` -- NEW Robolectric suite (hermetic players via never-completing DataSource factory): full I/O matrix incl. SM-2 20-trial record, policy boundary tables, facade-slot surfacing, service self-stop interplay.
- `_bmad-output/implementation-artifacts/sprint-status.md` -- row 5.3 status + Evidence log entry at completion.
- `_bmad-output/implementation-artifacts/spec-5-3-error-renewal-position-resume.md` -- this spec; status -> done at completion.

## Tasks & Acceptance

**Execution:**
- [x] `JitPolicy`: named constants + pure classification/mapping/tolerance helpers with boundary-table coverage.
- [x] `JitResolveEngine`: error-event detection, synchronous position/intent capture, eligibility filter (resolved-item + audible-progress evidence), per-source single-flight renewal with bounded attempts, budget reset on progress, typed surfacing, apply-sequence (replaceMediaItem -> seekTo -> prepare -> conditional play).
- [x] `SwayPlaybackService`: error-aware idle self-stop guard.
- [x] `ErrorTriggeredRenewalTest.kt` NEW: AC1 tolerance + was-playing/was-paused, AC2 dedup-under-concurrency, bounded-retry-then-typed-failure via `uiState.failedTrack`, budget reset, fatal immediate-surface, placeholder/no-evidence skips, service self-stop NOT tripped, SM-2 20-trial record.

**Acceptance Criteria:**
- Given a playing stream that fails with a source-class error mid-play, when recovery completes, then the audible resume lands within +/-3 s of the lost position across 20 forced trials (SM-2 100% pass, record emitted).
- Given two simultaneous 410-class errors for the same SourceId, when both handlers react, then exactly one fresh resolve executes (dedup verified under concurrency via gating).
- Renewal failure surfaces the typed category on `PlayerUiState.failedTrack` instead of retrying forever (bounded at `MAX_RENEWALS_PER_EPISODE` per progress-episode; budget resets after successful progress).
- Non-retryable errors surface immediately without renewal attempts.
- An error-driven `STATE_IDLE` never trips the service's idle self-stop during the renewal flow.

## Spec Change Log

## Completion Record (2026-08-23)

- `:playback:testDebugUnitTest` **73/73 green** = 61 prior (13 JitResolveEngine, 9 ReadTimeValidation, 13 PlayerConnection, 21 QueueBuilder, 1 FirstAudioTimingHarness, 4 SwayPlaybackService) + **12 NEW ErrorTriggeredRenewalTest**: SM-2 forced-expiry record "20/20 trials resumed within +/-3000 ms; max deviation=0 ms" emitted to test output; mid-play 403 renewal (invalidate + forceRefresh + fresh URL mediaId-scan swap + exact position resume + playing intent restored + zero typed failure); was-paused renews on position evidence and stays paused; bounded retry exactly MAX_RENEWALS_PER_EPISODE=2 attempts then typed Offline through engine slot -> conn.setFailedTrack -> uiState.failedTrack, further triggers draw ZERO extra resolves; budget resets after successful progress so the next expiry renews with a fresh budget; concurrent duplicate errors (gated resolver) coalesce into EXACTLY ONE resolve; fatal classes (4001 decoding / 1001 remote) surface immediately with zero invalidate/resolve; placeholder current items skip layer 2 (JIT owns them); no-audible-progress-evidence scenario skips silently; vanished item mid-renewal applies silently without spurious failure; service idle self-stop NOT tripped by the renewal flow in playing AND paused variants; JitPolicy boundary table pins the source window (1999/3000/4001/1001 outside; 2000/2004/2005/2999 inside), category mapping, clamp law and eligibility truth table.
- `:core:model:test` 118, `:catalog:testDebugUnitTest` 123 (2 liveSmoke skipped), `:core:data:testDebugUnitTest` 8 — all unchanged green.
- `scripts/check_placeholder_scheme.sh` exit 0; `scripts/check_module_edges.sh` exit 0; `:app:assembleDebug` BUILD SUCCESSFUL.

## Design Notes

- **Engine-owned layer 2 (cohesion):** renewal lives inside `JitResolveEngine`, not a new class — it is the single owner of resolve vocabulary (`safeInvalidate`/`safeResolveAudio`/`publishFailure`/mediaId scan), and a separate listener would create ordering hazards against the existing JIT/prefetch listeners. `handlePlayerError(errorCode)` is an internal seam mirroring `handlePendingCurrent`; production enters via the `onPlayerError` override.
- **Classification window:** retryable-expiry class == `PlaybackException` source codes `[2000..2999]` (`ERROR_CODE_IO_BAD_HTTP_STATUS`=2004 carries HTTP 403/410; `ERROR_CODE_IO_FILE_NOT_FOUND`=2005 covers CDN purges answering 404). Fatal classes map to `SwayError.Unknown(cause)` preserving diagnostics; exhausted-budget triggers deterministically surface `UpstreamUnavailable` for source-class codes — while the FIRST trigger surfaces the resolver's own typed failure (e.g. Offline) from the spent loop. Both behaviors pinned by test.
- **Eligibility filter is load-bearing twice:** semantically, layer 2 exists for MID-play death, so audible-progress evidence (captured position > 0 OR playing observed) is required; mechanically, it makes every PRIOR suite structurally immune to environmental prepare noise. Probe evidence (this sandbox): fetches to sway://pending and https://cdn.example.com park in BUFFERING forever — no error ever arrives here; but a fast-DNS CI could deliver real errors, which would otherwise react with renewals inside prior suites' exact-count assertions. All prior-suite scenarios hold position==0 with never-observed playback or placeholders, so they can never trip renewal regardless of environment.
- **Position capture:** synchronous at error time — live `player.currentPosition` preferred (ExoPlayer retains the error position until prepare), falling back to the service-side progress ticker snapshot maintained by the engine's listener callbacks (`lastAudibleProgressMs`). The mechanism restores the captured value exactly; `RESUME_TOLERANCE_MS=3000` remains the named P-5 bound for production wall-clock drift (SM-2 asserts within-tolerance AND observes exact restoration).
- **Budget semantics:** `MAX_RENEWALS_PER_EPISODE=2` resolve attempts per SourceId per progress-episode. ONE trigger may spend consecutive attempts internally (invisible recovery beats early surfacing); a trigger arriving at a spent budget publishes immediately with zero resolves (no hot loop). Budgets reset globally when healthy progress is observed (`noteSuccessfulProgress` from isPlaying=true or STATE_READY) — a deliberate simplification: any successful playback ends all episodes. Transitions reset sticky-playing evidence since each item must re-earn it.
- **Apply sequence:** `replaceMediaItem` (identity/mediaId preserved) THEN `seekTo(clamped captured)` (replace can reset position; explicit seek guarantees resume) THEN `prepare()` (mandatory to leave an error-driven IDLE) THEN `play()` only if playWhenReady was true — paused users stay paused. Item vanished mid-renewal skips silently (4.4 semantics).
- **Idle self-stop interplay:** the service guard now requires `player.playerError == null`. Consequence: an error-driven IDLE never stops the service even when paused — deliberate; the renewal/watchdog layers own recovery and the session stays user-controllable (onDestroy still stops). The pre-existing non-error path is unchanged (verified by the untouched SwayPlaybackServiceTest suite).
- **Hermetic acceptance evidence:** unit tests build players through `DefaultMediaSourceFactory` over a never-completing `DataSource` (CountDownLatch-gated open()), parking every fetch in BUFFERING deterministically on any machine — the +/-3 s core never depends on host DNS behavior. The service-level test cannot inject a datasource factory (service builds its own player), so it asserts only noise-immune facts (self-stop flag, invalidation occurred, tolerance, intents — no exact counts).
- **Quality unchanged:** renewal requests ride `AudioRequest.refresh()` (AUTO) exactly like 5.2's read-time renewal; settings wiring stays deferred to consumer epics per 5.1's recorded decision.
- **Self-review loop fixes applied:** (1) corrected spent-budget surfacing expectation (mapped UpstreamUnavailable, documented above); (2) rewrote the placeholder-skip fixture — the engine JIT-resolves pending items by design, so the fixture must fail the JIT resolve to legitimately hold a placeholder; (3) removed an unused import and a dead helper; (4) replaced `java.lang.Object.wait()` with a `CountDownLatch` (Kotlin-correct); (5) fixed a nonexistent PlaybackException constant reference. Re-verified full battery green after each fix.

## Verification

**Commands:**
- `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :playback:testDebugUnitTest` -- expected: prior 61 + new suite all green
- `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :core:model:test :catalog:testDebugUnitTest :core:data:testDebugUnitTest` -- expected: 118 / 123 / 8 unchanged green
- `"C:\Program Files\Git\bin\bash.exe" scripts/check_placeholder_scheme.sh; "C:\Program Files\Git\bin\bash.exe" scripts/check_module_edges.sh` -- expected: exit 0 both
- `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleDebug` -- expected: BUILD SUCCESSFUL
