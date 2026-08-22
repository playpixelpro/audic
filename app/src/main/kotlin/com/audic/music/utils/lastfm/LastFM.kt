package com.audic.music.utils.lastfm

/**
 * Last.fm. All protocol behaviour lives in [ScrobblerClient]; this only supplies the
 * endpoint and credentials, plus the scrobble-timing defaults shared by every target.
 */
object LastFM : ScrobblerClient(
    baseUrl = "https://ws.audioscrobbler.com/2.0/",
    authUrlBase = "https://www.last.fm/api/auth/",
    apiKey = com.audic.music.BuildConfig.LASTFM_API_KEY,
    secret = com.audic.music.BuildConfig.LASTFM_SECRET,
) {
    const val DEFAULT_SCROBBLE_DELAY_PERCENT = 0.5f
    const val DEFAULT_SCROBBLE_MIN_SONG_DURATION = 30
    const val DEFAULT_SCROBBLE_DELAY_SECONDS = 180

    /**
     * Generates the URL the user must visit in a browser / WebView to authorize the app.
     * Uses the "desktop flow" — [getToken] is called first, then its result is embedded
     * in the URL. After the user authorizes we call [getSession] with the same token.
     */
    suspend fun getOAuthUrl(): Result<String> {
        return getToken().map { token -> "$authUrlBase?api_key=$apiKey&token=${token.token}" }
    }

    suspend fun getOAuthUrlOrNull(): String? = getOAuthUrl().getOrNull()
}
