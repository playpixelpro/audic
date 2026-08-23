# Enhancement Plan: Audic Brain Home Section

> Date: 2026-08-23
> **Goal**: Add a first section to the Home screen showing **Audic Brain-powered recommendations** using on-device interest profiling — without breaking the existing `AiRecommendations` (cloud AI) flow.
> Based on analysis of:
> - **Audic Brain** components at `app/.../brain/` — `BrainManager`, `BrainInterestProfileBuilder`, `BrainRecommendationEngine`
> - **HomeViewModel** — how sections are loaded and exposed
> - **HomeScreen** — how sections are rendered (sealed `HomeSection` + `when` dispatch)

---

## 1. Current State

### The Home Screen Sections (in `HomeScreen.kt`)

```
sealed class HomeSection(val id: String, val baseWeight: Int) {
    SpeedDial (100)
    AiRecommendations (95)     ← Uses cloud AI "Recommended by AI" playlist
    QuickPicks (90)
    DailyDiscover (80)
    KeepListening (50)
    AccountPlaylists (40)
    ForgottenFavorites (30)
    FromTheCommunity (20)
    SimilarRecommendation (10)
    HomePageSection (10)
    MoodAndGenres (5)
}
```

### How `AiRecommendations` currently works

| Step | What happens |
|------|-------------|
| 1 | `AiRecommendationHelper` calls **OpenRouter (Gemini)** with top songs as prompt |
| 2 | Gemini returns JSON array of 20 recommended song titles + artists |
| 3 | `AiRecommendationHelper` resolves each via YouTube search → saves to local playlist "Recommended by AI" |
| 4 | `HomeViewModel.aiRecommendedPlaylist` watches this playlist via Room Flow |
| 5 | `HomeScreen.AiRecommendations` renders it as a `LazyRow` of `localGridItem` |

**This requires:**
- An OpenRouter API key configured by the user
- Internet access
- An AI provider that may charge or rate-limit

### The Audic Brain components (already implemented, not yet on Home)

| Component | Status |
|-----------|--------|
| `BrainInterestProfileBuilder.build()` | ✅ Builds artist/album affinities from library |
| `BrainInterestProfileBuilder.calculateMatchingScore()` | ✅ Scores a track (0-100) against profile |
| `BrainRecommendationEngine.scoreAndRank()` | ✅ Full scoring pipeline |
| `BrainSessionTracker` | ✅ Tracks listening sessions |
| `BrainManager` | ✅ Orchestrator (not yet wired into PlayerConnection) |
| **Home integration** | ❌ **Not yet done** |

---

## 2. Proposed Change: `HomeSection.BrainSuggestions`

### What

Add a **new** section `BrainSuggestions` with `baseWeight = 110` (highest, appears first) that uses **Audic Brain's on-device interest profile** to surface the top-scoring songs from the user's local library.

### Data Flow (new)

```
loadLocalDataPhase()
  ↓
loadBrainSuggestions()
  ├─ database.topSongs(30)           ← most-played songs
  ├─ database.likedSongsByPlayTimeAsc()   ← liked songs
  └─ BrainInterestProfileBuilder.build(topSongs, likedSongs)
       ↓
     For each top song:
       calculateMatchingScore(song.toMediaMetadata(), profile)
       ↓
     Sort by score descending → take top 20
       ↓
     brainSuggestions.value = List<Song>
```

### Rendering

Same pattern as `AiRecommendations`:
- `NavigationTitle("Recommended for You")`
- `LazyRow` of `localGridItem` (reuses `SongGridItem`)

### Why it won't break anything

| Concern | Mitigation |
|---------|-----------|
| Existing `AiRecommendations` | Untouched — both sections coexist |
| Existing database tables | No changes to Room entities or migrations |
| Existing DAOs | No changes |
| Existing UI | New additive section only |
| Existing Playback/MusicService | No changes |
| Existing translations | Single hardcoded English string, no string resource needed |
| Offline/No API key | BrainSuggestions works entirely offline |
| Thread safety | `loadBrainSuggestions()` runs inside `loadLocalDataPhase()` which is already on `Dispatchers.IO` |

---

## 3. Files to Change

### `HomeViewModel.kt`

| Change | Detail |
|--------|--------|
| **Add imports** | `BrainInterestProfile`, `BrainInterestProfileBuilder`, `toMediaMetadata` |
| **Add StateFlow** | `val brainSuggestions = MutableStateFlow<List<Song>?>(null)` |
| **Add method** | `private suspend fun loadBrainSuggestions()` — builds profile, scores top songs |
| **Call it** | In `loadLocalDataPhase()` after `getQuickPicks()` |

### `HomeScreen.kt`

| Change | Detail |
|--------|--------|
| **Add sealed class entry** | `data object BrainSuggestions : HomeSection("brain_suggestions", 110)` |
| **Add state collection** | `val brainSuggestions by viewModel.brainSuggestions.collectAsState()` |
| **Add to homeSections list** | `if (brainSuggestions?.isNotEmpty() == true) list.add(HomeSection.BrainSuggestions)` |
| **Add to default order map** | `HomeSection.BrainSuggestions to 1200` (highest = first in non-randomized) |
| **Add rendering** | `when (section) { HomeSection.BrainSuggestions -> { ... } }` — `NavigationTitle` + `LazyRow` |

---

## 4. Non-Goals

- ❌ Do NOT touch `AiRecommendations` / "Recommended by AI" playlist
- ❌ Do NOT wire `BrainManager` into `PlayerConnection` (separate Phase 2 task)
- ❌ Do NOT add new database tables or migrations
- ❌ Do NOT add string resources in every locale
- ❌ Do NOT add a loading shimmer for this section specifically

---

## 5. Success Criteria

1. `HomeSection.BrainSuggestions` appears as the **first section** on the home screen
2. It shows up to **20 songs** scored by on-device Audic Brain interest profile
3. Each song is clickable and plays normally
4. Existing `AiRecommendations` section still renders below it when data is available
5. App compiles and runs without errors
6. Works **offline** with no API key required