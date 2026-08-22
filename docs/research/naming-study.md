# Naming Study - Music-First Brand Identity

**Date:** August 23, 2026
**Author:** Brand research (naming workstream)
**Purpose:** Select a brand name for an independent Android music client that streams from YouTube / YouTube Music. The name must feel MUSIC-FIRST (sound, rhythm, melody, listening) — not a glass/material gimmick — while supporting a premium, buttery-smooth product identity.

**Design context:**
- Platform: Android, Kotlin + Jetpack Compose, Material 3 Expressive
- Feel: Apple/iOS-grade polish and fluidity; player transitions and artwork presentation inspired by SuvMusic
- Source: YouTube / YouTube Music catalog (streaming client, ViMusic/InnerTune/Metrolist class of apps)
- Audience skew: design-conscious listeners; heavy overlap with the Indian subcontinent / Global South user base typical of YouTube Music clients — an asset for Indian classical vocabulary

---

## Method

### Themes explored
Candidates were generated across four deliberate themes:

1. **Sound/music vocabulary (Western theory):** timbre, cadence, rubato, legato, cadenza, nocturne, fermata, melisma, aria, octave (ottava).
2. **Indian classical vocabulary:** meend (glide between notes), swara (note), laya (tempo/rhythm), raga, anahata ("unstruck sound").
3. **Poetic/emotional listening words:** dulcet (sweet-toned), soave (gentle/smooth), sotto voce (hushed voice), layali (Arabic nights / improvised night-music form).
4. **Invented/coined words with musical roots:** neiro (Japanese 音色, "tone color"), kanade (Japanese 奏でる, "to make music"), melos (Greek, "melody"), shirabe (Japanese, "tune"), octava and rezona (resonance coinages).

### Conflict-check approach
- Web-searched finalists with queries of the form `<name> android music app`, `<name> app`, `<name> music app`, `<name> android player`.
- Recorded collisions from Google Play listings, App Store listings, GitHub projects (especially Kotlin/Compose music clients), and music-industry companies/labels.
- Severity scale:
  - **Blocking** — an established product in the same or adjacent software category owns the name; confusion guaranteed.
  - **Crowded** — multiple users of the name, some in music/software; usable only with strong differentiation.
  - **Clear** — no direct collision surfaced in searches (does NOT guarantee trademark availability; formal legal screening still required).
- Blocked list (Retro Music, Musicolet, ViMusic, InnerTune, OuterTune, Harmonium, Harmony, Metrolist, SimpMusic, Namida, BlackHole, Spotube, RiMusic, Vanced, YT Music, glassware names) treated as taken per brief. One entry was incidentally re-verified organically during searches: Retro Music Player's official GitHub repo (~5.2k stars, Material You) surfaced in results.
- 14 names were search-checked across ~16 queries this session. Names marked **UNVERIFIED** below were evaluated on judgment only — do not treat them as cleared.

---

## Candidate table

