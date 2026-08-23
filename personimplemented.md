# Audic Brain v2 — Implemented Components

## Overview

This implementation adds a behavior-driven, on-device recommendation system inspired by
the Flow app's approach (FlowAlgorithmV2), adapted for music. It runs entirely on-device
with no external API calls for recommendations.

---

## New Files Created

### Core Engine (app/src/main/kotlin/com/audic/music/brain/)

| File | Description |
|------|-------------|
| **BrainConstants.kt** | Scoring weights and configuration constants |
| **ScoredTrack.kt** | Scored track data model + TrackSource enum |
| **BrainInterestProfile.kt** | User profile builder + matching algorithm |
| **BrainRecommendationEngine.kt** | Multi-signal scoring pipeline |
| **BrainSessionTracker.kt** | Listening session tracking (start/end/skip detection) |
| **BrainManager.kt** | Main orchestrator tying all components together |
| **BrainDao.kt** | Room DAO for brain tracking tables |

### UI (app/src/main/kotlin/com/audic/music/brain/ui/)

| File | Description |
|------|-------------|
| **BrainSettingsScreen.kt** | Settings screen for Audic Brain toggle |
| **BrainWhyDialog.kt** | "Why this song?" transparency dialog |

### Database Entities (app/src/main/kotlin/com/audic/music/db/entities/)

| File | Description |
|------|-------------|
| **BrainListeningSession.kt** | Room entity for listening sessions |
| **BrainSuggestionLog.kt** | Room entity for suggestion logging |

### Plan Document

| File | Description |
|------|-------------|
| **personalization.md** | Full architecture and implementation plan |

---

## Modified Files

| File | Change |
|------|--------|
| **MediaMetadata.kt** | Added `QueueItemSource.AUDIC_BRAIN` enum value |
| **PreferenceKeys.kt** | Added `AudicBrainEnabledKey`, `AudicBrainShowWhyKey` |
| **MusicDatabase.kt** | Added brain entities + DAO, bumped version to 44, added MIGRATION_43_44 |
| **NavigationBuilder.kt** | Added `settings/brain` composable route |
| **SettingsScreen.kt** | Added "Audic Brain" entry in settings menu |
| **SearchableSettings.kt** | Added brain-related search entries |
| **strings.xml** | Added brain-related string resources |

---

## Scoring Signals Implemented

| Signal | Range | Status |
|--------|-------|--------|
| Source Trust (Vault/Anchor/Momentum) | 0-80 | ✅ Implemented |
| Interest Matching (artist affinity) | 0-100 | ✅ Implemented |
| Freshness | 0-30 | ✅ Basic implementation |
| Diversity (artist repeat penalty) | Penalty | ✅ Implemented |
| Recency boost (24h) | 0-20 | ✅ Implemented |
| Light shuffle for variety | - | ✅ Implemented |

---

## Data Flow

```
Track starts playing
    ↓
BrainSessionTracker.startSession()
    ↓ (periodically)
BrainManager.onPlayPositionUpdate()
    ↓
Track ends/skipped
    ↓
BrainSessionTracker.finalizeSession()
## Fixes Applied During Build

The following compilation errors were fixed in the initial implementation:

| Error | Cause | Fix |
|-------|-------|-----|
| `BrainInterestProfile.kt:116` — `name` unresolved | `AlbumEntity` uses `title` not `name` | Changed `album.name` → `album.title` |
| `BrainRecommendationEngine.kt:44` — `toMediaMetadata` unresolved | Missing import | Added `import com.audic.music.models.toMediaMetadata` |
| `BrainSettingsScreen.kt:41` — `setValue` not available for delegate | Missing `setValue` import | Added `import androidx.compose.runtime.setValue` |
| `NavigationBuilder.kt:394-398` — Syntax errors | Insert at wrong line caused broken nesting | Removed extra `}`, fixed composable chain, removed duplicate `settings/player` route |

The `rememberPreference` function returns `MutableState<T>` and must be used with `var x by rememberPreference(...)` syntax (property delegation), importing both `getValue` and `setValue` from `androidx.compose.runtime`.
  → saves to brain_listening_session table
    ↓
BrainManager.generateRecommendations()
  → BrainInterestProfileBuilder.build()
  → BrainRecommendationEngine.scoreAndRank()
  → returns top 3 ScoredTrack
    ↓
BrainManager.logSuggestion()
  → saves to brain_suggestion_log table
    ↓
"Prepared for queue injection" (Phase 2)
```

---

## Integration Points (for Phase 2)

The following hooks are ready for integration with PlayerConnection:

1. **BrainManager.onTrackStarted(track)** → call in `onMediaItemTransition`
2. **BrainManager.onPlayPositionUpdate(positionMs)** → call from periodic timer
3. **BrainManager.onTrackEnded(track, wasSkipped)** → call on next track transition
4. **BrainManager.generateRecommendations(current, previous, queuedIds)** → returns scored tracks
5. **Injection logic**: Add scored tracks as MediaItems with `QueueItemSource.AUDIC_BRAIN` after current index

---

## What Was NOT Changed (No Breakage)

- ✅ No existing database tables were modified
- ✅ No existing DAO methods were changed
- ✅ No existing UI screens were broken (only new entries added)
- ✅ No existing PlayerConnection code was touched
- ✅ No existing MusicService code was touched
- ✅ No existing serialization formats changed (enum added with new value)
- ✅ All new code is in new files or minimal additions to existing files

---

## Next Steps (Phase 2)

1. Wire BrainManager into PlayerConnection lifecycle
2. Implement actual queue injection (add MediaItems after current index)
3. Add badge icon to injected items in Queue UI
4. Wire "Why this song?" dialog to BrainManager.getRecommendationReasons()
5. Add periodic background refresh
6. Build HomeScreen "Audic Brain Recommends" section