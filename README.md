# Sway

**Your music, in flow.**

Sway is an independent Android music client built with Kotlin and Jetpack Compose
(Material 3 Expressive). It uses the YouTube Music catalogue as its discovery and
playback source while owning everything that makes it feel like *your* player:

- own data layer — likes, playlists, and history live on your device
- own playback experience — Media3 service with resilient stream resolution
- own visual language — artwork-driven atmosphere, buttery-smooth motion,
  benchmarked against iOS-grade polish

## Status

In active development. The planning chain is complete:

| Artifact | Path |
|---|---|
| Research blueprint & evidence notes | [`docs/research/`](docs/research/) |
| Product requirements (PRD) | [`_bmad-output/planning-artifacts/prd.md`](_bmad-output/planning-artifacts/prd.md) |
| UX design specification | [`_bmad-output/planning-artifacts/ux-design-specification.md`](_bmad-output/planning-artifacts/ux-design-specification.md) |
| Architecture | [`_bmad-output/planning-artifacts/architecture.md`](_bmad-output/planning-artifacts/architecture.md) |
| Epics & stories (15 epics · 62 stories) | [`_bmad-output/planning-artifacts/epics-and-stories.md`](_bmad-output/planning-artifacts/epics-and-stories.md) |
| Sprint tracking | [`_bmad-output/implementation-artifacts/sprint-status.md`](_bmad-output/implementation-artifacts/sprint-status.md) |
| Decision log | [`docs/decisions/`](docs/decisions/) |

## Engineering principles

1. **One of everything** — one DI framework, one database, one network stack. No half-finished migrations, ever.
2. **Typed errors everywhere** — every failure renders an honest state; blank screens are forbidden.
3. **Stream URLs are temporary** — validated at read time, renewed on failure, position always preserved.
4. **Study, don't copy** — architecture informed by studying open-source references (GPL-3.0); all code written independently.

## Build

Coming online with Epic 1 (workspace scaffolding): JDK 21, Android SDK 36, minSdk 26.

---

*Trademark note: "Sway" collision check against existing marks pending before public release.*
