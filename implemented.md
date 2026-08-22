# Implemented: v1.0.9

## Faster YouTube Streaming

### Changes made

1. **Switched primary client to WEB_REMIX**
   - `YTPlayerUtils.MAIN_CLIENT` changed from `TVHTML5_SIMPLY_EMBEDDED_PLAYER` to `WEB_REMIX`
   - WEB_REMIX is lighter, responds faster, and returns direct stream URLs reliably

2. **Reordered fallback clients**
   - WEB_REMIX → VISIONOS → ANDROID_CREATOR → TVHTML5_SIMPLY → etc.
   - Smarter ordering tries leaner clients first before falling back to heavier ones

3. **Added PoToken pre-warming at app startup**
   - New `prewarmPoToken()` method in `YTPlayerUtils` initializes BotGuard WebView with a dummy video
   - Called in `App.kt` after a 3-second delay (off the critical startup path)
   - Saves ~2-5s on first playback

4. **Added ContentHints data class** for future content-aware fallback optimization

5. **Cipher WebView pre-warming** already existed, kept unchanged

### Other improvements in v1.0.9

- **Last.fm/Libre.fm**: Switched from unreliable WebView detection to system browser + "Done" button flow
- **Share links**: Integrated short.io SDK for trackable shortened links via `share.playpixelpro.com`
- **ListenBrainz**: Fixed race condition in `checkAndSubmitListenBrainzFinished()`, added real `positionMs`, added Timber logging
- **Haptics**: Default changed from enabled to disabled
- **Deleted `share.echomusic.fun`**: Removed dead domain + intent filter
- **Release workflow**: Builds universalFoss APK + universalGms APK + universalGms AAB

### Files created

| File | Purpose |
|------|---------|
| `app/.../utils/ShortLinkManager.kt` | Wraps short.io SDK for link shortening+treatment |
| `app/.../utils/ShareUtil.kt` | Helper for coroutine-based sharing |
| `innertube/.../models/ContentHints.kt` | Data class for content-aware client selection |
| `.github/workflows/release.yml` | CI/CD workflow for automatic APK builds |

### Files modified

| File | Change |
|------|--------|
| `app/.../utils/YTPlayerUtils.kt` | MAIN_CLIENT → WEB_REMIX, reordered fallbacks, added prewarmPoToken() |
| `app/.../App.kt` | Added PoToken pre-warm at startup |
| `app/.../playback/MusicService.kt` | Fixed ListenBrainz race condition, added positionMs + logging |
| `app/.../utils/lastfm/ScrobblerClient.kt` | Made authUrlBase/apiKey protected for child class access |
| `app/.../utils/lastfm/LastFM.kt` | Added getOAuthUrl() methods |
| `app/.../utils/lastfm/LibreFM.kt` | Hardcoded shared API key, added getOAuthUrl() |
| `app/.../ui/menu/*.kt` (17 files) | Changed share from dead domain to ShareUtil.shareUrl() |
| `app/.../AndroidManifest.xml` | Removed share.echomusic.fun intent filter |
| `app/.../MainActivity.kt` | Removed share.echomusic.fun host check |
| `app/.../YTItem.kt` | Changed shareLink from share.echomusic.fun to music.youtube.com |
| `app/.../PlaylistEntity.kt` | Same |
| `app/.../ListenBrainzManager.kt` | Fixed Elvis warnings, added validateToken() |
| `app/.../LastFMSettingsScreen.kt` | Replaced password dialog with browser OAuth + Done button |
| `app/.../LibreFMSettingsScreen.kt` | Same |
| `app/.../AccountSettingsScreen.kt` | Added ListenBrainz token validation + masked display |
| `app/build.gradle.kts` | Added short.io SDK dependency, changed default alias to audicrelease |
