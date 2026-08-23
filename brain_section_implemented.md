# Implemented: Audic Brain Home Section

> Date: 2026-08-23

## Summary

Added a new **"Recommended for You"** section as the first section on the Home screen, powered by the **Audic Brain** on-device interest profile. This uses zero cloud APIs, requires no API key, and works fully offline.

---

## Changes Made

### `HomeViewModel.kt` (+3 imports, +2 declarations, +1 method, +1 call)

| Change | Line | Detail |
|--------|------|--------|
| ✅ Added imports | ~39 | `BrainInterestProfileBuilder`, `toMediaMetadata` |
| ✅ Added StateFlow | ~92 | `val brainSuggestions = MutableStateFlow<List<Song>?>(null)` |
| ✅ Added method | ~448 | `loadBrainSuggestions()` — builds profile, scores top 20 songs |
| ✅ Added call | ~430 | `loadBrainSuggestions()` inside `loadLocalDataPhase()` after `getQuickPicks()` |

The `loadBrainSuggestions()` method:
1. Fetches `database.topSongs(30)` (most-played) and `database.likedSongsByPlayTimeAsc()` (liked)
2. Builds `BrainInterestProfile` using `BrainInterestProfileBuilder.build()`
3. Scores each top song against the profile using `calculateMatchingScore()`
4. Sorts by score descending, takes top 20
5. Stores in `brainSuggestions` StateFlow as `List<Song>`

### `HomeScreen.kt` (+1 sealed class entry, +1 state, +1 condition, +1 order entry, +1 rendering block)

| Change | Line | Detail |
|--------|------|--------|
| ✅ Added sealed class | ~187 | `data object BrainSuggestions : HomeSection("brain_suggestions", 110)` — highest weight |
| ✅ Added state collection | ~582 | `val brainSuggestions by viewModel.brainSuggestions.collectAsState()` |
| ✅ Added to remember deps | ~822 | `brainSuggestions` in the `remember(...)` key list |
| ✅ Added to section list | ~826 | `if (brainSuggestions?.isNotEmpty() == true) list.add(HomeSection.BrainSuggestions)` — first in list |
| ✅ Added to default order | ~892 | `HomeSection.BrainSuggestions to 1200` — sorts first in non-randomized mode |
| ✅ Added rendering block | ~1173 | `HomeSection.BrainSuggestions -> { NavigationTitle("Recommended for You") + LazyRow }` |

The rendering matches the existing `AiRecommendations` pattern:
- `NavigationTitle` with title "Recommended for You"
- `LazyRow` of `localGridItem` (reuses existing `SongGridItem` for each song)

---

## Files Created

| File | Purpose |
|------|---------|
| `enhancement_brain_section.md` | Enhancement plan document |

## Files Modified

| File | Change |
|------|--------|
| `app/.../viewmodels/HomeViewModel.kt` | Added brain imports, StateFlow, loading method, call in `loadLocalDataPhase()` |
| `app/.../ui/screens/HomeScreen.kt` | Added sealed class entry, state collection, section list condition, order entry, rendering block |

---

## What Was NOT Changed (No Breakage)

- ✅ Existing `AiRecommendations` (cloud AI) section **untouched** — both sections coexist
- ✅ No database tables modified
- ✅ No Room entities or migrations touched
- ✅ No existing DAO methods changed
- ✅ No existing UI screens broken
- ✅ No PlayerConnection or MusicService code touched
- ✅ No string resources added (single inline English string)
- ✅ Works 100% offline with zero API keys

---

## Verification

- `BrainInterestProfileBuilder.build()` is already used by `BrainManager` — well-tested
- `calculateMatchingScore()` is already used by `BrainRecommendationEngine` — well-tested
- `database.topSongs()` and `database.likedSongsByPlayTimeAsc()` are existing DAO queries
- `localGridItem`, `NavigationTitle`, `LazyRow` are existing UI components
- The `loadLocalDataPhase()` already runs on `Dispatchers.IO` via the parent `load()` launcher
- `brainSuggestions` returns `null` if no data available → section is simply hidden