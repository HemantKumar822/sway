# Owner Veto Brief — Provisional & Open Questions (E15 gate)

Generated: 2026-08-24 · For 15.3 release-readiness gate (PRD §7, AD-4)

> One page listing every headless decision and open question requiring owner veto/action before store submission. No telemetry, no distribution claims, personal-use framing per PRD §7.

## Provisional decisions (headless, veto via comment)

| ID | Decision | Where | Veto action |
|---|---|---|---|
| EP-1 | Epic granularity = builder-layer + thick vertical slices (E1-E2 enablers + E3-E8 vertical slices), not pure UX slices | epics-and-stories.md Epic List | Restructure note |
| EP-2 | FR completion points exactly-one (FR-8/22→E12, FR-25→E7, FR-14→E5, FR-15→E5, FR-30→E12) | Coverage maps | Reassign |
| EP-3 | Room schema incrementally (QueueState 7.3 → likes 8.1 → playlists 8.2 → history 8.3) with explicit migrations | 7.3, 8.1-8.3 | Front-load if preferred |
| EP-4 | Library overflow Settings/About wired in 15.2 (avoid throwaway stubs) | 11.4 note, 15.2 | Stub earlier if wanted |
| EP-5 | Error mapping: oversized → UpstreamUnavailable, malformed → Parse, blank-id dropped | 2.1, 3.2 | Align to taste |
| EP-6 | Licenses via curated `CURATED_LICENSES` from `libs.versions.toml` (Gradle plugin provisional) | 15.2 `AboutScreen.kt` | Pick tool |
| EP-7 | Points = Fibonacci → agent session sizing | Overview | Rescale |
| EP-8 | OQ-5 recents-swipe continuation per P-3 + OQ-6 quality flag `FeatureFlags.OQ6_QUALITY_VISIBLE=true` default ON | 6.3, 12.4, 15.1 | Flip flags |

## Upstream provisional (PRD/UX/Architecture)

- P-1..P-5, UX-P1..P12, AD-4 PROVISIONAL per their registers — see `docs/decisions/` and `planning-artifacts/*.md` memlogs.

## Open Questions (OQ) — owner action required

| OQ | Question | Current behavior (default) | Owner action at gate |
|---|---|---|---|
| OQ-1 | Home Feed InnerTube adapter | **Degraded Search-first landing** (brand + search + Library tiles per 9.5) — shelves deferred to v1.x | Approve degraded or schedule adapter |
| OQ-5 | Recents-swipe stops playback? | **Continues** (service not stopped when playing, notification remains per 6.3, P-3) | Confirm P-3 posture |
| OQ-6 | Audio-quality preference HQ | **Ships ON** behind `OQ6_QUALITY_VISIBLE=true` (chip 12.4 + settings entry) — veto hides both | Set `false` to veto HQ |
| OQ-7 | Store trademark / collision check | **NOT checked** — personal-use framing only; distribution would require name + policy review | **OWNER ACTION**: run trademark & Play Store collision check before any public distribution (PRD §7, AD-4) |

## Distribution posture (AD-4, NFR-9)

- No `distribution`/`store` claims in copy (grep `Play Store|distributed` = 0).
- No accounts, no telemetry, no crash SDKs (scan `firebase/analytics/crash` = 0).
- Egress hosts limited to catalog/stream/artwork (report in `release-evidence.md`).

## Next step

Sign off this brief (or veto inline) → dogfood `SM-3` per `dogfood-checklist.md` → Baseline Device macrobenchmark/soak append → release candidate.
