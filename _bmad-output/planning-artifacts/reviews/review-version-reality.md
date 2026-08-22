# Review — Version reality check (finalize_reviewers lens 1)

Target: ARCHITECTURE-SPINE content as committed in planning-artifacts/architecture.md (2026-08-23).
Method: every named technology re-checked against live sources on 2026-08-23 during the run;
this review records what was confirmed and what was not.

## Verdict

PASS with 3 minor items — all resolved by annotations already present or fixed post-review.

## Verified against the web this run (confirmed-current)

| Item | Pinned | Source evidence |
| --- | --- | --- |
| Kotlin | 2.4.10 | kotlinlang.org releases (Jul 14 2026 bug-fix line; 2.4.20 EAP not consumed) |
| AGP / Gradle | 9.3.0 / 9.5.0 | developer.android.com AGP table + 9.3.0 release notes (Jul 14 2026; supports API ≤37) |
| material3 | 1.4.0 stable | compose-material3 release page (Jul 15 2026); Expressive APIs promoted to stable in this version — spine's availability note matches |
| Compose ui/foundation | 1.11.x | androidx stable channel (Jul 01 2026: 1.11.4) |
| navigation-compose | 2.9.x | stable channel (2.9.8, Apr 22 2026) |
| Media3 | 1.11.0 | androidx media releases (stable Aug 05 2026); StuckPlayerException detection + builder-configurable timeouts confirmed in release notes (landed across late 1.10.x→1.11; spine correctly hedges "verify exact surface at build time") |
| Hilt / androidx.hilt | 2.60.1 / 1.4.0 | Dagger GitHub releases (Jul 07 2026) + androidx Hilt (Jul 01 2026) |
| Room | 2.8.4 | androidx Room page (latest 2.x stable Nov 19 2025); room3 3.0.0 new-package major confirmed and consciously deferred in AD-8 |
| Coil | 3.5.0 | coil changelog (Jun 10 2026); coil-network-okhttp artifact confirmed |
| OkHttp | 5.5.0 | square changelog (Aug 16 2026) |
| NewPipeExtractor | v0.26.5 JitPack | TeamNewPipe GitHub tags (Aug 15 2026) — newer than reference calibration v0.26.4 |
| DataStore | 1.2.x | stable channel (1.2.1, Apr 2026) |

## Findings

1. **MEDIUM — kotlinx.serialization "1.9.x" was not independently web-verified** this run
   (coroutines 1.11.x was corroborated via OkHttp's upgrade notes). Fix applied: stack-table
   footnote already permits minor-line bumps at build time; epics must confirm the exact
   patch when the version catalog is authored. No structural risk.
2. **LOW — test-library row pins "current stable lines" generically.** Acceptable at spine
   altitude (test frameworks are seed, not invariant); the build epic pins them.
3. **LOW — compileSdk 36 chosen while AGP 9.3 supports 37.** Deliberate: Android 17 (API 37)
   is still beta-channel; "target latest stable" resolves to 36. Correct as written.

## Conclusion

No decision in the spine rests on unverified or stale technology claims. The one soft pin
(serialization) is flagged for build-time confirmation without touching any AD.
