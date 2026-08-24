---
title: 'Story 9.1 - SwayTheme tokens on M3 Expressive [two-mode brand]'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 1
baseline_commit: 455b625
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics-and-stories.md (Story 9.1 AS AMENDED by owner 2026-08-24)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-design-specification.md §7.1 (owner decision: Ink & Paper mono default + artwork-dynamic mode)'
  - '{project-root}/_bmad-output/planning-artifacts/architecture.memlog.md (owner decision entry)'
  - 'SuvMusic UI/UX system analysis (github.com/suvojeet-sengupta/SuvMusic docs/UI_UX System): engine cascade dominant-colors -> system dynamic -> presets; spring-animated scheme transitions; M3E shapes/typography'
  - 'frontend-design skill (Anthropic): token-first planning; restraint; one signature element'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem (as amended by owner):** the violet-led brand is retired. Sway now ships TWO personalities in `:designui`: **"Ink & Paper"** — a Notion-philosophy monochrome default where paper neutrals carry the interface and color only means — and **artwork-DYNAMIC**, where the whole app recolors from the playing track's cover with spring-animated scheme transitions (SuvMusic-inspired cascade). Reduced-motion degrades every animated token to a <=120 ms fade.

**Approach:** pure theme-engine substrate in `:designui` (no screens yet):
1. `ThemeConfig(mode MONO|DYNAMIC, darkTheme, amoledBlack, reducedMotion)`.
2. `InkPaper` mono schemes: light paper-whites / dark Midnight-Ink / AMOLED collapse; ink primaries; semantic rose=like & amber=caution preserved in both modes.
3. `PaletteExtractor` — androidx.palette over any decoded Bitmap; vibrant-preferred seed; hermetically testable via synthetic bitmaps.
4. `DynamicSchemeFactory` — deterministic seed->full ColorScheme (light/dark) through a compact HSL tone ladder: hue inheritance on surfaces (chroma whisper-quiet), contrast floors enforced (readableOn flips at luminance .25).
5. `MotionScheme` — duration/easing/spring tokens; colorSpec spring vs <=120 ms linear fade under reduced motion.
6. `SwayShapes` ramp; `SwayTypography` Outfit(display)+Inter(body) BUNDLED variable fonts, tnum on numeric styles.
7. `SwayTheme(config, dynamicSeed)` composable: mode resolution (DYNAMIC+seed -> factory; else MONO), per-role animated scheme transitions.
8. CI audit `check_theme_imports.sh`: raw Color literals / R.font references forbidden outside :designui.

## I/O & Edge-Case Matrix

| Scenario | Expected | Handling |
|----------|----------|----------|
| AC1 dark/light toggle | correct scheme applies from the token set both modes | Bounded |
| AC2 reduced motion | all specs degrade to linear fade <=120 ms (unit-proven on MotionScheme) | Typed |
| AC3 seed bitmap -> DYNAMIC scheme | deterministic roles; onPrimary-vs-primary >=3:1 BOTH modes; hue inheritance provable in dark surfaces; light surfaces stay paper-bright with whisper chroma | Measured |
| No seed / extraction null | MONO applies structurally (SwayTheme resolution law) | Defensive |
| Import lint | raw colors/fonts only inside :designui (CI audit) | Bounded |

## Design Notes

1. **Two personalities, one anchor:** MONO is the design anchor (Notion restraint); DYNAMIC spends its boldness exactly once — the whole-app recolor gesture. Everything else stays quiet per the frontend-design discipline.
2. **Compact tonal math over a full Material You engine:** covers are high-chroma art; hue carries identity while lightness/chroma are disciplined by the factory. Same-seed determinism keeps future screenshot tests stable.
3. **Near-white quantization reality:** light surfaces at L>=0.95 lose measurable hue after 8-bit quantization, so the light-mode law is paper-bright + chroma<0.15; dark surfaces prove hue inheritance within 8 degrees.
4. **Bitmap loading arrives with Coil (13.1);** the extractor consumes decoded Bitmaps so the engine is complete and testable now — DYNAMIC falls back to MONO until artwork bitmaps flow (documented hand-off).
5. **ThemeMode persistence lands 15.1** (Settings); :designui cannot reach :core:data per AR-1 edges, so the composable takes config parameters.

## Verification

- `:designui:testDebugUnitTest` — **16 tests, 0 failures**: PaletteExtractorTest(4: red-band dominance, vibrant preference, determinism, neutral-canvas contrast floor), DynamicSchemeFactoryTest(5: determinism, 3:1 floors both modes, dark hue inheritance + light chroma-whisper, luminance split, mono role-identity), InkPaperTest(4), MotionSchemeTest(3).
- Repo total **443 tests, 0 failures** across seven modules (--rerun-tasks full sweep).
- **Five audits exit 0**: placeholder scheme, module edges, serializer ownership, history write-path, **theme imports(new)**.
- `:app:assembleDebug` BUILD SUCCESSFUL.