| # | Name | Theme / Meaning | Syllables | Conflicts found + Severity | Verdict |
|---|------|-----------------|-----------|----------------------------|---------|
| 1 | **Meend** | Hindustani classical: the smooth glide connecting two notes — literally "seamless transition" in melody | 1 | Search: term appears only as a technique inside tuner apps (SwarMeter et al.); **no product named Meend found**. Severity: **clear** | ✅ Strong — near-perfect metaphor for buttery-smooth transitions |
| 2 | **Dulcet** | Latin *dulcis*: sweet, soothing to hear ("dulcet tones") | 2 | Search: dulcetapp.com is an off-category habit-breaking desktop tool; one unrelated merge-puzzle game. No music-app collision. Severity: **clear-ish** | ✅ Strong — premium, warm, instantly about sound |
| 3 | **Cantabile** | Italian direction: "in a singing style" — flowing, lyrical smoothness | 4 | Known collision: Cantabile (cantabilesoftware.com), established Windows VST host. Not re-searched this session. Severity: **crowded** (category-distant: pro-audio vs consumer client) | ⚠️ Usable — superb fit; musicians will know the DAW host |
| 4 | **Anahata** | Sanskrit: heart chakra; *anahata nada* = "unstruck sound" | 4 | Not search-verified; yoga/wellness crowding presumed. Severity: **UNVERIFIED** | ⚠️ Beautiful meaning; verify before committing |
| 5 | **Sotto** | From *sotto voce* — hushed, intimate voice | 2 | Not search-verified; restaurants named Sotto exist. Severity: **UNVERIFIED**, presumed low | ⚠️ Chic and intimate; musical meaning implicit |
| 6 | **Melisma** | Many notes sung on one syllable — vocal runs | 3 | Not search-verified; possible boutique agency usage. Severity: **UNVERIFIED**, presumed low | ⚠️ Distinctive insider term |
| 7 | **Croon** | To sing softly and warmly (crooning) | 1 | Not search-verified; Dutch engineering firm "Croon" exists. Severity: **UNVERIFIED** | ⚠️ Warm, short; retro/vocal-only connotation |
| 8 | **Shirabe** | Japanese: tune, melody | 3 | Not search-verified; anime character crowding presumed. Severity: **UNVERIFIED** | ❌ Weak differentiation outside Japan |
| 9 | **Octava** | Coined from *ottava* (octave) | 3 | Not search-verified; OCTAVA AV hardware brand recalled but unconfirmed. Severity: **UNVERIFIED** | ⚠️ Musical root, reads techy/hardware |
| 10 | **Rezona** | Coined from "resonate" | 3 | Not search-verified; coined words usually clear. Severity: **UNVERIFIED** | ⚠️ Sounds musical but slightly synthetic |
| 11 | Timbre | Tone color — core aesthetic quality of sound | 2 | Known: Timbre Group Singapore (live-music venues). Pronunciation trap: TAM-ber / TIM-ber / tam-BRAY. Severity: **crowded** (known, not re-verified) | ❌ Homophone of "timber"; spelling confusion |
| 12 | Cadence | Rhythmic flow; fall of phrase | 2 | Known: Cadence Design Systems (major EDA corp), fitness apps, Kadence WP themes. Severity: **crowded → blocking** | ❌ Corporate-owned word |
| 13 | Aria | Operatic solo melody | 3 | Known: Opera "Aria" AI, ARIA awards, hotels, common name; collides with `aria-label` accessibility jargon in our own domain. Severity: **crowded** | ❌ Overused everywhere |
| 14 | Nocturne | Chopin-style night piece; night listening | 2 | Search: Jeffser/Nocturne — active GNOME music client for Jellyfin/Subsonic/Navidrome (2026); Nocturne Music Player for Android (TechNarcs). Severity: **blocking** | ❌ Taken twice in our exact category |
| 15 | Fermata | Held/sustained note symbol | 3 | Search: Fermata Media Player — established open-source Android media/video/IPTV player with YouTube addon (Play Store + GitHub). Severity: **blocking** | ❌ Established Android media player |
| 16 | Neiro | Japanese 音色: tone color/timbre | 2 | Search: FabianZettl/Neiro — Android OpenSubsonic client with liquid-glass miniplayer + dynamic album-art theming (our exact design territory); plus two more Subsonic/DLNA players named Neiro; Neiro Audio plugins; NEIRO memecoin. Severity: **blocking** | ❌ Same-niche Android clients own it |
| 17 | Kanade | Japanese 奏でる: to make music | 3 | Search: matsumo0922/Kanade — Material 3 Jetpack Compose Android music player (our exact stack); "Kanade Music" on Google Play; Rust self-hosted music server "kanade". Severity: **blocking** | ❌ Owned directly in our stack/category |
| 18 | Melos | Greek: melody, song; root of "melodic" | 2 | Search: Melos – Music Player (iOS); Melos-Music-App GitHub org (incl. unofficial YT Music Dart API repo); mymelos.com; MELOS web3 studio; Dart "melos" monorepo tool. Severity: **crowded → blocking** | ❌ Too many music-product squatters |
| 19 | Laya | Sanskrit/Carnatic: tempo, rhythmic pulse | 2 | Search: Laya Music Player on Android (122k+ downloads via Uptodown/Aptoide). Severity: **blocking** | ❌ Existing Android music player |
| 20 | Swara | Sanskrit: a note of the scale | 2 | Search: Swara Music Player Pro (Android), Swara AI tutor (Android), SwarMeter, beSur ecosystem. Severity: **crowded** | ❌ Saturated in our exact audience segment |
| 21 | Soave | Italian: gentle, smooth; also Veneto wine region | 2 | Search: Soave Records (house label), Soave Studio (release-campaign platform), artist "Soave" (40k monthly listeners). Severity: **crowded** | ❌ Already a music-industry brand family |
| 22 | Layali | Arabic: nights; improvised night-music form | 3 | Search: LAYALI music-discovery app concept (2025 UX case study); "Layali Music" label/artist presence on Amazon Music. Severity: **crowded** | ❌ Active use in music-discovery context |
| 23 | Rubato | Italian: expressive "stolen time," tempo freedom | 3 | Search: Rubato: Piano & Instruments (iOS), Rubato Music Centre, GitHub playback app vebert/rubato, Rubato Music school (SG). Severity: **crowded** | ❌ Busy already in music-ed/playback tools |
| 24 | Cadenza | Soloist's improvised flourish at the cadence | 3 | Search: Cadenza (MetaMusic) on Google Play — AI accompaniment app; Cadenza Live Accompanist; playcadenza.app piano trainer. Severity: **crowded** | ❌ Multiple live music-app products |
| 25 | Legato | Notes bound smoothly — literal definition of smooth playing | 3 | Search: Legato: Music Practice Journal (Google Play, Android), LegatoHub classical app (Android), Legato score app (iOS), Legato School of Music. Severity: **crowded** | ❌ Four-plus music apps ship under it |

