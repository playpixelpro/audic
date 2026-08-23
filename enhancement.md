# Enhancement Plan: Playback Fix & AI Architecture

> Date: 2026-08-21
> **Priority 1**: Fix YouTube playback blocking (CRITICAL — app is non-functional without this)
> **Priority 2**: AI service architecture consolidation (improves maintainability)
> Based on analysis of:
> - **Audic** (current app) — playback at `YTPlayerUtils.kt`, `MusicService.kt`, `BotDetectionMitigator.kt`
> - **SimpMusic** (reference app) — `core/service/kotlinYtmusicScraper/`, `core/data/src/mediaservice/`

---

# Part 1: YouTube Playback Blocking (CRITICAL PRIORITY)

## 1. Current State: Playback Failure Analysis

### The Error
```
Playback failed. No playable stream was returned for this song.
YouTube may be blocking this session (code_io_unspecified) 2000
```

### Root Cause

YouTube is aggressively blocking "unauthorized" client requests. The failure chain is:

```
MusicService.onPlayerError()
  → isCacheOrStreamCorruptionError()  # ERROR_CODE_IO_UNSPECIFIED (2000)
  → handleExpiredUrlError()
    → markStreamClientRejected()
    → BotDetectionMitigator.rotateGuestSession()
    → player.seekTo() + player.prepare()   # RETRY with same client!
```

The critical issue: **the retry uses the same blocked client** (`ANDROID_VR`) after rotating the guest session. Rotating the session changes `visitorData`, but `ANDROID_VR` is fundamentally blocked from returning playable URLs regardless of session because YouTube has moved it (and all mobile clients) to SABR-only responses.

### SABR (Server-Adjusted Bandwidth Ratio) Streaming

Since mid-2026, YouTube has been systematically moving clients to SABR where `streamingData.adaptiveFormats[i].url` is **null** and there is **no `signatureCipher`** either. The only way to get playable URLs is through clients that still carry direct URLs:

| Client | Currently Returns URLs? | Notes |
|---|---|---|
| `ANDROID_VR` (Main) | ❌ No — SABR only | Used as MAIN_CLIENT |
| `ANDROID_VR_NO_AUTH` | ❌ No | SABR enforced |
| `IOS` | ❌ No | SABR enforced |
| `IPADOS` | ❌ No | SABR enforced |
| `ANDROID_CREATOR` | ❌ No | SABR enforced |
| `MOBILE` | ❌ No | SABR enforced |
| `WEB` | ⚠️ Sometimes | Needs PoToken, returns signatureCipher |
| `WEB_REMIX` | ⚠️ Sometimes | YouTube Music web — best for music |
| `TVHTML5_SIMPLY_EMBEDDED_PLAYER` | ✅ Most reliable | Embedded player bypasses age-restriction |
| `VISIONOS` | ✅ Good fallback | Apple VisionOS client — newest addition |

### The Client Fallback Chain

In `YTPlayerUtils.resolvePlaybackData()` (line ~370), the fallback tries clients in this order:

```kotlin
val METADATA_CLIENT = YouTubeClient.Companion.WEB_REMIX
// MAIN_CLIENT = ANDROID_VR
val STREAM_FALLBACK_CLIENTS = listOf(
    ANDROID_VR_NO_AUTH, ANDROID_VR_1_43_32, ANDROID_CREATOR,
    TVHTML5_SIMPLY_EMBEDDED_PLAYER, TVHTML5, IOS, IPADOS, 
    VISIONOS, MOBILE, WEB, WEB_CREATOR, WEB_REMIX
)
```

**Problem**: `ANDROID_VR` is the PRIMARY client but it almost always fails now. It should be moved **last** in the chain or removed entirely. The Web clients (`TVHTML5_SIMPLY_EMBEDDED_PLAYER`, `WEB_REMIX`) should be tried first.

### PoToken Generation Issues

In `PoTokenGenerator.kt`, PoTokens are generated via a WebView that loads YouTube's bot detection challenge. This can fail silently when:
- WebView creation times out
- The challenge page changes
- The sessionId is stale or invalid
- The resulting PoToken is rejected by YouTube's backend

