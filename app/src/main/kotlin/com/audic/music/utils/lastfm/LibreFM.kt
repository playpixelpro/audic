package com.audic.music.utils.lastfm

/**
 * Libre.fm — the GNU FM instance at libre.fm. It implements the same AudioScrobbler 2.0
 * protocol as Last.fm, so [ScrobblerClient] covers it unchanged; only the endpoint and
 * credentials differ.
 *
 * Scrobble timing (delay percent / minimum duration / delay seconds) is shared with
 * Last.fm rather than duplicated — those DataStore keys are global.
 */
object LibreFM : ScrobblerClient(
    baseUrl = "https://libre.fm/2.0/",
    authUrlBase = "https://libre.fm/api/auth/",
    apiKey = com.audic.music.BuildConfig.LIBREFM_API_KEY,
    secret = com.audic.music.BuildConfig.LIBREFM_SECRET,
)
