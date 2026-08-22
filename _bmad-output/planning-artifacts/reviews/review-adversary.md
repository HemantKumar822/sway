# Review — Adversarial divergence hunt (finalize_reviewers lens 2)

Target: ARCHITECTURE-SPINE content as committed in planning-artifacts/architecture.md.
Method: for each pair of independently-built units one level down, attempt to satisfy every
AD to the letter yet still produce incompatible artifacts (shared-data shapes, dual owners,
conflicting state-mutation paths).

## Verdict

PASS after fixes. Four genuine divergence holes found and closed by tightening existing ADs
(no new ADs needed; IDs stay stable). One residual accepted risk recorded.

## Divergences constructed

### D-1 Queue snapshot: two serializers, two shapes (CRITICAL — fixed)

Two builders obeying AD-6/AD-8 still diverge: `:playback` persists via SessionRestoreRepository
using its own JSON snapshot shape; a second builder extends `QueueStateEntity` handling inside
`:core:data` with a different shape (e.g., drops artwork variants or quality discriminator).
Restore renders a queue the service cannot resolve faithfully — silent data loss across
process death (breaks FR-25/NFR-4 while violating no AD as written).
**Fix applied to AD-8:** canonical `QueueSnapshot` (core:model) is the only queue
representation; exactly one serializer owned by `:core:data`; no other module may
(de)serialize queue state.

### D-2 Placeholder sentinel: two formats (HIGH — fixed)

AD-6 names `sway://pending/<sourceId>` but nothing forbids another unit from minting or
sniffing a different sentinel (the reference had two: watch-URL sniffing + placeholder.invalid,
with dedicated race guards because of it). Transition handler and error paths could disagree
on what counts as "unresolved".
**Fix applied to AD-6:** placeholder scheme defined in exactly one place in `:playback`;
no other module constructs, mutates, or string-sniffs placeholders.

### D-3 History recording: two write paths (HIGH — fixed)

FR-34 records "after 10 s played". Service-side analytics listener and a UI-layer observer
both plausibly "own" that trigger → double entries or recency fights on the same play event.
**Fix applied to AD-6:** history recording is exclusively service-side, single write path
through HistoryRepository.

### D-4 Artwork fallback logic: parse-time vs load-time duplication (MEDIUM — fixed)

AD-11 computes candidate chains at parse time (`:catalog`), but Coil loading lives in
`:designui`. A designui builder can re-implement host-specific upgrade/downgrade rules
(the reference's exact three-site divergence) instead of consuming candidates as data.
**Fix applied to AD-11:** ArtworkRef carries the ordered candidate list; designui consumes
candidates as data and contains zero host-specific URL logic.

### D-5 Quality enum placement (LOW — fixed by clarification)

AudioRequest/ResolvedAudio/quality enum used by settings (:core:data), resolver (:catalog),
and player (:playback). AD-1's "ports speak only core:model types" implies placement, but an
implied invariant invites a local re-declaration.
**Fix applied to AD-7:** explicit sentence pinning AudioRequest/ResolvedAudio/quality enum
to core:model.

## Residual accepted risk

R-2 (ciphered-format prevalence on the NewPipe primary path) cannot be retired from a desk —
it needs the device experiment already logged under Open Questions Remaining. The escalation
path (early InnerTube adapter) is documented in AD-1, so the risk is owned, not open.

## Checklist walk (rubric)

- Real divergence points fixed at this altitude: yes (after D-1..D-5 closures).
- Every AD Rule enforceable: yes — each carries a mechanical or test-verifiable clause.
- Deferred items cannot let units diverge: confirmed (all are feature-scoped, not boundary-scoped).
- Named tech verified-current: see review-version-reality.md (pass).
- Spec coverage: all 10 PRD feature groups mapped in Capability table; all C-1..C-8 and
  NFR-1..NFR-10 traced; OQ-1..4 closed with rationale, OQ-5..7 correctly left product-owned.
- No inherited parent spine (initiative altitude) — n/a.
- Operational envelope: single-user single-device Android app; deployment = local builds +
  release-gate posture (AD-4); operations = none shipped (P-4); stated in Deferred/AD-4 —
  adequate for this product's reality.