When PoToken fails, `ANDROID_VR` returns `UNPLAYABLE` status or SABR-only responses.

### n-Parameter / Throttling

Even when a URL is resolved, the CDN may return **403 Forbidden** because:
1. The `n` parameter (throttling) was not transformed
2. The `cver` (client version) doesn't match the expected value
3. The request lacks proper `User-Agent` / `Origin` / `Referer` headers

The app has 3 layers of n-parameter deobfuscation (`CipherDeobfuscator`, `EjsNTransformSolver`, `NewPipeExtractor.deobfuscateThrottlingParam`) but they can all fail if the player.js hash is stale or the WebView crashes.
---

## 2. Reference State: How SimpMusic Avoids Blocking

### Key Differences

| Aspect | Audic | SimpMusic (Reference) | Winner |
|---|---|---|---|
| **Primary Client** | `ANDROID_VR` (mobile VR) | `WEB_REMIX` (YouTube Music web) | Reference |
| **Client Fallback** | Mobile-first chain | Single client + NewPipe | Audic (more thorough) |
| **Stream URL Extraction** | Multi-client fallback | `StreamInfo.getInfo()` via NewPipe | Reference (simpler) |
| **PoToken** | WebView-based generation | ❌ None | Audic (potential) |
| **n-Param Deobfuscation** | 3 layers (Cipher+EJS+NewPipe) | NewPipe's `YoutubeJavaScriptPlayerManager` | Similar |
| **Guest Session Rotation** | ✅ Yes — `BotDetectionMitigator` | ❌ None | Audic |
| **Proxy Support** | ✅ Yes — configurable | ❌ None | Audic |
| **Server-Abr-Streaming-Url** | Not used | Used for `fexp` extraction | Reference |
| **HTML Page Scraping** | Not used | Scrapes `ytInitialPlayerResponse` | Reference |

### What Reference Does Better

1. **Uses `WEB_REMIX` as primary client** — YouTube Music's own web client is less aggressively throttled than mobile/VR clients.

2. **Scrapes `ytInitialPlayerResponse` from HTML page** — When the API returns SABR-only, the reference app falls back to parsing the `<script>` tag from the YouTube Music page HTML, which sometimes contains direct stream URLs.

3. **Extracts `fexp` from `serverAbrStreamingUrl`** — The PlayerResponse's `streamingData.serverAbrStreamingUrl` contains `fexp` parameters that must be appended to playback tracking URLs. The reference does this correctly; Audic doesn't.

4. **No PoToken dependency** — The reference app doesn't use PoToken at all, avoiding failure modes from PoToken generation issues.

### What Audic Does Better

1. **Multi-client fallback** — When one client fails, Audic tries 12+ others. The order just needs fixing.
2. **Guest session rotation** — `BotDetectionMitigator` rotates `visitorData`, bypassing rate limits.
3. **PoToken support** — When working, unlocks age-restricted content.
4. **IP version control** — IPv6-only mode can bypass some geo-restrictions.
5. **Proxy support** — Full proxy configuration for bypassing regional blocks.
---

## 3. Solution Strategy for Playback Fix

### Short-Term Fixes (Implement Immediately)

#### Fix 1: Reorder Client Priority (Highest Impact)

**`YTPlayerUtils.kt`** — Change `MAIN_CLIENT` from `ANDROID_VR` to `TVHTML5_SIMPLY_EMBEDDED_PLAYER` and reorder the fallback chain to try web clients first:

```kotlin
// Current (broken):
val MAIN_CLIENT = ANDROID_VR

// New (fixed):
val MAIN_CLIENT = TVHTML5_SIMPLY_EMBEDDED_PLAYER

// Reorder fallbacks — web clients FIRST:
val STREAM_FALLBACK_CLIENTS = listOf(
    WEB_REMIX,
    TVHTML5_SIMPLY_EMBEDDED_PLAYER,
    VISIONOS,
    ANDROID_CREATOR,
    ANDROID_VR_NO_AUTH,
    ANDROID_VR_1_43_32,
    ANDROID_VR,
    IOS,
    IPADOS,
    MOBILE,
    WEB,
    WEB_CREATOR,
    TVHTML5,
)
```

