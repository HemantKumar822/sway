# Phase 0 — Repository Acquisition and Safety

**Status:** COMPLETE
**Date:** 2026-08-23
**Blueprint:** `docs/suvmusic-research-blueprint.md` (§15, Phase 0)

## Exit evidence checklist

| Requirement | Result |
|---|---|
| Repository opens | ✅ Cloned to `reference/SuvMusic` |
| Commit recorded | ✅ `d6636ca8ba79549643185a6e074f4da88a339880` |
| Branch | ✅ `main` |
| Submodules understood | ✅ See below |
| License recorded | ✅ GPL-3.0 (verified in local `LICENSE` file) |
| Clean working tree | ✅ `git status --porcelain` → 0 lines |

## Verified repository facts

- **Reference commit under study:** `d6636ca8ba79549643185a6e074f4da88a339880` on branch `main`. All research notes from Phases 1–4 are valid against this commit only.
- **License:** GNU General Public License v3.0. Consequence: we must NOT copy source code into the independent client unless the whole client becomes GPL-3.0. Our policy: study ideas and behavior; write all code independently.
- **Submodule:** exactly one — `app/NewPipeExtractor` → https://github.com/TeamNewPipe/NewPipeExtractor.git, recorded submodule commit `4701a1729ed587e5d1b30d5c1631746f7f835e98`. **Not initialized locally yet** (`-` prefix in `git submodule status`). Initialize only when Phase 3/4 requires reading its source:
  ```powershell
  git -C reference\SuvMusic submodule update --init app/NewPipeExtractor
  ```
- **Top-level layout observed:** `app`, `composeApp`, `core`, `extractor`, `media-source`, `lyric-kugou`, `lyric-lrclib`, `lyric-simpmusic`, `scrobbler`, `updater`, `docs`, `fastlane`, `gradle`, `.github`.

## Notable files discovered at root

These were not in the original blueprint overview and should feed Phase 1:

- `ARCHITECTURE_REVIEW.md` — reference's own architecture review
- `SYSTEM_DESIGN_FLAWS.md` — the author's own list of design flaws (high-value for us: known weaknesses to avoid)
- `DEVELOPER.md` — build/developer documentation
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` — module map inputs
- `CHANGELOG.md` + release notes (`release_1.0.4.md`, `SuvMusic-1.2.0-Release.md`, `1.0.3_suvmusic.md`)
- `com.suvojeet.suvmusic.yml` — likely F-Droid metadata
- `compose-stability.conf` — Compose compiler stability config

## Safety rules in effect

1. `reference/` is added to our `.gitignore` — the clone never enters our repository history.
2. No builds, no edits, no branch switches inside `reference/SuvMusic`.
3. Every claim in future notes must cite a file path valid at commit `d6636ca8`.

## Open questions carried forward

- [ ] Q0.1: What does `SYSTEM_DESIGN_FLAWS.md` admit as broken? (Read at start of Phase 1.)
- [ ] Q0.2: Is NewPipeExtractor vendored as a git submodule but built via `includeBuild`/composite build, or consumed differently? (Phase 1: read `settings.gradle.kts`.)
- [ ] Q0.3: Does `com.suvojeet.suvmusic.yml` reveal distribution constraints relevant to us? (Low priority.)

## Next phase

Phase 1 — Build and module map (blueprint §15). Fresh context window recommended.
