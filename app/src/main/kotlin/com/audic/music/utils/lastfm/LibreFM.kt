package com.audic.music.utils.lastfm

/**
 * Libre.fm — the GNU FM instance at libre.fm. It implements the same AudioScrobbler 2.0
 * protocol as Last.fm, so [ScrobblerClient] covers it unchanged; only the endpoint and
 * credentials differ.
 *
 * Uses the shared public API key from web-scrobbler (registered with Libre.fm's maintainer
 * for the OAuth web flow). This key identifies the APPLICATION, not the user — all Audic
 * users share it, same as web-scrobbler and other FOSS clients.
 *
 * Scrobble timing (delay percent / minimum duration / delay seconds) is shared with
 * Last.fm rather than duplicated — those DataStore keys are global.
 */
object LibreFM : ScrobblerClient(
    baseUrl = "https://libre.fm/2.0/",
    authUrlBase = "https://libre.fm/api/auth/",
    apiKey = "r8i1y91hz71tcx7vyrp9hk1alhqp1898",
    secret = "8187db5vg234yq6tm7o62q8mtl1niala",
) {
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