**Rationale**: TV/embedded clients are far less likely to be SABR-forced because they need simple URLs for smart TVs. `VISIONOS` is the newest addition and YouTube hasn't blocked it yet.

#### Fix 2: Retry with Different Client, Not Same Client

**`MusicService.kt`** — In `handleExpiredUrlError()` and `handleGenericIOError()`, the retry calls `player.seekTo() + player.prepare()` which triggers `ResolvingDataSource.resolve()`, which uses the SAME `MAIN_CLIENT` again.

**Fix**: Add a `rejectedClient` tracking mechanism in `YTPlayerUtils`:

```kotlin
// YTPlayerUtils.kt
private val rejectedClients = ConcurrentHashMap<String, MutableSet<String>>()

fun markClientRejected(mediaId: String, clientName: String) {
    rejectedClients.getOrPut(mediaId) { mutableSetOf() }.add(clientName)
}

fun clearRejectedClients(mediaId: String) {
    rejectedClients.remove(mediaId)
}
```

Then in `resolvePlaybackData()`, skip any client marked as rejected for that `mediaId`.

#### Fix 3: Improve PoToken Reliability

**`PoTokenGenerator.kt`** — The current WebView approach can fail silently. Add:

1. **Timeout guard**: Kill WebView if no result within 10 seconds
2. **Fallback mode**: If PoToken fails, skip PoToken-dependent clients entirely
3. **Caching**: Cache the PoToken for the session duration

```kotlin
// PoTokenGenerator.kt
private val cachedPoToken = AtomicReference<PoTokenResult?>()

suspend fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
    cachedPoToken.get()?.let { return it }
    return withTimeout(10_000) {
        // ... existing WebView logic ...
    }.also { cachedPoToken.set(it) }
}
```
#### Fix 4: Add `serverAbrStreamingUrl` / `fexp` Extraction

Mirror the reference app's logic of extracting `fexp` from the `serverAbrStreamingUrl`:

```kotlin
val fexp = playerResponse.streamingData
    ?.serverAbrStreamingUrl
    ?.toUri()
    ?.getQueryParameter("fexp")

if (fexp != null) {
    playbackTracking.atrUrl.baseUrl += "&fexp=$fexp"
    playbackTracking.videostatsPlaybackUrl.baseUrl += "&fexp=$fexp"
}
```

#### Fix 5: Add HTML Page Scraping Fallback

When all clients fail, scrape YouTube Music HTML for `ytInitialPlayerResponse`:

```kotlin
private suspend fun scrapePlayerResponse(videoId: String): PlayerResponse? {
    val url = "https://music.youtube.com/watch?v=$videoId"
    val html = httpClient.newCall(okhttp3.Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT_WEB)
        .build()
    ).execute().body?.string() ?: return null
    
    val pattern = """ytInitialPlayerResponse\s*=\s*({.*?});""".toRegex()
    val json = pattern.find(html)?.groupValues?.get(1) ?: return null
    return Json.decodeFromString<PlayerResponse>(json)
}
```

#### Fix 6: Update n-Parameter Deobfuscation Order

Try `NewPipeExtractor.deobfuscateThrottlingParam()` **first** (faster, no WebView), then WebView solvers:

```kotlin
fun getUrlFromFormat(/*...*/): String? {
    if (!format.url.isNullOrEmpty()) return format.url
    // 1st: NewPipe — fastest, no WebView needed
    val newPipeUrl = NewPipeExtractor.getStreamUrl(format, videoId)
    if (newPipeUrl != null) return newPipeUrl
    // 2nd: Cipher deobfuscation (WebView-based)
    val cipherUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
    if (cipherUrl != null) return cipherUrl
    // 3rd: NewPipe StreamInfo fallback
    return YouTube.getNewPipeStreamUrls(videoId).firstOrNull()?.second
}
```

---

## 4. Mid-Term Playback Fixes

#### Fix 7: SABR-Only Response Handling

If all formats arrive without `url` or `signatureCipher`, skip that client immediately:

```kotlin
fun isSabrOnlyResponse(response: PlayerResponse): Boolean {
    return response.streamingData?.adaptiveFormats?.all { 
        it.url == null && it.signatureCipher == null && it.cipher == null
    } == true
}
```

