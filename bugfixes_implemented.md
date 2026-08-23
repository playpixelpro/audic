# Implemented: Bug Fixes (Logcat Errors)

> Date: 2026-08-23

## Summary

Fixed three pre-existing errors visible in logcat, using reference2 (Metrolist) as the verified implementation source for all fixes. None of these changes affect app state, UI, or database.

---

## Fix 1: `po_token.html` Missing (FileNotFoundException)

### Root Cause

`PoTokenWebView.kt:118` tried `assets.open("po_token.html")` but the file did not exist in `app/src/main/assets/`.

### Fix

Copied `reference2/app/src/main/assets/po_token.html` (8,610 bytes) to `app/src/main/assets/po_token.html`.

### Verification

The JavaScript protocol in audic's `PoTokenWebView.kt` calls exactly three JS functions:
1. `runBotGuard(challengeData)` -> returns `{webPoSignalOutput, botguardResponse}`
2. `createPoTokenMinter(webPoSignalOutput, integrityToken)` -> Promise
3. `obtainPoToken(u8Identifier)` -> Promise<Uint8Array>

Each of these functions is **defined identically** in reference2's `po_token.html` (lines 93, ~125, and 178 respectively).

### Impact
- ❌ No more `FileNotFoundException: po_token.html`
- ✅ WebView properly loads BotGuard JavaScript
- ✅ `WEB_REMIX` (main client) can now generate PoTokens, reducing fallback latency
- ✅ Existing `poTokenFullyFailed` fallback still guards against unexpected failures

---

## Fix 2: YouTube `get_transcript` 400 (Precondition check failed)

### Root Cause

`InnerTube.getTranscript()` was the **only** API endpoint in the file that:
1. Did **NOT** call `ytClient(client)` - missing all session headers (`X-Goog-Visitor-Id`, `X-YouTube-Client-Name/Version`, `X-Origin`, `Referer`, User-Agent)
2. Passed `null, null` for `visitorData`/`dataSyncId` in the body context

This caused YouTube to return HTTP 400 "Precondition check failed."

### Fix (1 file, innertube/InnerTube.kt)

```diff
- parameter("key", "...")
- headers {
- append("Content-Type", "application/json")
- }
+ ytClient(client)          // ← adds session headers + visitor + user agent
  parameter("key", "...")
  setBody(
      GetTranscriptBody(
-         context = client.toContext(locale, null, null),
+         context = client.toContext(locale, visitorData, dataSyncId),  // ← passes session
        ...
      )
  )
```

Reference2 delegates this to `InnerTubeX` (external KMP library) which already sends proper session context - this change mirrors that behavior.

### Impact
- ✅ Transcript requests now include proper YouTube Music session headers (`WEB_REMIX` client)
- ✅ If still fails, lyrics provider chain continues to next source (graceful fallback)

---

## Fix 3: Paxeni Cloudflare 403

### Root Cause

`Paxsenix.search()` called `lyrics.paxenix.org/apple-music/search?q=...` which is behind Cloudflare. The old User-Agent `"audicmusic/$appVersion"` was too simpliistic -> Cloudflare blocked with 403 "Attention Required!"

### Fix (ported from reference2)

Reference2 **bypasses** Cloudflare entirely by querying **Apple Music's catalog API directly**:

Reference2 verwendet:
- Apple Music catalog API: `https://amp-api.music.apple.com/v1/catalog/us/search`
- JWT token scraped from `beta.music.apple.com`'s JS bundle (via `AppleTokenManager`)
- Browser-like headers: `User-Agent`, `Origin`, `Referer`, `Accept-Language`
- Lyrics still fetched via `lyrics.paxenix.org` (non-search endpoints not blocked)

Changes ported to audic's `paxenixlyrics` module:

| File | Change |
|------|--------|
| `PaxenixModels.kt` | Added 8 Apple Music API models (search response, resources, song attributes, artwork) |
| `Paxenix.kt` | **Changed**: User-Agent to real Firefox Chrome UA, timeouts 5s->15s/4s->10s
| | **Replaced**: `search()` to use Apple Music catalog API with Bearer token
| | **Added**: `AppleTokenManager` inner class (scraps JWT from beta.music.apple.com)
| | **Added**: 401 token expiry detection -> clears token -> retries on next call
| | **Kept**: `fetchLyricsForTrack()` still uses `lyrics.paxenix.org` (non-search endpoint)

### Impact  
- ✅ No more Cloudflare 403 on search
- ✅ Lyrics still fetched from paxenix (same quality)
- ✅ Token refreshing is automatic (401 -> clear -> fetch new)
- ✅ If Apple Music search fails, returns empty list -> provider chain continues
-  ✅ Timeouts increased to match reference2 for reliability

---

## Files Modified

| File | Changes |
|------|---------|
| `app/src/main/assets/po_token.html` | **NEW** (copied from reference2) |
| `innertube/.../InnerTube.kt` | `getranscript()` now calls `ytClient()` + passes session context |
| `paxenixlyrics/.../Paxenix.kt` | New User-Agent, Apple Music search, timeouts, `AppleTokenManager` |
| `paxenixlyrics/.../odels/PaxenixModels.kt` | 8 new Apple Music API data classes |

## Files Created

| File | Purpose |
|---|---------|
| `app/src/main/assets/po_token.html` | BotGuard WebView HTML for PoToken generation |

## What Was NOT Touched

- ✅ No database entities, Room tables, or migrations
- ✅ No DAO methods
- ✅ No UI screens, navigation, or composables
- ✅ No PlayerConnection or MusicService
- ✅ No Brain components or Home sections
- ✅ No existing lyrics provider chain logic