---

## Top 5 recommendation (ranked)

Ranking prioritizes **distinctiveness and space-cleanliness over raw beauty** (per brief: prefer distinctive over pretty-but-taken), then emotional fit with a premium smooth-listening experience, then length/pronounceability.

### 1. Meend — strongest pick
- **Why it wins:** One syllable and the single most on-brief meaning discovered: *meend* is the Hindustani technique of gliding seamlessly between notes — the exact sonic analogue of the buttery screen-to-screen transitions this app is built around. It honors the Indian classical tradition anchored in the app's likely core audience while sounding exotic-premium globally. Searches found zero products using the name.
- **Risks:** Obscure to non-Indian listeners — needs a one-line story ("Meend — the glide between notes"), which strong brands have anyway. Spelling mistypes likely (*meen/mind/meende*). Trademark status UNVERIFIED; run formal clearance before commit.
- **Tagline idea:** *"Every great song lives in the glide."*

### 2. Dulcet
- **Why it wins:** Means "sweet to the ear" — pre-loaded positive association with sound, zero education needed. Two syllables, clean consonants, near-universal pronounceability. Searches surfaced no music-product usage at all.
- **Risks:** An off-category habit-breaking tool uses the name (dulcetapp.com) — different market, minimal confusion, but it holds the obvious .com. Adjectival rather than noun-like; iconography must carry identity. Also UNVERIFIED for trademark.
- **Tagline idea:** *"Sweetness, on repeat."*

### 3. Cantabile
- **Why it wins:** The Italian performance direction for "singingly, flowingly" — arguably the precise technical term for the listening feel the app targets. Elegant, romantic, classical pedigree without stuffiness.
- **Risks:** Longest finalist (4 syllables). Cantabile (Topten Software) is an established VST/live-rig host; musician-audience overlap will recognize it. Category distance (desktop pro-audio tool vs Android streaming client) makes coexistence plausible but not friction-free.
- **Tagline idea:** *"Music, played singingly."*

### 4. Anahata
- **Why it wins:** *Anahata nada* — "the unstruck sound," resonance arising without anything being struck. Profoundly music-first, spiritual-premium, pairs beautifully with ambient artwork presentation.
- **Risks:** Conflicts UNVERIFIED — yoga studios and wellness apps almost certainly squat nearby. Four syllables; heart-chakra connotation may read New Age rather than tech-polished to some users.
- **Tagline idea:** *"The sound before sound."*

