# Decision 0001 — Blueprint corrections from Phase 1 evidence

**Date:** 2026-08-23
**Evidence:** `docs/research/phase-1-module-map.md` (reference commit `d6636ca8`)
**Supersedes:** assumptions in `docs/suvmusic-research-blueprint.md` §5/§7

## Corrections (verified repository facts vs our earlier assumptions)

1. **`composeApp` is NOT the main UI layer.** It is a mid-migration Kotlin Multiplatform/desktop artifact that `:app` only partially delegates to. Blueprint §7 treated it as the primary Compose reference — wrong. **Our UI study target for later phases is `:app`'s Compose code.**
2. **`core` is four modules**, not one: at minimum `core:model`, `core:domain`, `core:data`, `core:db`. Spine: `core:model` ← `core:domain` ← (`composeApp`, `core:data`).
3. **NewPipeExtractor is consumed from JitPack (`v0.26.4`)**, not from the git submodule. The submodule is vestigial — referenced by no Gradle file; only F-Droid's prebuild substitutes it. Phase 0 plan to init the submodule is cancelled.
4. **Reference runs two DI systems (Hilt 2.59.1 + Koin 4.1.0) and two databases (Room 2.8.4 + SQLDelight 2.1.0) simultaneously** — residue of unfinished migrations. This is a warning sign, not a pattern to imitate.
5. Native audio (NDK 27, C++23, ~6 files) lives only in `:app`.

## Decisions taken

- D-01: Our independent client must pick exactly ONE DI framework and ONE database up front (architecture phase will decide which).
- D-02: God-class sizes reported by the author (YouTubeRepository ~3,471 LOC, SessionManager ~3,137, MusicPlayer ~2,953) become hard budget limits in our architecture: repositories/services stay small and layered.
- D-03: Error-handling flaw ("swallow and return emptyList") becomes an explicit non-pattern: every failure path must produce a typed, observable state.