#### Fix 8: IP/Proxy Rotation on Bot Detection

When `BotDetectionMitigator` detects a bot error, escalate rotation:

```kotlin
suspend fun escalateRotation() {
    when (failureCount.get()) {
        0, 1 -> rotateGuestSession()
        2, 3 -> rotateIpVersion()      // switch to IPv6
        4, 5 -> YouTube.useProxy(nextProxy)
        else -> YouTube.useProxy(randomProxy)
    }
}
```

#### Fix 9: Add Stream Headers Matching Client

Each stream URL fetch must include headers matching the client that produced it:

```kotlin
playbackData = PlaybackData(
    streamUrl = resolvedUrl,
    streamHeaders = buildMap {
        put("User-Agent", client.userAgent)
        put("Origin", if (isWebClient) ORIGIN_YOUTUBE_MUSIC else "")
        put("Referer", if (isWebClient) "$ORIGIN_YOUTUBE_MUSIC/" else "")
    }
)
```
---

## 5. Implementation Plan (Playback Fix)

### Phase 1: Reorder Clients + Add Headers (1-2 days)

Files to modify:
- `app/src/main/kotlin/com/audic/music/utils/YTPlayerUtils.kt`
  - Change `MAIN_CLIENT` to `TVHTML5_SIMPLY_EMBEDDED_PLAYER`
  - Reorder `STREAM_FALLBACK_CLIENTS` to prioritize web clients
  - Add stream headers (User-Agent, Origin, Referer) to `PlaybackData`
- `innertube/src/main/kotlin/com/music/innertube/models/YouTubeClient.kt`
  - Add `isWebClient: Boolean` property
  - Add `origin` and `referer` properties
- `app/src/main/kotlin/com/audic/music/playback/MusicService.kt`
  - Wire headers into `ResolvingDataSource` via `MediaItem` metadata

### Phase 2: Retry with Different Client (1 day)

Files to modify:
- `app/src/main/kotlin/com/audic/music/utils/YTPlayerUtils.kt`
  - Add `rejectedClients` tracking map
  - Add `markClientRejected()`, `clearRejectedClients()`, skip-rejected logic
- `app/src/main/kotlin/com/audic/music/playback/MusicService.kt`
  - Call `markStreamClientRejected()` BEFORE retry
  - Ensure `player.prepare()` triggers fresh client selection

### Phase 3: Improve PoToken Reliability (1 day)

Files to modify:
- `app/src/main/kotlin/com/audic/music/utils/potoken/PoTokenGenerator.kt`
  - Add timeout guard (10s)
  - Add session-based caching
  - Add fallback mode (skip PoToken-dependent clients if generation fails)
- `app/src/main/kotlin/com/audic/music/utils/YTPlayerUtils.kt`
  - Handle null PoToken gracefully: fall through to non-PoToken clients

### Phase 4: Add HTML Scraping + SABR Detection (1-2 days)

Files to modify:
- `app/src/main/kotlin/com/audic/music/utils/YTPlayerUtils.kt`
  - Add `scrapePlayerResponse()` method
  - Add `isSabrOnlyResponse()` detection
  - Integrate scraping as final fallback before throwing error

### Phase 5: Bot Detection Escalation (2-3 days)

Files to modify:
- `app/src/main/kotlin/com/audic/music/utils/BotDetectionMitigator.kt`
  - Add escalation tiers (visitorData → IPv6 → proxy)
  - Add `maxFailuresBeforeRotation` configuration
- `innertube/src/main/kotlin/com/music/innertube/YouTube.kt`
  - Add `useProxy(proxy)` method
  - Add IP rotation support

---

## 6. Migration / Testing Strategy (Playback)

| Step | Description | Risk | Effort |
|---|---|---|---|
| **1** | Reorder clients, test with 10 blocked songs | Low | 1h |
| **2** | Add stream headers, verify CDN 403 rate drops | Low | 30m |
| **3** | Implement rejected-client tracking and retry per-client | Medium | 2h |
| **4** | Improve PoToken with timeout + cache | Medium | 3h |
| **5** | Add SABR detection + HTML page scraping | Medium | 4h |
| **6** | Test with known-failing songs | Medium | 2h |
| **7** | Roll out to beta and monitor failure logs | Low | Ongoing |
| **8** | Implement IP/proxy rotation (if needed) | High | 8h |

