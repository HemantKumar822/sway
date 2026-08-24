# Dogfood Checklist — SM-3 (Story 15.3)

Generated: 2026-08-24 · For v1 daily-driver handoff (PRD §7, SM-3)

> Install `app:assembleDebug` on daily driver, exercise friction log below for ≥3 days. Report issues to `_bmad-output/` or GitHub with `sm3-friction` label. Gate passes when no release-blocker remains.

## Install

- [ ] `.\gradlew.bat :app:assembleDebug` on `jbr` → `app/build/outputs/apk/debug/app-debug.apk` → install on device
- [ ] First launch grants `POST_NOTIFICATIONS` via rationale → RequestPermission (grant or deny both valid; media controls remain platform-exempt)
- [ ] Verify `Settings → Appearance` System/Light/Dark toggles instantly (no restart), survives kill-relaunch
- [ ] Verify `About` lists Sway `0.1.0 (1)` + every shipped dep 14 licenses expandable, no distribution claims

## Journeys (UJ)

- [ ] **UJ-1 Maya cafe**: Home → Search "neon" → grouped results → Album tap → Play track #5 (queue at #5) → Mini appears → Full → scrub ±1s → Like → Queue jump → Back to Library (tab state preserved)
- [ ] **UJ-2 Dev commute**: Play → background + screen-off 10m → notification controls → lock screen parity → call (transient pause → resume) → unplug headphones (<1s pause, no auto-resume)
- [ ] **UJ-3 Priya playlist**: Library → + New "Gym" twice (duplicates allowed) → Add songs (Liked→batch) → Edit: reorder/remove/add → kill → reopen → edits intact → Play queue
- [ ] **UJ-4 Alex overnight**: Play 2 min → history (>10s rule) → History tap replay → Kill `adb shell am kill` → relaunch → session paused at ±5s → tap Play resumes at saved moment
- [ ] **UJ-5 Sofia subway**: Airplane on → cold start Library + banner → Search stale `Saved` tappable → play stale → offline message → airplane off → banner clears + search succeeds without restart → artwork cached re-view zero network

## Friction log template

| Date | Journey | Observation | Severity (block/major/minor) | Repo |
|---|---|---|---|---|
|  |  |  |  |  |

## Sign-off

- Device: __________  OS: ____  Baseline profile: ____
- Gaps found: __  Blockers: __  Date: ____  Owner: Hemant
