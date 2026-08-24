---
title: 'Story 8.4 - Offline Fallback Cache store'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: 05b013d
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 8.4)'
  - '{project-root}/_bmad-output/planning-artifacts/prd.md (FR-4 substrate; UJ-5)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.md (C-8 lesson: strict validation on read, corrupt entry deleted, shipped-crash lesson)'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** When the network dies Sofia gets blank screens instead of her recent searches (FR-4 substrate missing / L8). Nothing caches catalog responses for failure-path service.

**Approach:** `FallbackCacheStore` in :core:data — JSON files under cacheDir keyed by request shape (base64-url filenames carrying the key inside the envelope), write-through API for repositories, read-on-failure API returning stale-marked payload or miss. Laws enforced ON ACCESS: 72 h TTL deletes expired entries (whole-directory sweep per access); strict envelope+payload validation deletes corrupt files with zero crashes; atomic temp-rename writes leave no torn files. Fresh-first is STRUCTURAL: the only read API is readOnFailure.

## Code Map

- `core/data/src/main/kotlin/com/sway/core/data/FallbackCacheStore.kt` -- NEW (write / readOnFailure / sweepExpired; TTL_MS=72h P-style constant; FORMAT_VERSION envelope).
- `core/data/src/test/kotlin/com/sway/core/data/FallbackCacheStoreTest.kt` -- NEW law suite (7 tests).
- Consumers arrive with E10 repositories (write-through on success; readOnFailure on Offline/UpstreamUnavailable).

## Design Notes

1. **Fresh-first structural:** no fresh-read method exists — AC4 proven by reflection over the API surface in-test.
2. **Envelope:** {v, key(base64-matched), fetchedAt, payload(escaped original JSON)}; payload re-parsed as strict JSON element so wrong-shape/wrong-type corruption validates out.
3. **Sweep-on-access** makes TTL self-healing without background jobs (NFR-10 discipline).
4. **Never throws:** cache is best-effort by definition; every path degrades to null/miss.

## Verification

:core:data **32 tests** green (25 prior-family + 7 cache laws). Repo total **443 tests, 0 failures**; all four audits exit 0; assembleDebug OK.
