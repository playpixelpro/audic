# Audic Brain v2 — Behavior-Driven Personalization Engine

## Overview

Audic Brain v2 is an **on-device, privacy-first** recommendation engine that learns from
your listening behavior to dynamically inject personalized song suggestions into your queue,
similar to the Flow app's approach (FlowAlgorithmV2), but adapted for music.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   UI Layer                              │
│  SettingsScreen → BrainSettings route                   │
│  QueueMenu → "Why this song?" dialog                   │
│  HomeScreen → "Audic Brain Recommends" section          │
├─────────────────────────────────────────────────────────┤
│                  BrainManager                           │
│  - Lifecycle: starts/stops with PlayerConnection        │
│  - Orchestrates: tracker → engine → injection           │
│  - Toggle: enabled/disabled via DataStore               │
├─────────────────────────────────────────────────────────┤
│            BrainRecommendationEngine                    │
│  1. Gather candidates (3 sources):                      │
│     a. YouTube.next(currentTrackId) — Anchor            │
│     b. YouTube.next(prevTrackId) — Momentum             │
│     c. Top 15 most-played from DB — Vault               │
│  2. Merge & deduplicate                                 │
│  3. Remove already-queued tracks                        │
│  4. Score each candidate (multi-signal):                │
│     - Source Trust (0-80 pts)                           │
│     - Interest Matching (0-100 pts)                     │
│     - Freshness (0-30 pts)                              │
│     - Diversity injection (channel/artist penalty)      │
│     - Recency boost (0-20 pts)                          │
│  5. Pick top 3 → inject into queue                      │
├─────────────────────────────────────────────────────────┤
│            BrainSessionTracker                          │
│  - Listens to PlayerConnection playback state           │
│  - Starts session on play                              │
│  - Accumulates totalDurationPlayed                     │
│  - Detects skips (< 15s → skip penalty)                │
│  - Persists to BrainListeningSession table              │
├─────────────────────────────────────────────────────────┤
│            BrainInterestProfile                         │
│  - Built from local DB: top songs, liked songs          │
│  - Tracks: artist affinities, album affinities          │
│  - Computes similarity scores                           │
│  - Stored in DataStore as JSON snapshot                │
├─────────────────────────────────────────────────────────┤
│            Data Layer (Room DB + DataStore)              │
│  Tables:                                                │
│    brain_listening_session — per-session tracking        │
│    brain_suggestion_log — per-suggestion tracking        │
│  DataStore keys:                                        │
│    audicBrainEnabled — toggle                           │
│    brainInterestProfile — cached profile JSON           │
└─────────────────────────────────────────────────────────┘
```

---

## Scoring Signals (Adapted from FlowAlgorithmV2)

| Signal | Range | What It Measures |
|--------|-------|------------------|
| **Source Trust** | 0-80 | Where the candidate came from (library: 60, related-to-current: 50, related-to-previous: 40, search-based: 30, discovery: 20) |
| **Interest Matching** | 0-100 | How well the candidate's artists/albums match your profile's affinities |
| **Freshness** | 0-30 | Newer additions score higher (exponential decay based on `inLibrary` date) |
| **Diversity** | Penalty | Artist repeat penalty (-25), same-source penalty (-10) |
| **Recency** | 0-20 | Candidates related to recently played (24h) get boosted |
| **Time-of-Day** | 0-10 | Morning boosts chill/ambient, evening boosts energetic/pop |

---

## Implementation Phases

### Phase 1: Foundation (This Implementation)
- Database entities + migration
- BrainConstants, model classes
- BrainInterestProfile builder
- BrainRecommendationEngine (scoring pipeline)
- BrainSessionTracker
- BrainManager (orchestrator)
- Settings UI + toggle
- "Why this song?" dialog
- QueueItemSource.AUDIC_BRAIN enum

### Phase 2: Integration
- Wire BrainManager into PlayerConnection lifecycle
- Real queue injection (add items after current index)
- Badging on queue items (AI badge)
- Auto-enable prompt on first launch

### Phase 3: Polish
- Home screen "Audic Brain Recommends" section
- Statistical dashboards
- A/B testing framework (if needed)
- Performance optimizations

---

## Data Flow

```
Player track transition
    ↓
SessionTracker records:
  - Track played? → accumulate playTime
  - Track skipped (<15s)? → penalty
    ↓
BrainManager.onTrackChanged(currentSong, previousSong)
    ↓
Engine.gatherCandidates():
  - YouTube.next(currentSong.id) → anchor songs
  - YouTube.next(previousSong.id) → momentum songs
  - database.topSongs(15) → vault songs
    ↓
Engine.scoreAndRank(candidates, profile, listeningHistory)
    ↓
Top 3 candidates tagged with QueueItemSource.AUDIC_BRAIN
    ↓
Inject into player queue after current index
    ↓
UI badges the injected items with AI icon
User can tap "Why this song?" to see scoring breakdown
```

---

## Non-Goals

- ❌ Remove or replace existing YouTube Music native recommendations
- ❌ External API calls for recommendations (100% on-device)
- ❌ Real-time collaborative filtering
- ❌ Server-side processing