**Validation criteria**: After each phase, attempt playback of 10 songs that previously failed:
1. Stream URL is resolved (check PlaybackLogs)
2. Playback starts within 5 seconds
3. No `ERROR_CODE_IO_UNSPECIFIED` (2000) errors
4. No `ERROR_CODE_REMOTE_ERROR` errors

**Rollback plan**: Keep the old client order as a comment. If the new order increases failures, revert `MAIN_CLIENT` to `ANDROID_VR` immediately. Add a settings toggle for client preference.
---

# Part 2: AI Service Architecture Enhancement (Lower Priority)

> **Note**: The AI translation feature is currently **working fine**. This section describes architecture improvements that can be implemented after the playback fix is complete.

---

## 7. Current AI State (Audic)

### Architecture

Audic has **three separate API service objects** plus a **streaming variant**:

```
app/src/main/kotlin/com/audic/music/api/
├── DeepLService.kt               # Non-streaming, DeepL-only
├── MistralService.kt             # Non-streaming, Mistral-only
├── OpenRouterService.kt          # Non-streaming, OpenRouter-only
└── OpenRouterStreamingService.kt # SSE streaming, OpenRouter-only
```

**Streaming implementation** (`OpenRouterStreamingService`):
- Raw OkHttp POST → read `BufferedReader` line-by-line
- Manual SSE parsing: `data:` prefix stripping, `[DONE]` marker detection
- Emits `Flow<StreamChunk>` sealed class: `Content(text)`, `Complete(translatedLines)`, `Error(message)`
- Prompt-injected JSON: the system prompt tells the model to "output ONLY a JSON array"
- Fallback parsing chain: `JSONArray` → ```json stripping → substring extraction → line-splitting
- All services share ~90% identical code

**Translation flow** (`LyricsTranslationHelper`):
1. User triggers translation from UI
2. The helper picks a service based on `aiProvider` preference
3. Sends lyrics lines as prompt → waits for response (or stream)
4. Parses JSON array of translated lines
5. Writes to Room `LyricsEntity.translatedLyrics`
6. Updates `translatedTextFlow` on each `LyricsEntry` for progressive UI

### Pain Points
- ⚠️ 3 duplicated service files with near-identical logic
- ⚠️ Fragile JSON parsing from prompt-enforced output
- ⚠️ Manual SSE handling that can break with API changes
- ⚠️ Adding a new provider requires writing another service class

---

## 8. Reference AI State (SimpMusic)

SimpMusic has a **single `AiService`** in a **KMP module**:

```
reference/core/service/aiService/src/commonMain/kotlin/org/simpmusic/aiservice/
├── AiClient.kt   # Configuration wrapper that auto-rebuilds AiService
└── AiService.kt  # Single service for all OpenAI-compatible providers
```

**Key design choices:**

| Aspect | Implementation |
|---|---|
| **API library** | `com.aallam.openai` OpenAI SDK |
| **Request format** | `chatCompletionRequest { }` DSL |
| **Response enforcement** | `ChatResponseFormat.jsonSchema(...)` |
| **Streaming** | ❌ Not used (full response wait) |
| **Providers** | `AIHost.GEMINI`, `AIHost.OPENAI`, `AIHost.CUSTOM_OPENAI` |
| **Cross-platform** | ✅ KMP (Android/iOS/Desktop JVM) |
| **Domain model** | Returns `Lyrics` with `Line(startTimeMs, endTimeMs, words)` |
| **DB coupling** | ❌ None — pure domain model |
| **Config wrapper** | `AiClient` auto-rebuilds service on config changes |

---

## 9. AI Comparison

| Aspect | Audic (Current) | SimpMusic (Reference) |
|---|---|---|
| **Streaming** | ✅ SSE via OkHttp, progressive UI | ❌ Full response wait |
| **API library** | Raw OkHttp + manual SSE | `com.aallam.openai` SDK |
| **JSON enforcement** | Prompt instructions + fallback parsing | JSON Schema |
| **Cross-platform** | ❌ Android-only | ✅ KMP |
| **Response format** | Flat `List<String>` | `Lyrics` with timestamps |
| **DB coupling** | ✅ Direct Room writes | ❌ Pure domain model |
| **Multi-provider** | ✅ OpenRouter, Mistral, DeepL, Claude | ✅ Gemini, OpenAI, Custom |
| **Config wrapper** | Manual per-service | `AiClient` auto-rebuilds |
| **Code duplication** | ⚠️ 3 files + 1 streaming variant | ✅ Single `AiService` |
| **Parsing fragility** | ⚠️ Regex fallback chain | ✅ Guaranteed by API schema |
---

## 10. AI Recommendation: Recreate (Don't Submodule)

**Why NOT to submodule:** Build system mismatch (KMP vs Android), DI mismatch (Koin vs Hilt), domain model mismatch, streaming absent in reference, maintenance burden.

**Adopt from reference:** Single unified AI service, JSON Schema, OpenAI SDK, config wrapper.

**Keep from Audic:** Progressive UI streaming updates.

---

## 11. AI Implementation Plan

### Phase A1: Add OpenAI SDK Dependency

**`gradle/libs.versions.toml`**:
```toml
[versions]
openai-client = "4.0.2"

