# Changelog

## 2.0.4

- **Homepage order fix**: Audic Brain "Recommended for You" and "Recommended by AI" sections now correctly appear above YouTube content when randomization is enabled. Previously they fell into a generic low-weight `else` branch (score 50–150); now explicitly weighted at **250–450**, placing them in the top third of the page.

## 2.0.0

- **URL shortener**: Migrated from short.io SDK to direct YOURLS API via `audic.playpixelpro.com`
- **Share reliability**: Fixed share intent not appearing after menu dismissal — now uses app-level coroutine scope that survives popup closure
- **Server-side fix**: YOURLS endpoint hardened to skip remote title scraping, eliminating HTTP 500 on POST requests
- **Lifecycle-safe scope**: `ShareUtil` no longer depends on composable `rememberCoroutineScope()` — uses `SupervisorJob` backed scope
- **GET fallback**: Added GET query-parameter shortening as fallback when POST fails
- **Cleanup**: Removed dead `share.echomusic.fun` host check; removed unused `RoomWarnings` import (reverted, it was needed); suppressed `@Incubating` & deprecation warnings in AGP config
- **Playlist sync**: Fixed "condition is always true" warning in playlist sync retry loop — simplified null-safe `browseId` handling
- **compileSdk**: Restored to 37 (Android API level) for compatibility with latest dependencies

## 1.1.2

- Replace short.io SDK with direct YOURLS API for link shortening
- Add YOURLS_API_KEY placeholder to local.properties.template
- Fix YOURLS empty response: add HTTP status logging + GET fallback
- Read YOURLS API key from BuildConfig instead of hardcoding
- Fix compiler warning: remove unnecessary safe call on non-null ResponseBody
- Add clear logging to ShortLinkManager for share verification
- Update release workflow for better changelog generation

## 1.1.1

- New "Recommended for You" home section powered by on-device Audic Brain interest profiling
- Fix po_token.html missing: BotGuard PoToken generation now works (WEB_REMIX loads faster)
- Fix get_transcript 400 error: added YouTube Music session headers + WEB_REMIX client
- Fix Paxsenix Cloudflare 403: switched to Apple Music catalog API search with auto-refreshing JWT tokens
- Added AppleTokenManager for automatic Apple Music credential rotation
- Fix .gitignore: track po_token.html as intentional asset, ignore build metadata and baseline profiles

## 1.0.9

- Fix share dialog not opening on Android 14+: `startActivity` now called on main thread with `FLAG_ACTIVITY_NEW_TASK`
- Fix short.io "Access denied": updated API key and switched to thread-safe cache
- Fix lyrics alignment: removed double-counted `lyricsOffset` in MetroLyrics rendering
- Remove redundant non-null assertions and unnecessary Elvis operators
- Fix typos and remove unused imports/properties across the codebase

## 1.0.7

- Libre.fm integration: log in with your Libre.fm account and independently toggle scrobbling, now-playing updates, and like sync (Settings → Account → Integrations); scrobbling targets Last.fm and Libre.fm together
- Fix audio export "unsupported MIME type" error: export now writes AAC/M4A with embedded metadata and cover art (Android has no MP3 encoder)
- Fix export failing with "Transformer accessed on the wrong thread"
- Remove the dead audicmusiccanvas module and pin material-icons-extended to 1.7.8 (fixes dependency resolution)
- Update Gradle wrapper

## 1.0.6

- Fix playback failures on songs reported as "Video unavailable" (YouTube now withholds stream URLs from mobile clients): stream resolution now falls back to the VISIONOS client and the updated NewPipe extractor
- Show the real failure reason when playback resolution fails, instead of a misleading "Video unavailable"
- Rework player.js cipher handling (n-parameter deobfuscation, signature timestamp, player config persistence)
- Fix download and audio-export edge cases
- Various UI and canvas fixes

## 1.0.5

- Cache artwork to local files for offline playback of downloaded songs
- Auto-fetch and persist lyrics when downloads complete
- Restore three-dot options menu alongside download button on player
- Wire Last.fm love/unlove to heart button (enabled via settings)
- Enable export-as-MP3 by default in player menu
- Fix audio export with foreground notification and range header
- Add @Transaction annotations to playlist DAO queries

## 1.0.4

- Set default music streaming cache to unlimited (was 1GB)
- Set default image cache to 4GB (was 512MB)
- Fresh installs get the new limits; existing users keep their current settings

## 1.0.3

- Fixed update notification link returning 404
- Fixed in-app update downloading wrong APK variant
- Improved APK selection to match device architecture and build flavor

## 1.0.2

- Replace OPUS audio quality with HIGH and AUTO adaptive modes
- Add network-aware adaptive bitrate selection for AUTO quality
- Dynamic audio quality selector in settings and player UI
- Fix release APK naming to Audic-<version>-<variant>.apk
- Fix compiler warnings and deprecated API usage

## 1.0.1

- Initial release
- Fix update setting
- Fix music playback errors
- Remove legacy icon support and clean up unused resources