### 5. Sotto
- **Why it wins:** From *sotto voce* — the hushed, intimate voice. Two syllables, chic Italian, encodes quiet-confidence premium feel (intimacy over loudness).
- **Risks:** Conflicts UNVERIFIED (restaurants use it); musical meaning implicit rather than explicit; standalone "sotto" means "under" in Italian — marketing must pair it with voce or lean on context.
- **Tagline idea:** *"Turn it down. Hear everything."*

---

## Names explicitly rejected and why

| Name | Reason for rejection |
|------|----------------------|
| **Neiro** | BLOCKING. Three same-niche Android/Subsonic music clients — one with a liquid-glass miniplayer and dynamic album-art theming, i.e. our exact design pitch — plus an audio plugin company and memecoin contamination. |
| **Kanade** | BLOCKING. An existing Material 3 + Jetpack Compose Android music player, "Kanade Music" on Google Play, and a Rust self-hosted music server all use it. |
| **Fermata** | BLOCKING. Fermata Media Player is an established open-source Android media/IPTV player with YouTube addons — direct functional overlap. |
| **Nocturne** | BLOCKING. Active 2026 GNOME music client (Jellyfin/Subsonic/Navidrome) plus an older Android Nocturne Music Player. |
| **Melos** | Crowded/blocking. iOS Melos player, a GitHub org building a YouTube Music app under the name, web3 studio, plus the well-known Dart build tool. |
| **Laya** | Blocking. Laya Music Player ships on Android with 100k+ downloads. |
| **Swara** | Crowded. Swara Music Player Pro, Swara AI, SwarMeter — saturated exactly in the segment we would appeal to. |
| **Soave** | Crowded. Soave Records label + Soave Studio release platform + charting artist — already an active music-industry brand family. |
| **Layali** | Crowded. A music-discovery app concept launched 2025 and a "Layali Music" label/artist footprint on Amazon Music. |
| **Rubato** | Crowded. iOS instrument app, music school, GitHub playback app — name already busy in music tools. |
| **Cadenza** | Crowded. MetaMusic's Cadenza accompaniment app lives on Google Play today; piano-trainer products too. |
| **Legato** | Crowded. Four-plus shipping apps (Android practice journal, LegatoHub, iOS score app, music school). The prettiest casualty of the study. |
| **Timbre** | Crowded + usability trap. Timbre Group Singapore owns live-music mindshare in Asia; global pronunciation is unstable (TAM-ber/TIM-ber/tam-BRAY) and homophone of "timber". |
| **Cadence** | Cadence Design Systems dominates the word in tech; also generic fitness/writing apps. Corporate-owned vocabulary. |
| **Aria** | Overexposed: Opera's AI assistant, awards, hotels, given names — and collides with `aria-label` accessibility jargon inside our own engineering domain. |
| **Raga / Taal / Raag** | Treated as taken per brief logic: heavily genericized across dozens of existing Indian-music apps and media; no differentiation headroom. Not individually search-verified. |
| **Glassware-style names (Vitro, Prisma, Lumen)** | Excluded per brief: material/glass gimmick vocabulary contradicts the music-first mandate. |

---

## Notes and caveats

1. **Search ≠ clearance.** "Clear" here means no collision surfaced across ~16 web queries on Aug 23, 2026. It does not replace trademark screening (Nice class 9/42) or Play Store developer-name checks.
2. **UNVERIFIED entries** (Anahata, Sotto, Melisma, Croon, Shirabe, Octava, Rezona) carry judgment-based severity only. If any advances to finalist status, run the same search battery before deciding.
3. **The pattern worth noting:** every "obvious beautiful musical word" (legato, cadenza, rubato, nocturne, neiro, kanade, fermata) is already claimed by hobbyist/open-source music clients — this niche names itself from music theory. Distinctiveness therefore lives either in deep-cut vocabulary (Meend) or in off-beat poetic adjectives (Dulcet), not in the standard Italian canon.
4. **Recommendation:** proceed with **Meend**, hold **Dulcet** as runner-up, and commission formal trademark screening for both before any public use.