[libraries]
openai-client = { module = "com.aallam.openai:openai-client", version.ref = "openai-client" }
openai-client-okhttp = { module = "com.aallam.openai:openai-client-okhttp", version.ref = "openai-client" }
```

### Phase A2: Create Unified `AiService.kt`

```kotlin
class AiService(
    private val provider: AiProvider,
    private val apiKey: String,
    private val baseUrl: String? = null,
    private val model: String? = null,
) {
    suspend fun translateLyrics(lyricsLines: List<String>, targetLanguage: String, mode: TranslateMode): Result<List<String>>
    fun translateLyricsStreaming(lyricsLines: List<String>, targetLanguage: String, mode: TranslateMode): Flow<StreamChunk>
}
```

### Phase A3: Create `AiClient.kt`

Auto-rebuilds `AiService` on config changes using property setters.

### Phase A4: Deprecate Old Services

| Current File | Action |
|---|---|
| `OpenRouterService.kt` | Delete after migration |
| `OpenRouterStreamingService.kt` | Delete after migration |
| `MistralService.kt` | Delete after migration |
| `DeepLService.kt` | Keep |

### Phase A5: Update `LyricsTranslationHelper.kt`

Use `AiClient` instead of direct service calls. Choose streaming vs non-streaming based on config.

### Phase A6: Update AI Settings Screen

Map providers to `AiProvider` enum, add streaming toggle, wire custom base URL.

---

## 12. AI Migration Strategy

| Step | Risk | Effort |
|---|---|---|
| Add `openai-client` dependency | Low | Small |
| Create `AiService.kt` | Medium | Medium |
| Create `AiClient.kt` | Low | Small |
| Add JSON Schema | Medium | Medium |
| Update `LyricsTranslationHelper.kt` | High | Medium |
| Test with providers | Medium | Small |
| Delete old files | Low | Small |

---

## 13. Overall Summary

### Priority 1: Fix YouTube Playback (CRITICAL)
The app is non-functional for many songs. Primary fixes:
1. **Reorder client fallback** — `TVHTML5_SIMPLY_EMBEDDED_PLAYER` as main, web clients first
2. **Retry with different client** — Skip already-rejected clients
3. **Improve PoToken reliability** — Timeout, caching, fallback
4. **Add HTML page scraping** — `ytInitialPlayerResponse` extraction
5. **Add stream headers** — Match User-Agent/Origin/Referer to client

### Priority 2: AI Architecture Enhancement (Lower Priority)
AI translation works fine. Cleanup improvements:
1. **Single unified AiService** — Replace 3+ duplicated services
2. **JSON Schema** — Reliable structured output
3. **OpenAI SDK** — Robust API interaction
4. **Keep streaming** — Maintain progressive UI updates

### Estimated Timeline
- **Playback Fix**: 3-5 days (5 phases)
- **AI Enhancement**: 3-5 days (lower priority)
- **Total**: 6-10 days for both
