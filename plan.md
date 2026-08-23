# Plan: Faster YouTube Streaming

## Problem

Current playback is slow when user taps a song. Main bottlenecks:

| Bottleneck | Current | Target | Speed gain |
|-----------|---------|--------|-----------|
| Primary client | `TVHTML5_SIMPLY_EMBEDDED_PLAYER` (heavy) | `WEB_REMIX` (lightweight) | ~500ms-1s |
| PoToken generator | Lazy init on first play | Pre-warm at app startup | ~2-5s |
| Cipher WebView | Lazy init on first play | Pre-warm at app startup | ~1-2s |
| N-transform | Applied to ALL clients | Only web clients need it | ~200-500ms |
| Fallback order | Flat array, same order | Content-aware ordering | Fewer failed attempts |

## Steps

### 1. Switch primary client to WEB_REMIX

**File:** `app/src/main/kotlin/com/audic/music/utils/YTPlayerUtils.kt`

- Change `MAIN_CLIENT` from `TVHTML5_SIMPLY_EMBEDDED_PLAYER` to `WEB_REMIX`
- Reorder `STREAM_FALLBACK_CLIENTS`: WEB_REMIX first, then VISIONOS, ANDROID_CREATOR, TVHTML5_SIMPLY, etc.

### 2. Add ContentHints data class

**New file:** `innertube/src/main/kotlin/com/music/innertube/models/ContentHints.kt`

```kotlin
data class ContentHints(
    val isExplicit: Boolean? = null,
    val isKidsContent: Boolean? = null, 
    val isLive: Boolean? = null,
    val isUploaded: Boolean? = null,
)
```

### 3. Add content-aware fallback in YTPlayerUtils

In `resolvePlaybackData()`, determine hints from `playlistId` / `videoDetails` and adjust client iteration order. Use content type to skip irrelevant clients.

### 4. Add prewarmPoToken() method to YTPlayerUtils

```kotlin
suspend fun prewarmPoToken() {
    val sessionId = YouTube.visitorData ?: return
    if (!MAIN_CLIENT.useWebPoTokens) return
    runCatching { poTokenGenerator.getWebClientPoToken(POTOKEN_WARMUP_VIDEO_ID, sessionId) }
}
```

### 5. Add pre-warm calls at app startup

**File:** `MainActivity.kt` or `App.kt`

Launch coroutine at startup to pre-warm both PoToken and Cipher WebView in parallel.

### 6. Guard n-transform for web clients only

In the n-transform section, add a check:
```kotlin
if (currentClient.useWebPoTokens) { /* apply n-transform */ }
```
Non-web clients (ANDROID_VR, IOS, etc.) don't need n-transform in most cases.

### 7. Keep audic's extra mitigations (unchanged)

- HTML scraping fallback
- Bot detection / session rotation
- EJS n-transform solver  
- `cver` fix
- `fexp` extraction

## Expected improvement

| Scenario | Before | After |
|----------|--------|-------|
| First play after cold start | ~3-6s | ~1-2s |
| Subsequent plays | ~1-2s | ~0.5-1s |
| Failed client recovery | ~2-3s per client | ~1-2s per client |
